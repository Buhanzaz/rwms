package media

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// ErrVideoTranscoderUnavailable identifies a missing or inaccessible ffmpeg
// executable rather than invalid user media.
var ErrVideoTranscoderUnavailable = errors.New("video transcoder is unavailable")

// VideoTranscodeRequest bounds one source-to-playback conversion inside an
// already isolated processing workspace.
type VideoTranscodeRequest struct {
	SourcePath      string
	DestinationPath string
	MaxOutputBytes  int64
}

// VideoTranscoder creates the canonical public playback representation while
// leaving object persistence and generation fencing to VideoProcessor.
type VideoTranscoder interface {
	Transcode(context.Context, VideoTranscodeRequest) error
}

// FFmpegTranscoder invokes one resolved ffmpeg executable without a shell.
type FFmpegTranscoder struct {
	executable string
}

// NewFFmpegTranscoder resolves the configured executable during startup so a
// missing runtime dependency fails before the service accepts work.
func NewFFmpegTranscoder(executable string) (FFmpegTranscoder, error) {
	executable = strings.TrimSpace(executable)
	if executable == "" {
		return FFmpegTranscoder{}, fmt.Errorf("%w: executable is blank", ErrVideoTranscoderUnavailable)
	}
	resolved, err := exec.LookPath(executable)
	if err != nil {
		return FFmpegTranscoder{}, fmt.Errorf("%w: %v", ErrVideoTranscoderUnavailable, err)
	}
	return FFmpegTranscoder{executable: resolved}, nil
}

// Transcode produces an H.264/yuv420p and AAC MP4 capped at 1280x720. It strips
// metadata, keeps aspect ratio and even dimensions, and bounds output at the
// ffmpeg muxer as well as at the caller's postcondition check.
func (transcoder FFmpegTranscoder) Transcode(ctx context.Context, request VideoTranscodeRequest) error {
	if transcoder.executable == "" || strings.TrimSpace(request.SourcePath) == "" ||
		strings.TrimSpace(request.DestinationPath) == "" || request.MaxOutputBytes <= 0 ||
		filepath.Clean(request.SourcePath) == filepath.Clean(request.DestinationPath) {
		return fmt.Errorf("invalid video transcode request")
	}
	command := exec.CommandContext(ctx, transcoder.executable, ffmpegPlaybackArguments(request)...)
	command.Stdout = &boundedDiagnosticBuffer{}
	command.Stderr = &boundedDiagnosticBuffer{}
	if err := command.Run(); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		var executableError *exec.Error
		if errors.As(err, &executableError) {
			return fmt.Errorf("%w: %v", ErrVideoTranscoderUnavailable, err)
		}
		return fmt.Errorf("ffmpeg playback transcode failed")
	}
	return nil
}

func ffmpegPlaybackArguments(request VideoTranscodeRequest) []string {
	return []string{
		"-nostdin", "-hide_banner", "-loglevel", "error", "-y",
		"-i", request.SourcePath,
		"-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn",
		"-map_metadata", "-1", "-map_chapters", "-1",
		"-vf", "scale=w=min(1280\\,iw):h=min(720\\,ih):force_original_aspect_ratio=decrease:force_divisible_by=2",
		"-c:v", "libx264", "-preset", "medium", "-crf", "28", "-pix_fmt", "yuv420p",
		"-c:a", "aac", "-b:a", "128k", "-ac", "2",
		"-movflags", "+faststart", "-fs", strconv.FormatInt(request.MaxOutputBytes, 10),
		request.DestinationPath,
	}
}
