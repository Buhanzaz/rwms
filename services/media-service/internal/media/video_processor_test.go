package media

import (
	"bytes"
	"context"
	"io"
	"reflect"
	"testing"
	"time"
)

type memoryObjectStore struct {
	objects      map[string][]byte
	contentTypes map[string]string
}

func newMemoryObjectStore(objects map[string][]byte) *memoryObjectStore {
	return &memoryObjectStore{
		objects:      objects,
		contentTypes: map[string]string{},
	}
}

func (store *memoryObjectStore) Get(_ context.Context, key string) (io.ReadCloser, ObjectMetadata, error) {
	data, ok := store.objects[key]
	if !ok {
		return nil, ObjectMetadata{}, errMissingObject(key)
	}
	return io.NopCloser(bytes.NewReader(data)), ObjectMetadata{SizeBytes: int64(len(data))}, nil
}

func (store *memoryObjectStore) GetVersion(ctx context.Context, key, versionID string) (io.ReadCloser, ObjectMetadata, error) {
	if versionID == "" {
		return nil, ObjectMetadata{}, errMissingObject(key)
	}
	object, metadata, err := store.Get(ctx, key)
	metadata.VersionID = versionID
	return object, metadata, err
}

func (store *memoryObjectStore) Put(_ context.Context, key string, source io.Reader, size int64, contentType string) error {
	data, err := io.ReadAll(source)
	if err != nil {
		return err
	}
	if int64(len(data)) != size {
		return errUnexpectedSize(key, size, int64(len(data)))
	}
	store.objects[key] = data
	store.contentTypes[key] = contentType
	return nil
}

func (store *memoryObjectStore) PutVersion(ctx context.Context, key string, source io.Reader, size int64, contentType string) (ObjectMetadata, error) {
	if err := store.Put(ctx, key, source, size, contentType); err != nil {
		return ObjectMetadata{}, err
	}
	return ObjectMetadata{SizeBytes: size, ContentType: contentType, VersionID: "version-" + key}, nil
}

type unusedCommandRunner struct {
	called bool
}

type acceptedVideoProbe struct{}

func (acceptedVideoProbe) Probe(context.Context, string) (VideoMetadata, error) {
	return VideoMetadata{Codec: "h264", Container: "mp4", Duration: time.Second}, nil
}

func testVideoProcessor(store ObjectStore, runner CommandRunner) VideoProcessor {
	return VideoProcessor{Store: store, Runner: runner, Probe: acceptedVideoProbe{},
		AllowedCodecs: map[string]struct{}{"h264": {}}, MaxDuration: time.Minute,
		Limits: testProcessingLimits()}
}

func (runner *unusedCommandRunner) Run(context.Context, string, ...string) error {
	runner.called = true
	return nil
}

func TestVideoProcessorCopiesOnlyTheOriginalWithoutRunningFFmpeg(t *testing.T) {
	store := newMemoryObjectStore(map[string][]byte{
		"media/m-1/source/upload.mp4": []byte("video bytes"),
	})
	runner := &unusedCommandRunner{}
	result, err := testVideoProcessor(store, runner).Process(context.Background(), VideoProcessRequest{
		MediaID:         "m-1",
		SourceObjectKey: "media/m-1/source/upload.mp4",
		SourceVersionID: "version-1",
		ContentType:     "Video/MP4; charset=binary",
		Generation:      1,
		Rotation:        Rotation0,
	})
	if err != nil {
		t.Fatalf("Process() error = %v", err)
	}
	if runner.called {
		t.Fatal("ffmpeg runner was called for an unrotated original")
	}
	if got, want := result.Original.ObjectKey, "media/m-1/generations/1/original.mp4"; got != want {
		t.Fatalf("original key = %q, want %q", got, want)
	}
	if got, want := result.Original.ContentType, "video/mp4"; got != want {
		t.Fatalf("content type = %q, want %q", got, want)
	}
	if got, want := string(store.objects[result.Original.ObjectKey]), "video bytes"; got != want {
		t.Fatalf("stored data = %q, want %q", got, want)
	}
	if result.Original.ChecksumSHA256 == "" {
		t.Fatal("checksum was not recorded")
	}
}

func TestVideoProcessorRejectsAnUnsupportedRotation(t *testing.T) {
	store := newMemoryObjectStore(map[string][]byte{})
	_, err := testVideoProcessor(store, &unusedCommandRunner{}).Process(context.Background(), VideoProcessRequest{
		MediaID:         "m-1",
		SourceObjectKey: "source",
		SourceVersionID: "version-1",
		ContentType:     "video/mp4",
		Rotation:        Rotation(45),
	})
	if err == nil {
		t.Fatal("Process() error = nil, want unsupported rotation error")
	}
}

func TestVideoRotationArgumentsUseTheNativeContainerAndCodec(t *testing.T) {
	tests := []struct {
		name      string
		extension string
		rotation  Rotation
		want      []string
	}{
		{
			name:      "mp4 right",
			extension: ".mp4",
			rotation:  Rotation90,
			want:      []string{"-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", "in.mp4", "-vf", "transpose=1", "-c:v", "libx264", "-c:a", "copy", "-movflags", "+faststart", "-fs", "1048576", "out.mp4"},
		},
		{
			name:      "webm left",
			extension: ".webm",
			rotation:  Rotation270,
			want:      []string{"-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", "in.webm", "-vf", "transpose=2", "-c:v", "libvpx-vp9", "-c:a", "libopus", "-fs", "1048576", "out.webm"},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := videoRotationArguments("in"+test.extension, "out"+test.extension, test.extension, test.rotation, 1048576)
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("arguments = %#v, want %#v", got, test.want)
			}
		})
	}
}

func testProcessingLimits() ProcessingLimits {
	return ProcessingLimits{
		MaxImageBytes: 1 << 20, MaxImageOutputBytes: 1 << 20,
		MaxDecodedPixels: 1_000_000, MaxVideoBytes: 1 << 20,
		MaxVideoOutputBytes: 1 << 20, Timeout: time.Second,
	}
}

type missingObjectError string

func (err missingObjectError) Error() string {
	return "missing object: " + string(err)
}

func errMissingObject(key string) error {
	return missingObjectError(key)
}

type unexpectedSizeError struct {
	key  string
	want int64
	got  int64
}

func (err unexpectedSizeError) Error() string {
	return "unexpected size for " + err.key
}

func errUnexpectedSize(key string, want, got int64) error {
	return unexpectedSizeError{key: key, want: want, got: got}
}
