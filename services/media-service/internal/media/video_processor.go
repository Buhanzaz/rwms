package media

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// VideoProcessRequest names one immutable video ingress object and generation
// to validate, retain exactly, and derive a compressed playback representation.
type VideoProcessRequest struct {
	MediaID string
	// SourceObjectKey is the immutable ingress original.
	SourceObjectKey string
	SourceVersionID string
	ContentType     string
	Generation      int
}

// VideoProcessResult contains the pinned source and compressed MP4 playback
// objects that together make one READY video generation.
type VideoProcessResult struct {
	Original ProcessedVariant
	Playback ProcessedVariant
}

// VideoProcessor validates one immutable ingress object, preserves its exact
// bytes as ORIGINAL, and delegates compressed playback creation to an injected
// transcoder.
type VideoProcessor struct {
	Store          ObjectStore
	Probe          VideoProbe
	Transcoder     VideoTranscoder
	AllowedCodecs  map[string]struct{}
	MaxDuration    time.Duration
	MaxOutputBytes int64
	Limits         ProcessingLimits
}

// Process validates the source duration, codec, and container, creates and
// validates an H.264 MP4 derivative, then writes both immutable generation
// objects. The source is downloaded once and ORIGINAL bytes are never rewritten.
func (processor VideoProcessor) Process(ctx context.Context, request VideoProcessRequest) (VideoProcessResult, error) {
	if processor.Store == nil || processor.Probe == nil || processor.Transcoder == nil {
		return VideoProcessResult{}, fmt.Errorf("video processor is not configured")
	}
	if !processor.Limits.Valid() || processor.MaxOutputBytes <= 0 {
		return VideoProcessResult{}, fmt.Errorf("video processor limits are required")
	}
	if len(processor.AllowedCodecs) == 0 || processor.MaxDuration <= 0 {
		return VideoProcessResult{}, fmt.Errorf("video validation policy is required")
	}
	if request.MediaID == "" || request.SourceObjectKey == "" || request.SourceVersionID == "" || request.Generation <= 0 {
		return VideoProcessResult{}, fmt.Errorf("invalid video processing request")
	}
	contentType, err := normalizeVideoContentType(request.ContentType)
	if err != nil {
		return VideoProcessResult{}, err
	}
	request.ContentType = contentType
	extension, err := videoExtension(request.ContentType)
	if err != nil {
		return VideoProcessResult{}, err
	}

	directory, err := os.MkdirTemp("", "rwms-media-video-*")
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("create video processing workspace: %w", err)
	}
	defer os.RemoveAll(directory)
	sourcePath := filepath.Join(directory, "source"+extension)
	if err := processor.downloadToFile(ctx, request.SourceObjectKey, request.SourceVersionID, sourcePath); err != nil {
		return VideoProcessResult{}, err
	}
	sourceMetadata, err := processor.validateVideoFile(ctx, sourcePath, extension)
	if err != nil {
		return VideoProcessResult{}, err
	}
	playbackPath := filepath.Join(directory, "playback.mp4")
	transcodeContext, cancel := context.WithTimeout(ctx, processor.Limits.Timeout)
	err = processor.Transcoder.Transcode(transcodeContext, VideoTranscodeRequest{
		SourcePath: sourcePath, DestinationPath: playbackPath, MaxOutputBytes: processor.MaxOutputBytes,
	})
	cancel()
	if err != nil {
		return VideoProcessResult{}, err
	}
	if err := processor.validatePlayback(ctx, playbackPath, sourceMetadata); err != nil {
		return VideoProcessResult{}, err
	}

	original, err := processor.writeFileVariant(ctx, sourcePath, newProcessedVariant(
		VariantOriginal, OriginalObjectKey(request.MediaID, request.Generation, extension),
		request.ContentType, 0, 0, 0, "",
	))
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("write video original: %w", err)
	}
	playback, err := processor.writeFileVariant(ctx, playbackPath, newProcessedVariant(
		VariantPlayback, VideoPlaybackObjectKey(request.MediaID, request.Generation),
		"video/mp4", 0, 0, 0, "",
	))
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("write video playback: %w", err)
	}
	return VideoProcessResult{Original: original, Playback: playback}, nil
}

