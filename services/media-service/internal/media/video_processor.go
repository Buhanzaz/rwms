package media

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

type CommandRunner interface {
	Run(context.Context, string, ...string) error
}

type ExecCommandRunner struct{}

func (ExecCommandRunner) Run(ctx context.Context, name string, arguments ...string) error {
	command := exec.CommandContext(ctx, name, arguments...)
	var diagnostics boundedBuffer
	command.Stdout = &diagnostics
	command.Stderr = &diagnostics
	err := command.Run()
	if err != nil {
		return fmt.Errorf("%s failed", name)
	}
	return nil
}

type boundedBuffer struct{ bytes.Buffer }

func (buffer *boundedBuffer) Write(value []byte) (int, error) {
	const maximum = 4096
	original := len(value)
	if buffer.Len() < maximum {
		remaining := maximum - buffer.Len()
		if len(value) > remaining {
			value = value[:remaining]
		}
		_, _ = buffer.Buffer.Write(value)
	}
	return original, nil
}

type VideoProcessRequest struct {
	MediaID string
	// SourceObjectKey is the immutable ingress original for the same reason as
	// image processing: Rotation is absolute, not a delta from a prior variant.
	SourceObjectKey string
	SourceVersionID string
	ContentType     string
	Generation      int
	Rotation        Rotation
}

type VideoProcessResult struct {
	Original ProcessedVariant
}

type VideoProcessor struct {
	Store         ObjectStore
	Runner        CommandRunner
	Probe         VideoProbe
	AllowedCodecs map[string]struct{}
	MaxDuration   time.Duration
	Limits        ProcessingLimits
}

func (processor VideoProcessor) Process(ctx context.Context, request VideoProcessRequest) (VideoProcessResult, error) {
	if processor.Store == nil || processor.Runner == nil || processor.Probe == nil {
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
	if _, err := ParseRotation(int16(request.Rotation)); err != nil {
		return VideoProcessResult{}, err
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

	if request.Rotation == Rotation0 {
		return processor.copyOriginal(ctx, request, extension)
	}
	return processor.rotateOriginal(ctx, request, extension)
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

func (processor VideoProcessor) rotateOriginal(ctx context.Context, request VideoProcessRequest, extension string) (VideoProcessResult, error) {
	directory, err := os.MkdirTemp("", "rwms-media-video-*")
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("create video workspace: %w", err)
	}
	defer os.RemoveAll(directory)

	inputPath := filepath.Join(directory, "source"+extension)
	outputPath := filepath.Join(directory, "rotated"+extension)
	if err := processor.downloadToFile(ctx, request.SourceObjectKey, request.SourceVersionID, inputPath); err != nil {
		return VideoProcessResult{}, err
	}
	processingContext, cancel := context.WithTimeout(ctx, processor.Limits.Timeout)
	defer cancel()
	if err := processor.Runner.Run(processingContext, "ffmpeg", videoRotationArguments(inputPath, outputPath, extension, request.Rotation, processor.Limits.MaxVideoOutputBytes)...); err != nil {
		return VideoProcessResult{}, fmt.Errorf("rotate video: %w", err)
	}
	if err := processor.validateVideoFile(ctx, outputPath, extension); err != nil {
		return VideoProcessResult{}, fmt.Errorf("validate rotated video: %w", err)
	}
	output, err := os.Open(outputPath)
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("open rotated video: %w", err)
	}
	defer output.Close()
	info, err := output.Stat()
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("read rotated video metadata: %w", err)
	}
	if info.Size() <= 0 || info.Size() > processor.Limits.MaxVideoOutputBytes {
		return VideoProcessResult{}, fmt.Errorf("rotated video exceeds output limit")
	}
	hash := sha256.New()
	result := newProcessedVariant(
		VariantOriginal,
		OriginalObjectKey(request.MediaID, request.Generation, extension),
		request.ContentType,
		info.Size(),
		0,
		0,
		"",
	)
	metadata, err := putObject(ctx, processor.Store, result.ObjectKey, io.TeeReader(output, hash), result.SizeBytes, result.ContentType)
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("write rotated video: %w", err)
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

func videoRotationArguments(inputPath, outputPath, extension string, rotation Rotation, maxOutputBytes int64) []string {
	filter := map[Rotation]string{
		Rotation90:  "transpose=1",
		Rotation180: "transpose=1,transpose=1",
		Rotation270: "transpose=2",
	}[rotation]
	arguments := []string{"-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", inputPath, "-vf", filter}
	if extension == ".webm" {
		arguments = append(arguments, "-c:v", "libvpx-vp9", "-c:a", "libopus")
	} else {
		arguments = append(arguments, "-c:v", "libx264", "-c:a", "copy", "-movflags", "+faststart")
	}
	return append(arguments, "-fs", fmt.Sprintf("%d", maxOutputBytes), outputPath)
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
