package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

var (
	// ErrObjectVersionMismatch identifies a caller-requested immutable object
	// version which does not exist or was not the version returned by storage.
	ErrObjectVersionMismatch = errors.New("immutable object version mismatch")
	// ErrDependency identifies a storage transport or server failure.
	ErrDependency = errors.New("storage dependency unavailable")
)

// MinIOOptions contains server-only storage credentials. They must never be
// serialized into an API response; all public object traffic is streamed by
// the authenticated same-origin media API.
type MinIOOptions struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	Bucket    string
	UseSSL    bool
}

type MinIOObjectStore struct {
	client *minio.Client
	bucket string
}

func NewMinIOObjectStore(options MinIOOptions) (*MinIOObjectStore, error) {
	if strings.TrimSpace(options.Endpoint) == "" {
		return nil, fmt.Errorf("MinIO endpoint is required")
	}
	if strings.TrimSpace(options.AccessKey) == "" || strings.TrimSpace(options.SecretKey) == "" {
		return nil, fmt.Errorf("MinIO credentials are required")
	}
	if strings.TrimSpace(options.Bucket) == "" {
		return nil, fmt.Errorf("MinIO bucket is required")
	}

	client, err := minio.New(options.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(options.AccessKey, options.SecretKey, ""),
		Secure: options.UseSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("create MinIO client: %w", err)
	}
	return &MinIOObjectStore{client: client, bucket: options.Bucket}, nil
}

func (store *MinIOObjectStore) GetVersion(ctx context.Context, key, versionID string) (io.ReadCloser, media.ObjectMetadata, error) {
	if strings.TrimSpace(versionID) == "" {
		return nil, media.ObjectMetadata{}, fmt.Errorf("object version ID is required")
	}
	options := minio.GetObjectOptions{VersionID: versionID}
	object, err := store.client.GetObject(ctx, store.bucket, key, options)
	if err != nil {
		return nil, media.ObjectMetadata{}, classifyVersionError("get", key, versionID, err)
	}
	info, err := object.Stat()
	if err != nil {
		_ = object.Close()
		return nil, media.ObjectMetadata{}, classifyVersionError("stat", key, versionID, err)
	}
	if info.VersionID != versionID {
		_ = object.Close()
		return nil, media.ObjectMetadata{}, fmt.Errorf("%w: MinIO returned an unexpected object version for %q", ErrObjectVersionMismatch, key)
	}
	return object, metadata(info), nil
}

func (store *MinIOObjectStore) StatVersion(ctx context.Context, key, versionID string) (media.ObjectMetadata, error) {
	if strings.TrimSpace(versionID) == "" {
		return media.ObjectMetadata{}, fmt.Errorf("object version ID is required")
	}
	info, err := store.client.StatObject(ctx, store.bucket, key, minio.StatObjectOptions{VersionID: versionID})
	if err != nil {
		return media.ObjectMetadata{}, classifyVersionError("stat", key, versionID, err)
	}
	if info.VersionID != versionID {
		return media.ObjectMetadata{}, fmt.Errorf("%w: MinIO returned an unexpected object version for %q", ErrObjectVersionMismatch, key)
	}
	return metadata(info), nil
}

func classifyVersionError(operation, key, versionID string, err error) error {
	response := minio.ToErrorResponse(err)
	if response.StatusCode == http.StatusNotFound || response.Code == "NoSuchKey" ||
		response.Code == "NoSuchObject" || response.Code == "NoSuchVersion" {
		return fmt.Errorf("%w: %s pinned MinIO object %q version %q: %v",
			ErrObjectVersionMismatch, operation, key, versionID, err)
	}
	return fmt.Errorf("%w: %s pinned MinIO object %q version %q: %v",
		ErrDependency, operation, key, versionID, err)
}

func (store *MinIOObjectStore) PutVersion(ctx context.Context, key string, source io.Reader, sizeBytes int64, contentType string) (media.ObjectMetadata, error) {
	if sizeBytes < 0 {
		return media.ObjectMetadata{}, fmt.Errorf("object %q requires a known content length", key)
	}
	info, err := store.client.PutObject(ctx, store.bucket, key, source, sizeBytes, minio.PutObjectOptions{
		ContentType: contentType,
	})
	if err != nil {
		return media.ObjectMetadata{}, fmt.Errorf("put MinIO object %q: %w", key, err)
	}
	if info.VersionID == "" {
		return media.ObjectMetadata{}, fmt.Errorf("MinIO did not return an immutable object version")
	}
	return media.ObjectMetadata{SizeBytes: info.Size, ContentType: contentType, ETag: strings.Trim(info.ETag, "\""), VersionID: info.VersionID}, nil
}

// PutIngressVersion streams one browser upload to the private versioned bucket.
// The checksum is stored as immutable object metadata and is verified again by
// the API before the upload session is finalized.
func (store *MinIOObjectStore) PutIngressVersion(
	ctx context.Context,
	key string,
	source io.Reader,
	sizeBytes int64,
	contentType, checksumSHA256 string,
) (media.ObjectMetadata, error) {
	if sizeBytes <= 0 || strings.TrimSpace(key) == "" || strings.TrimSpace(contentType) == "" {
		return media.ObjectMetadata{}, fmt.Errorf("ingress object metadata is required")
	}
	if len(checksumSHA256) != 64 {
		return media.ObjectMetadata{}, fmt.Errorf("ingress checksum must be lowercase SHA-256")
	}
	for _, character := range checksumSHA256 {
		if !(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f') {
			return media.ObjectMetadata{}, fmt.Errorf("ingress checksum must be lowercase SHA-256")
		}
	}
	info, err := store.client.PutObject(ctx, store.bucket, key, source, sizeBytes, minio.PutObjectOptions{
		ContentType: contentType,
		UserMetadata: map[string]string{
			"sha256": checksumSHA256,
		},
	})
	if err != nil {
		return media.ObjectMetadata{}, fmt.Errorf("put MinIO ingress object %q: %w", key, err)
	}
	if info.VersionID == "" || strings.TrimSpace(info.ETag) == "" {
		return media.ObjectMetadata{}, fmt.Errorf("MinIO did not return immutable ingress metadata")
	}
	return media.ObjectMetadata{
		SizeBytes:   info.Size,
		ContentType: contentType,
		ETag:        strings.Trim(info.ETag, "\""),
		VersionID:   info.VersionID,
	}, nil
}

func (store *MinIOObjectStore) EnsureVersioning(ctx context.Context) error {
	configuration, err := store.client.GetBucketVersioning(ctx, store.bucket)
	if err != nil {
		return fmt.Errorf("read MinIO bucket versioning: %w", err)
	}
	if configuration.Status != "Enabled" {
		return fmt.Errorf("MinIO bucket versioning must be Enabled")
	}
	return nil
}

func metadata(info minio.ObjectInfo) media.ObjectMetadata {
	userMetadata := make(map[string]string, len(info.UserMetadata))
	for key, value := range info.UserMetadata {
		userMetadata[strings.ToLower(key)] = value
	}
	return media.ObjectMetadata{
		SizeBytes:    info.Size,
		ContentType:  strings.ToLower(strings.TrimSpace(strings.Split(info.ContentType, ";")[0])),
		ETag:         strings.Trim(info.ETag, "\""),
		VersionID:    info.VersionID,
		UserMetadata: userMetadata,
	}
}
