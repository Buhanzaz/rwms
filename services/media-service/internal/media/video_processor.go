package media

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type CommandRunner interface {
	Run(context.Context, string, ...string) error
}

type ExecCommandRunner struct{}

func (ExecCommandRunner) Run(ctx context.Context, name string, arguments ...string) error {
	command := exec.CommandContext(ctx, name, arguments...)
	output, err := command.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s failed: %w: %s", name, err, string(output))
	}
	return nil
}

type VideoProcessRequest struct {
	MediaID string
	// SourceObjectKey is the immutable ingress original for the same reason as
	// image processing: Rotation is absolute, not a delta from a prior variant.
	SourceObjectKey string
	ContentType     string
	Generation      int
	Rotation        Rotation
}

type VideoProcessResult struct {
	Original ProcessedVariant
}

type VideoProcessor struct {
	Store  ObjectStore
	Runner CommandRunner
}

func (processor VideoProcessor) Process(ctx context.Context, request VideoProcessRequest) (VideoProcessResult, error) {
	if processor.Store == nil || processor.Runner == nil {
		return VideoProcessResult{}, fmt.Errorf("video processor is not configured")
	}
	if request.MediaID == "" || request.SourceObjectKey == "" || request.Generation < 0 {
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

	if request.Rotation == Rotation0 {
		return processor.copyOriginal(ctx, request, extension)
	}
	return processor.rotateOriginal(ctx, request, extension)
}

func (processor VideoProcessor) copyOriginal(ctx context.Context, request VideoProcessRequest, extension string) (VideoProcessResult, error) {
	source, metadata, err := processor.Store.Get(ctx, request.SourceObjectKey)
	if err != nil {
		return VideoProcessResult{}, fmt.Errorf("read video source: %w", err)
	}
	defer source.Close()
	if metadata.SizeBytes < 0 {
		return VideoProcessResult{}, fmt.Errorf("video source size is unknown")
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
	if err := processor.Store.Put(ctx, result.ObjectKey, io.TeeReader(source, hash), result.SizeBytes, result.ContentType); err != nil {
		return VideoProcessResult{}, fmt.Errorf("write video original: %w", err)
	}
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
	if err := processor.downloadToFile(ctx, request.SourceObjectKey, inputPath); err != nil {
		return VideoProcessResult{}, err
	}
	if err := processor.Runner.Run(ctx, "ffmpeg", videoRotationArguments(inputPath, outputPath, extension, request.Rotation)...); err != nil {
		return VideoProcessResult{}, fmt.Errorf("rotate video: %w", err)
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
	if err := processor.Store.Put(ctx, result.ObjectKey, io.TeeReader(output, hash), result.SizeBytes, result.ContentType); err != nil {
		return VideoProcessResult{}, fmt.Errorf("write rotated video: %w", err)
	}
	result.ChecksumSHA256 = hex.EncodeToString(hash.Sum(nil))
	return VideoProcessResult{Original: result}, nil
}

func (processor VideoProcessor) downloadToFile(ctx context.Context, objectKey, destination string) error {
	source, _, err := processor.Store.Get(ctx, objectKey)
	if err != nil {
		return fmt.Errorf("read video source: %w", err)
	}
	defer source.Close()
	target, err := os.Create(destination)
	if err != nil {
		return fmt.Errorf("create video source file: %w", err)
	}
	defer target.Close()
	if _, err := io.Copy(target, source); err != nil {
		return fmt.Errorf("download video source: %w", err)
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

func videoRotationArguments(inputPath, outputPath, extension string, rotation Rotation) []string {
	filter := map[Rotation]string{
		Rotation90:  "transpose=1",
		Rotation180: "transpose=1,transpose=1",
		Rotation270: "transpose=2",
	}[rotation]
	arguments := []string{"-hide_banner", "-loglevel", "error", "-y", "-i", inputPath, "-vf", filter}
	if extension == ".webm" {
		arguments = append(arguments, "-c:v", "libvpx-vp9", "-c:a", "libopus")
	} else {
		arguments = append(arguments, "-c:v", "libx264", "-c:a", "copy", "-movflags", "+faststart")
	}
	return append(arguments, outputPath)
}
