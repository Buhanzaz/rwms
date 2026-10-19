package storage

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestVersionPinnedStorageAndPrivateIngressIntegration(t *testing.T) {
	endpoint := os.Getenv("MEDIA_TEST_MINIO_ENDPOINT")
	if endpoint == "" {
		t.Skip("MEDIA_TEST_MINIO_ENDPOINT is not configured")
	}
	accessKey := os.Getenv("MEDIA_TEST_MINIO_ACCESS_KEY")
	secretKey := os.Getenv("MEDIA_TEST_MINIO_SECRET_KEY")
	bucket := testsupport.NewVersionedMinIOBucket(t, endpoint, accessKey, secretKey, false)
	store, err := NewMinIOObjectStore(MinIOOptions{
		Endpoint: endpoint, AccessKey: accessKey,
		SecretKey: secretKey,
		Bucket:    bucket,
	})
	if err != nil {
		t.Fatalf("NewMinIOObjectStore() error = %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := store.EnsureVersioning(ctx); err != nil {
		t.Fatalf("EnsureVersioning() error = %v", err)
	}

	key := "integration/" + uuid.NewString() + "/versioned.txt"
	firstBody := []byte("first immutable body")
	first, err := store.PutVersion(ctx, key, bytes.NewReader(firstBody), int64(len(firstBody)), "text/plain")
	if err != nil || first.VersionID == "" {
		t.Fatalf("first PutVersion() = %#v, %v", first, err)
	}
	secondBody := []byte("second immutable body")
	second, err := store.PutVersion(ctx, key, bytes.NewReader(secondBody), int64(len(secondBody)), "text/plain")
	if err != nil || second.VersionID == "" || second.VersionID == first.VersionID {
		t.Fatalf("second PutVersion() = %#v, %v; first version %q", second, err, first.VersionID)
	}

	object, metadata, err := store.GetVersion(ctx, key, first.VersionID)
	if err != nil {
		t.Fatalf("GetVersion(first) error = %v", err)
	}
	defer object.Close()
	got, err := io.ReadAll(object)
	if err != nil || !bytes.Equal(got, firstBody) || metadata.VersionID != first.VersionID {
		t.Fatalf("pinned first body = %q, metadata=%#v, error=%v", got, metadata, err)
	}
	stat, err := store.StatVersion(ctx, key, first.VersionID)
	if err != nil || stat.VersionID != first.VersionID || stat.SizeBytes != int64(len(firstBody)) {
		t.Fatalf("StatVersion(first) = %#v, %v", stat, err)
	}
	ingressBody := []byte("private same-origin ingress body")
	ingressSum := sha256.Sum256(ingressBody)
	checksum := hex.EncodeToString(ingressSum[:])
	ingressKey := "integration/" + uuid.NewString() + "/upload.jpg"
	ingress, err := store.PutIngressVersion(ctx, ingressKey, bytes.NewReader(ingressBody),
		int64(len(ingressBody)), "image/jpeg", checksum)
	if err != nil {
		t.Fatalf("PutIngressVersion() error = %v", err)
	}
	if ingress.VersionID == "" || ingress.ETag == "" || ingress.SizeBytes != int64(len(ingressBody)) {
		t.Fatalf("PutIngressVersion() metadata = %#v", ingress)
	}
	ingressStat, err := store.StatVersion(ctx, ingressKey, ingress.VersionID)
	if err != nil || ingressStat.VersionID != ingress.VersionID ||
		ingressStat.UserMetadata["sha256"] != checksum {
		t.Fatalf("StatVersion(ingress) = %#v, %v", ingressStat, err)
	}
}
