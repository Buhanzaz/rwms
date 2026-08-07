package config

import "testing"

func TestLoadRequiresExplicitResourceAndUploadLimits(t *testing.T) {
	t.Setenv("MEDIA_RUNTIME_PROFILE", "production")
	t.Setenv("MEDIA_DATABASE_URL", "postgres://media:secret@localhost/media")
	t.Setenv("MEDIA_AUTH_ISSUER", "https://auth.example/auth")
	t.Setenv("MEDIA_AUTH_JWKS_URL", "https://auth.example/jwks")
	t.Setenv("MEDIA_MINIO_ENDPOINT", "minio:9000")
	t.Setenv("MEDIA_MINIO_ACCESS_KEY", "media")
	t.Setenv("MEDIA_MINIO_SECRET_KEY", "secret")
	t.Setenv("MEDIA_MINIO_BUCKET", "rwms-media")
	t.Setenv("MEDIA_MINIO_USE_SSL", "false")
	t.Setenv("MEDIA_KAFKA_BROKERS", "kafka:29092")
	t.Setenv("MEDIA_INSTANCE_ID", "test-1")

	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want missing explicit limit failure")
	}
}

func TestLoadAcceptsCompleteFailClosedConfiguration(t *testing.T) {
	for name, value := range map[string]string{
		"MEDIA_RUNTIME_PROFILE":        "production",
		"MEDIA_DATABASE_URL":           "postgres://media:secret@localhost/media",
		"MEDIA_AUTH_ISSUER":            "https://auth.example/auth",
		"MEDIA_AUTH_JWKS_URL":          "https://auth.example/jwks",
		"MEDIA_MINIO_ENDPOINT":         "minio:9000",
		"MEDIA_MINIO_ACCESS_KEY":       "media",
		"MEDIA_MINIO_SECRET_KEY":       "secret",
		"MEDIA_MINIO_BUCKET":           "rwms-media",
		"MEDIA_MINIO_USE_SSL":          "true",
		"MEDIA_MAX_UPLOAD_BYTES":       "1048576",
		"MEDIA_MAX_DECODED_PIXELS":     "1000000",
		"MEDIA_MAX_IMAGE_OUTPUT_BYTES": "1048576",
		"MEDIA_MAX_VIDEO_DURATION":     "2m",
		"MEDIA_ALLOWED_VIDEO_CODECS":   "h264",
		"MEDIA_UPLOAD_EXPIRY":          "5m",
		"MEDIA_PROCESSING_TIMEOUT":     "30s",
		"MEDIA_ALLOWED_MIME_TYPES":     "image/jpeg,video/mp4",
		"MEDIA_KAFKA_BROKERS":          "kafka:29092",
		"MEDIA_INSTANCE_ID":            "test-1",
	} {
		t.Setenv(name, value)
	}
	configuration, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if configuration.MaxUploadBytes != 1048576 || len(configuration.AllowedMIMETypes) != 2 {
		t.Fatalf("unexpected configuration: %#v", configuration)
	}
	if configuration.InventoryTopic != "rwms.inventory.session.v1" ||
		configuration.InventoryOwnerGroup != "media-service-inventory-owner-v1" ||
		configuration.InventoryOwnerDLT != "rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt" {
		t.Fatalf("inventory owner Kafka configuration = %#v", configuration)
	}
	if configuration.AssetRentalItemTopic != "rwms.asset.rental-item.v1" ||
		configuration.CabinOwnerGroup != "media-service-cabin-owner-v1" {
		t.Fatalf("cabin owner Kafka configuration = %#v", configuration)
	}
	if configuration.TaskBoardEntryOwnerProofTopic != "rwms.task-board.entry-owner-proof.v1" ||
		configuration.TaskBoardEntryOwnerProofGroup != "media-service-task-board-entry-owner-proof-v1" {
		t.Fatalf("task-board owner proof Kafka configuration = %#v", configuration)
	}
}

func TestLoadRejectsNonCanonicalInventoryOwnerKafkaConfiguration(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_KAFKA_INVENTORY_OWNER_GROUP", "shared-consumer")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want non-canonical inventory owner group rejection")
	}
}

func TestLoadRejectsNonCanonicalCabinOwnerKafkaConfiguration(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_KAFKA_CABIN_OWNER_GROUP", "shared-consumer")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want non-canonical cabin owner group rejection")
	}
}

func TestLoadRejectsNonCanonicalTaskBoardOwnerProofKafkaConfiguration(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_KAFKA_TASK_BOARD_ENTRY_OWNER_PROOF_GROUP", "shared-consumer")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want non-canonical task-board owner proof group rejection")
	}
}

func TestProductionRejectsInsecureIssuerOrStorage(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_RUNTIME_PROFILE", "production")
	t.Setenv("MEDIA_AUTH_ISSUER", "http://auth.example/auth")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want insecure issuer rejection")
	}

	setCompleteConfiguration(t)
	t.Setenv("MEDIA_RUNTIME_PROFILE", "production")
	t.Setenv("MEDIA_MINIO_USE_SSL", "false")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want insecure MinIO rejection")
	}
}

func TestLocalTestProfileAllowsExplicitInsecureDependencies(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_RUNTIME_PROFILE", "local-test")
	t.Setenv("MEDIA_AUTH_ISSUER", "http://auth.test/auth")
	t.Setenv("MEDIA_AUTH_JWKS_URL", "http://auth.test/jwks")
	t.Setenv("MEDIA_MINIO_USE_SSL", "false")
	if _, err := Load(); err != nil {
		t.Fatalf("Load() error = %v", err)
	}
}

func setCompleteConfiguration(t *testing.T) {
	t.Helper()
	for name, value := range map[string]string{
		"MEDIA_DATABASE_URL":           "postgres://media:secret@localhost/media",
		"MEDIA_AUTH_ISSUER":            "https://auth.example/auth",
		"MEDIA_AUTH_JWKS_URL":          "https://auth.example/jwks",
		"MEDIA_MINIO_ENDPOINT":         "minio:9000",
		"MEDIA_MINIO_ACCESS_KEY":       "media",
		"MEDIA_MINIO_SECRET_KEY":       "secret",
		"MEDIA_MINIO_BUCKET":           "rwms-media",
		"MEDIA_MINIO_USE_SSL":          "true",
		"MEDIA_MAX_UPLOAD_BYTES":       "1048576",
		"MEDIA_MAX_DECODED_PIXELS":     "1000000",
		"MEDIA_MAX_IMAGE_OUTPUT_BYTES": "1048576",
		"MEDIA_MAX_VIDEO_DURATION":     "2m",
		"MEDIA_ALLOWED_VIDEO_CODECS":   "h264",
		"MEDIA_UPLOAD_EXPIRY":          "5m",
		"MEDIA_PROCESSING_TIMEOUT":     "30s",
		"MEDIA_ALLOWED_MIME_TYPES":     "image/jpeg,video/mp4",
		"MEDIA_KAFKA_BROKERS":          "kafka:29092",
		"MEDIA_INSTANCE_ID":            "test-1",
	} {
		t.Setenv(name, value)
	}
}
