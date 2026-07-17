package storage

import (
	"bytes"
	"context"
	"encoding/base64"
	"io"
	"os"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestVersionPinnedStorageAndConstrainedPolicyIntegration(t *testing.T) {
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
	signed, err := store.SignedVersionDownloadURL(ctx, key, first.VersionID, time.Minute)
	if err != nil || signed.Query().Get("versionId") != first.VersionID {
		t.Fatalf("SignedVersionDownloadURL() = %v, %v", signed, err)
	}

	checksum := strings.Repeat("a", 64)
	policy, err := store.SignedUploadPolicy(ctx, "integration/"+uuid.NewString()+"/upload.jpg", "image/jpeg", checksum, 128, time.Minute)
	if err != nil {
		t.Fatalf("SignedUploadPolicy() error = %v", err)
	}
	if policy.Fields["key"] == "" || policy.Fields["Content-Type"] != "image/jpeg" ||
		policy.Fields["x-amz-meta-sha256"] != checksum {
		t.Fatalf("constrained POST fields = %#v", policy.Fields)
	}
	encodedPolicy := policy.Fields["policy"]
	decodedPolicy, err := base64.StdEncoding.DecodeString(encodedPolicy)
	if err != nil {
		t.Fatalf("decode POST policy: %v", err)
	}
	policyText := strings.ReplaceAll(string(decodedPolicy), " ", "")
	if !strings.Contains(policyText, `["content-length-range",128,128]`) ||
		!strings.Contains(policyText, `["eq","$x-amz-meta-sha256","`+checksum+`"]`) {
		t.Fatalf("POST policy is not exact-length/checksum constrained: %s", policyText)
	}
}
