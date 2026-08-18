package media

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestFFmpegPlaybackArgumentsPinCompressionAndBounds(t *testing.T) {
	request := VideoTranscodeRequest{
		SourcePath: "/tmp/source.webm", DestinationPath: "/tmp/playback.mp4", MaxOutputBytes: 8 << 20,
	}
	arguments := ffmpegPlaybackArguments(request)
	wanted := []string{
		"-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", "/tmp/source.webm",
		"-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn",
		"-map_metadata", "-1", "-map_chapters", "-1",
		"-vf", "scale=w=min(1280\\,iw):h=min(720\\,ih):force_original_aspect_ratio=decrease:force_divisible_by=2",
		"-c:v", "libx264", "-preset", "medium", "-crf", "28", "-pix_fmt", "yuv420p",
		"-c:a", "aac", "-b:a", "128k", "-ac", "2", "-movflags", "+faststart",
		"-fs", "8388608", "/tmp/playback.mp4",
	}
	if !reflect.DeepEqual(arguments, wanted) {
		t.Fatalf("ffmpeg arguments = %#v, want %#v", arguments, wanted)
	}
}

func TestVideoExecutablesRejectBlankStartupConfiguration(t *testing.T) {
	if _, err := NewFFmpegTranscoder(" "); !errors.Is(err, ErrVideoTranscoderUnavailable) {
		t.Fatalf("NewFFmpegTranscoder() error = %v", err)
	}
	if _, err := NewFFprobe(" "); !errors.Is(err, ErrVideoProbeUnavailable) {
		t.Fatalf("NewFFprobe() error = %v", err)
	}
}

func TestFFmpegTranscoderProducesBoundedH264AACPlayback(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe is not installed")
	}

	directory := t.TempDir()
	source := filepath.Join(directory, "source.mp4")
	playback := filepath.Join(directory, "playback.mp4")
	contextWithTimeout, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	generate := exec.CommandContext(contextWithTimeout, "ffmpeg",
		"-nostdin", "-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24",
		"-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
		"-t", "2", "-c:v", "libx264", "-crf", "12", "-pix_fmt", "yuv420p",
		"-c:a", "aac", "-b:a", "192k", source,
	)
	if output, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("generate source video: %v: %s", err, output)
	}

	transcoder, err := NewFFmpegTranscoder("ffmpeg")
	if err != nil {
		t.Fatalf("resolve ffmpeg: %v", err)
	}
	const maximumOutputBytes = int64(4 << 20)
	if err := transcoder.Transcode(contextWithTimeout, VideoTranscodeRequest{
		SourcePath: source, DestinationPath: playback, MaxOutputBytes: maximumOutputBytes,
	}); err != nil {
		t.Fatalf("transcode playback: %v", err)
	}
	info, err := os.Stat(playback)
	if err != nil {
		t.Fatalf("stat playback: %v", err)
	}
	if info.Size() <= 0 || info.Size() > maximumOutputBytes {
		t.Fatalf("playback size = %d", info.Size())
	}
	metadata, err := (FFprobe{}).Probe(contextWithTimeout, playback)
	if err != nil {
		t.Fatalf("probe playback: %v", err)
	}
	if metadata.Codec != "h264" || !containerMatches(".mp4", metadata.Container) {
		t.Fatalf("playback metadata = %#v", metadata)
	}
	audio := exec.CommandContext(contextWithTimeout, "ffprobe", "-v", "error",
		"-select_streams", "a:0", "-show_entries", "stream=codec_name",
		"-of", "default=noprint_wrappers=1:nokey=1", playback)
	audioCodec, err := audio.Output()
	if err != nil || strings.TrimSpace(string(audioCodec)) != "aac" {
		t.Fatalf("playback audio codec = %q, error = %v", audioCodec, err)
	}
}
