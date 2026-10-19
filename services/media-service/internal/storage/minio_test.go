package storage

import "testing"

func TestNewMinIOObjectStoreRejectsMissingConfiguration(t *testing.T) {
	_, err := NewMinIOObjectStore(MinIOOptions{})
	if err == nil {
		t.Fatal("NewMinIOObjectStore() error = nil, want configuration error")
	}
}

func TestNewMinIOObjectStoreAcceptsServerOnlyConfiguration(t *testing.T) {
	store, err := NewMinIOObjectStore(MinIOOptions{
		Endpoint:  "minio.local:9000",
		AccessKey: "media-service",
		SecretKey: "not-a-real-secret",
		Bucket:    "rwms-media",
	})
	if err != nil {
		t.Fatalf("NewMinIOObjectStore() error = %v", err)
	}
	if store == nil || store.bucket != "rwms-media" {
		t.Fatalf("store = %#v, want configured store", store)
	}
}
