package media

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// ErrVideoProbeUnavailable identifies a missing or inaccessible ffprobe
// executable rather than invalid user media.
var ErrVideoProbeUnavailable = errors.New("video probe is unavailable")

// VideoMetadata is the codec, container, and duration needed to validate an
// uploaded video before it becomes a media generation.
type VideoMetadata struct {
	Codec     string
	Container string
	Duration  time.Duration
}

// VideoProbe obtains validated metadata from a temporary immutable video file.
type VideoProbe interface {
	Probe(context.Context, string) (VideoMetadata, error)
}

// FFprobe is the production VideoProbe backed by one startup-resolved executable.
type FFprobe struct {
	executable string
}

// NewFFprobe resolves the configured executable during startup so video work is
// not accepted when its validation dependency is absent.
func NewFFprobe(executable string) (FFprobe, error) {
	executable = strings.TrimSpace(executable)
	if executable == "" {
		return FFprobe{}, fmt.Errorf("%w: executable is blank", ErrVideoProbeUnavailable)
	}
	resolved, err := exec.LookPath(executable)
	if err != nil {
		return FFprobe{}, fmt.Errorf("%w: %v", ErrVideoProbeUnavailable, err)
	}
	return FFprobe{executable: resolved}, nil
}

// Probe runs ffprobe with bounded output and parses the first video stream's
// codec together with the container and duration.
func (probe FFprobe) Probe(ctx context.Context, path string) (VideoMetadata, error) {
	executable := probe.executable
	if executable == "" {
		// Preserve the zero-value helper for focused package tests; production startup always uses
		// NewFFprobe and therefore fails closed before opening runtime dependencies.
		executable = "ffprobe"
	}
	command := exec.CommandContext(ctx, executable, "-v", "error", "-select_streams", "v:0",
		"-show_entries", "stream=codec_name", "-show_entries", "format=format_name,duration",
		"-of", "json", path)
	var output boundedProbeBuffer
	command.Stdout = &output
	command.Stderr = &boundedDiagnosticBuffer{}
	if err := command.Run(); err != nil {
		var executableError *exec.Error
		if errors.As(err, &executableError) {
			return VideoMetadata{}, fmt.Errorf("%w: %v", ErrVideoProbeUnavailable, err)
		}
		return VideoMetadata{}, fmt.Errorf("ffprobe failed")
	}
	var result struct {
		Streams []struct {
			Codec string `json:"codec_name"`
		} `json:"streams"`
		Format struct {
			Name     string `json:"format_name"`
			Duration string `json:"duration"`
		} `json:"format"`
	}
	// ffprobe adds version-dependent top-level sections such as an empty `programs` array even
	// when show_entries requests only streams and format. The output is already byte-bounded and
	// only the explicitly decoded fields become validation input, so compatible extra sections
	// must not make every otherwise valid video fail processing.
	decoder := json.NewDecoder(bytes.NewReader(output.Bytes()))
	if err := decoder.Decode(&result); err != nil || len(result.Streams) != 1 {
		return VideoMetadata{}, fmt.Errorf("ffprobe output is invalid")
	}
	seconds, err := strconv.ParseFloat(result.Format.Duration, 64)
	if err != nil || seconds <= 0 {
		return VideoMetadata{}, fmt.Errorf("ffprobe duration is invalid")
	}
	return VideoMetadata{
		Codec: strings.ToLower(result.Streams[0].Codec), Container: strings.ToLower(result.Format.Name),
		Duration: time.Duration(seconds * float64(time.Second)),
	}, nil
}

type boundedProbeBuffer struct{ bytes.Buffer }

// Write implements io.Writer while rejecting ffprobe output above the diagnostic bound.
func (buffer *boundedProbeBuffer) Write(value []byte) (int, error) {
	const maximum = 64 << 10
	if buffer.Len()+len(value) > maximum {
		return 0, fmt.Errorf("ffprobe output exceeds limit")
	}
	return buffer.Buffer.Write(value)
}

type boundedDiagnosticBuffer struct{ bytes.Buffer }

// Write implements io.Writer while retaining only the bounded diagnostic prefix.
func (buffer *boundedDiagnosticBuffer) Write(value []byte) (int, error) {
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
