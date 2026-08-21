package media

import (
	"bytes"
	"context"
	"io"
	"os"
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

type acceptedVideoProbe struct{}

func (acceptedVideoProbe) Probe(context.Context, string) (VideoMetadata, error) {
	return VideoMetadata{Codec: "h264", Container: "mp4", Duration: time.Second}, nil
}

// copyingVideoTranscoder is a deterministic processor test double that proves
// playback bytes come from the injected transformation boundary.
type copyingVideoTranscoder struct {
	bytes []byte
}

func (transcoder copyingVideoTranscoder) Transcode(_ context.Context, request VideoTranscodeRequest) error {
	return os.WriteFile(request.DestinationPath, transcoder.bytes, 0o600)
}

func testVideoProcessor(store ObjectStore) VideoProcessor {
	return VideoProcessor{Store: store, Probe: acceptedVideoProbe{}, Transcoder: copyingVideoTranscoder{bytes: []byte("compressed playback")},
		AllowedCodecs: map[string]struct{}{"h264": {}}, MaxDuration: time.Minute,
		MaxOutputBytes: 1 << 20, Limits: testProcessingLimits()}
}

func TestVideoProcessorPreservesOriginalAndWritesCompressedPlayback(t *testing.T) {
	store := newMemoryObjectStore(map[string][]byte{
		"media/m-1/source/upload.mp4": []byte("video bytes"),
	})
	result, err := testVideoProcessor(store).Process(context.Background(), VideoProcessRequest{
		MediaID:         "m-1",
		SourceObjectKey: "media/m-1/source/upload.mp4",
		SourceVersionID: "version-1",
		ContentType:     "Video/MP4; charset=binary",
		Generation:      1,
	})
	if err != nil {
		t.Fatalf("Process() error = %v", err)
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
	if got, want := result.Playback.ObjectKey, "media/m-1/generations/1/playback.mp4"; got != want {
		t.Fatalf("playback key = %q, want %q", got, want)
	}
	if got, want := result.Playback.ContentType, "video/mp4"; got != want {
		t.Fatalf("playback content type = %q, want %q", got, want)
	}
	if got, want := string(store.objects[result.Playback.ObjectKey]), "compressed playback"; got != want {
		t.Fatalf("stored playback = %q, want %q", got, want)
	}
	if result.Playback.ChecksumSHA256 == "" || result.Playback.ChecksumSHA256 == result.Original.ChecksumSHA256 {
		t.Fatal("independent playback checksum was not recorded")
	}
}

func TestVideoProcessorRejectsPlaybackBeyondConfiguredOutputLimitBeforeObjectWrites(t *testing.T) {
	store := newMemoryObjectStore(map[string][]byte{
		"media/m-1/source/upload.mp4": []byte("video bytes"),
	})
	processor := testVideoProcessor(store)
	processor.MaxOutputBytes = 4
	_, err := processor.Process(context.Background(), VideoProcessRequest{
		MediaID: "m-1", SourceObjectKey: "media/m-1/source/upload.mp4",
		SourceVersionID: "version-1", ContentType: "video/mp4", Generation: 1,
	})
	if err == nil {
		t.Fatal("Process() error = nil, want bounded playback rejection")
	}
	if _, exists := store.objects[OriginalObjectKey("m-1", 1, ".mp4")]; exists {
		t.Fatal("original generation object was written before playback validation")
	}
	if _, exists := store.objects[VideoPlaybackObjectKey("m-1", 1)]; exists {
		t.Fatal("oversized playback generation object was written")
	}
}

func testProcessingLimits() ProcessingLimits {
	return ProcessingLimits{
		MaxVideoBytes: 1 << 20, Timeout: time.Second,
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
