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
// to validate and copy as an original-only media asset.
type VideoProcessRequest struct {
	MediaID string
	// SourceObjectKey is the immutable ingress original.
	SourceObjectKey string
	SourceVersionID string
	ContentType     string
	Generation      int
}

// VideoProcessResult contains the validated, version-pinned original video.
type VideoProcessResult struct {
	Original ProcessedVariant
}

// VideoProcessor validates an immutable ingress object with a VideoProbe and
// copies the accepted original without producing image-style derivatives.
type VideoProcessor struct {
	Store         ObjectStore
	Probe         VideoProbe
	AllowedCodecs map[string]struct{}
	MaxDuration   time.Duration
	Limits        ProcessingLimits
}

// Process validates the configured video duration, codec, and container before
// copying its exact content to an immutable generation original.
func (processor VideoProcessor) Process(ctx context.Context, request VideoProcessRequest) (VideoProcessResult, error) {
	if processor.Store == nil || processor.Probe == nil {
		return VideoProcessResult{}, fmt.Errorf("video processor is not configured")
	}
	if !processor.Limits.Valid() {
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
	if err := processor.validateSource(ctx, request, extension); err != nil {
		return VideoProcessResult{}, err
	}

	return processor.copyOriginal(ctx, request, extension)
}

func (processor VideoProcessor) validateSource(ctx context.Context, request VideoProcessRequest, extension string) error {
	directory, err := os.MkdirTemp("", "rwms-media-probe-*")
	if err != nil {
		return fmt.Errorf("create video probe workspace: %w", err)
	}
	defer os.RemoveAll(directory)
	path := filepath.Join(directory, "source"+extension)
	if err := processor.downloadToFile(ctx, request.SourceObjectKey, request.SourceVersionID, path); err != nil {
		return err
	}
	return processor.validateVideoFile(ctx, path, extension)
}

func (processor VideoProcessor) validateVideoFile(ctx context.Context, path, extension string) error {
	probeContext, cancel := context.WithTimeout(ctx, processor.Limits.Timeout)
	defer cancel()
	metadata, err := processor.Probe.Probe(probeContext, path)
	if err != nil {
		return fmt.Errorf("validate video container")
	}
	if metadata.Duration <= 0 || metadata.Duration > processor.MaxDuration {
		return fmt.Errorf("video duration is outside configured limits")
	}
	if _, allowed := processor.AllowedCodecs[metadata.Codec]; !allowed {
		return fmt.Errorf("video codec is not allowed")
	}
	if !containerMatches(extension, metadata.Container) {
		return fmt.Errorf("video container does not match declared type")
	}
	return nil
}

func (processor VideoProcessor) copyOriginal(ctx context.Context, request VideoProcessRequest, extension string) (VideoProcessResult, error) {
	source, metadata, err := getObject(ctx, processor.Store, request.SourceObjectKey, request.SourceVersionID)
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("read video source: %w", err)
	}
	defer source.Close()
	if metadata.SizeBytes <= 0 || metadata.SizeBytes > processor.Limits.MaxVideoBytes {
		return VideoProcessResult{}, fmt.Errorf("video source size exceeds configured limit")
	}
	hash := sha256.New()
	result := newProcessedVariant(
		VariantOriginal,
		OriginalObjectKey(request.MediaID, request.Generation, extension),
		request.ContentType,
		metadata.SizeBytes,
		0,
		0,
		"",
	)
	metadata, err = putObject(ctx, processor.Store, result.ObjectKey, io.TeeReader(source, hash), result.SizeBytes, result.ContentType)
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("write video original: %w", err)
	}
	result.ObjectVersionID = metadata.VersionID
	result.ChecksumSHA256 = hex.EncodeToString(hash.Sum(nil))
	return VideoProcessResult{Original: result}, nil
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