func (processor VideoProcessor) validateVideoFile(ctx context.Context, path, extension string) (VideoMetadata, error) {
	probeContext, cancel := context.WithTimeout(ctx, processor.Limits.Timeout)
	defer cancel()
	metadata, err := processor.Probe.Probe(probeContext, path)
	if err != nil {
		return VideoMetadata{}, fmt.Errorf("validate video container")
	}
	if metadata.Duration <= 0 || metadata.Duration > processor.MaxDuration {
		return VideoMetadata{}, fmt.Errorf("video duration is outside configured limits")
	}
	if _, allowed := processor.AllowedCodecs[metadata.Codec]; !allowed {
		return VideoMetadata{}, fmt.Errorf("video codec is not allowed")
	}
	if !containerMatches(extension, metadata.Container) {
		return VideoMetadata{}, fmt.Errorf("video container does not match declared type")
	}
	return metadata, nil
}

func (processor VideoProcessor) validatePlayback(ctx context.Context, path string, source VideoMetadata) error {
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > processor.MaxOutputBytes {
		return fmt.Errorf("video playback exceeds configured output limit")
	}
	probeContext, cancel := context.WithTimeout(ctx, processor.Limits.Timeout)
	defer cancel()
	metadata, err := processor.Probe.Probe(probeContext, path)
	if err != nil || metadata.Codec != "h264" || !containerMatches(".mp4", metadata.Container) {
		return fmt.Errorf("video playback is invalid")
	}
	tolerance := source.Duration / 100
	if tolerance < time.Second {
		tolerance = time.Second
	}
	if metadata.Duration < source.Duration-tolerance || metadata.Duration > source.Duration+tolerance {
		return fmt.Errorf("video playback duration does not match source")
	}
	return nil
}

func (processor VideoProcessor) writeFileVariant(
	ctx context.Context,
	path string,
	result ProcessedVariant,
) (ProcessedVariant, error) {
	file, err := os.Open(path)
	if err != nil {
		return ProcessedVariant{}, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() <= 0 {
		return ProcessedVariant{}, fmt.Errorf("video output is not a regular non-empty file")
	}
	result.SizeBytes = info.Size()
	hash := sha256.New()
	metadata, err := putObject(ctx, processor.Store, result.ObjectKey, io.TeeReader(file, hash), result.SizeBytes, result.ContentType)
	if err != nil {
		return ProcessedVariant{}, err
	}
	result.ObjectVersionID = metadata.VersionID
	result.ChecksumSHA256 = hex.EncodeToString(hash.Sum(nil))
	return result, nil
}

func (processor VideoProcessor) downloadToFile(ctx context.Context, objectKey, versionID, destination string) error {
	source, metadata, err := getObject(ctx, processor.Store, objectKey, versionID)
	if err != nil {
		return fmt.Errorf("read video source: %w", err)
	}
	defer source.Close()
	limit := processor.Limits.MaxVideoBytes
	if metadata.SizeBytes <= 0 || metadata.SizeBytes > limit {
		return fmt.Errorf("video source exceeds byte limit")
	}
	target, err := os.Create(destination)
	if err != nil {
		return fmt.Errorf("create video source file: %w", err)
	}
	defer target.Close()
	written, err := io.Copy(target, io.LimitReader(source, limit+1))
	if err != nil {
		return fmt.Errorf("download video source: %w", err)
	}
	if written != metadata.SizeBytes || written > limit {
		return fmt.Errorf("video source length changed while reading")
	}
	return nil
}

func videoExtension(contentType string) (string, error) {
	switch contentType {
	case "video/mp4":
		return ".mp4", nil
	case "video/webm":
		return ".webm", nil
	default:
		return "", fmt.Errorf("unsupported original video content type %q", contentType)
	}
}

func normalizeVideoContentType(contentType string) (string, error) {
	normalized := strings.ToLower(strings.TrimSpace(strings.Split(contentType, ";")[0]))
	if _, err := videoExtension(normalized); err != nil {
		return "", err
	}
	return normalized, nil
}

func containerMatches(extension, container string) bool {
	for _, candidate := range strings.Split(strings.ToLower(container), ",") {
		candidate = strings.TrimSpace(candidate)
		if (extension == ".mp4" && candidate == "mp4") || (extension == ".webm" && candidate == "webm") {
			return true
		}
	}
	return false
}
