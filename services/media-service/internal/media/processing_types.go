package media

import (
	"context"
	"fmt"
	"io"
)

// ObjectStore is the version-pinned private object boundary required by video
// processing. Still-image variants are produced by Android clients and never
// pass through a Go decoder.
type ObjectStore interface {
	GetVersion(context.Context, string, string) (io.ReadCloser, ObjectMetadata, error)
	PutVersion(context.Context, string, io.Reader, int64, string) (ObjectMetadata, error)
}

// ObjectMetadata is immutable storage metadata verified at byte ingress and
// persisted with one media generation.
type ObjectMetadata struct {
	SizeBytes    int64
	ContentType  string
	ETag         string
	VersionID    string
	UserMetadata map[string]string
}

// ProcessedVariant describes one version-pinned logical output without
// carrying media bytes in the worker or persistence boundary.
type ProcessedVariant struct {
	Variant         Variant
	ObjectKey       string
	ContentType     string
	SizeBytes       int64
	Width           int
	Height          int
	ChecksumSHA256  string
	ObjectVersionID string
}

func getObject(ctx context.Context, store ObjectStore, key, versionID string) (io.ReadCloser, ObjectMetadata, error) {
	if versionID == "" {
		return nil, ObjectMetadata{}, fmt.Errorf("immutable object version is required")
	}
	return store.GetVersion(ctx, key, versionID)
}

func putObject(
	ctx context.Context,
	store ObjectStore,
	key string,
	source io.Reader,
	size int64,
	contentType string,
) (ObjectMetadata, error) {
	return store.PutVersion(ctx, key, source, size, contentType)
}

func newProcessedVariant(
	variant Variant,
	objectKey, contentType string,
	sizeBytes int64,
	width, height int,
	checksumSHA256 string,
) ProcessedVariant {
	return ProcessedVariant{
		Variant: variant, ObjectKey: objectKey, ContentType: contentType,
		SizeBytes: sizeBytes, Width: width, Height: height, ChecksumSHA256: checksumSHA256,
	}
}
