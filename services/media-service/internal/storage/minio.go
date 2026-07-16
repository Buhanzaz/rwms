package storage

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// MinIOOptions contains server-only storage credentials. They must never be
// serialized into an API response; callers receive signed URLs instead.
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

func (store *MinIOObjectStore) Get(ctx context.Context, key string) (io.ReadCloser, media.ObjectMetadata, error) {
	object, err := store.client.GetObject(ctx, store.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, media.ObjectMetadata{}, fmt.Errorf("get MinIO object %q: %w", key, err)
	}
	info, err := object.Stat()
	if err != nil {
		_ = object.Close()
		return nil, media.ObjectMetadata{}, fmt.Errorf("stat MinIO object %q: %w", key, err)
	}
	return object, media.ObjectMetadata{SizeBytes: info.Size}, nil
}

func (store *MinIOObjectStore) Put(ctx context.Context, key string, source io.Reader, sizeBytes int64, contentType string) error {
	if sizeBytes < 0 {
		return fmt.Errorf("object %q requires a known content length", key)
	}
	_, err := store.client.PutObject(ctx, store.bucket, key, source, sizeBytes, minio.PutObjectOptions{
		ContentType: contentType,
	})
	if err != nil {
		return fmt.Errorf("put MinIO object %q: %w", key, err)
	}
	return nil
}

func (store *MinIOObjectStore) SignedUploadURL(ctx context.Context, key string, expiresIn time.Duration) (*url.URL, error) {
	if expiresIn <= 0 {
		return nil, fmt.Errorf("upload URL expiry must be positive")
	}
	signedURL, err := store.client.PresignedPutObject(ctx, store.bucket, key, expiresIn)
	if err != nil {
		return nil, fmt.Errorf("sign upload URL for %q: %w", key, err)
	}
	return signedURL, nil
}

func (store *MinIOObjectStore) SignedDownloadURL(ctx context.Context, key string, expiresIn time.Duration) (*url.URL, error) {
	if expiresIn <= 0 {
		return nil, fmt.Errorf("download URL expiry must be positive")
	}
	signedURL, err := store.client.PresignedGetObject(ctx, store.bucket, key, expiresIn, nil)
	if err != nil {
		return nil, fmt.Errorf("sign download URL for %q: %w", key, err)
	}
	return signedURL, nil
}
