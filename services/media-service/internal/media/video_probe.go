package media

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

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

// FFprobe is the production VideoProbe backed by the ffprobe executable.
type FFprobe struct{}

// Probe runs ffprobe with bounded output and parses the first video stream's
// codec together with the container and duration.
func (FFprobe) Probe(ctx context.Context, path string) (VideoMetadata, error) {
	command := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-select_streams", "v:0",
		"-show_entries", "stream=codec_name", "-show_entries", "format=format_name,duration",
		"-of", "json", path)
	var output boundedProbeBuffer
	command.Stdout = &output
	command.Stderr = &boundedDiagnosticBuffer{}
	if err := command.Run(); err != nil {
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
	decoder := json.NewDecoder(bytes.NewReader(output.Bytes()))
	decoder.DisallowUnknownFields()
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
