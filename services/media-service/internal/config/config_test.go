package config

import (
	"strings"
	"testing"
	"time"
)

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
		"MEDIA_MAX_VIDEO_DURATION":     "2m",
		"MEDIA_MAX_VIDEO_OUTPUT_BYTES": "524288",
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
	if configuration.MaxUploadBytes != 1048576 || configuration.MaxVideoOutputBytes != 524288 ||
		configuration.FFmpegExecutable != "ffmpeg" || configuration.FFprobeExecutable != "ffprobe" ||
		len(configuration.AllowedMIMETypes) != 2 {
		t.Fatalf("unexpected configuration: %#v", configuration)
	}
	if configuration.ManagementAddress != "127.0.0.1:9095" {
		t.Fatalf("management address = %q, want default loopback", configuration.ManagementAddress)
	}
	if configuration.HTTPReadTimeout != 5*time.Minute ||
		configuration.HTTPWriteTimeout != 5*time.Minute {
		t.Fatalf("HTTP timeouts = %s/%s, want 5m/5m",
			configuration.HTTPReadTimeout, configuration.HTTPWriteTimeout)
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
	if configuration.DriverShiftOwnerProofTopic != "rwms.task-board.driver-shift-owner-proof.v1" ||
		configuration.DriverShiftOwnerProofGroup != "media-service-driver-shift-owner-proof-v1" {
		t.Fatalf("driver-shift owner proof Kafka configuration = %#v", configuration)
	}
}

func TestLoadRejectsNonPositiveHTTPTransferTimeouts(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_HTTP_READ_TIMEOUT", "0s")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "MEDIA_HTTP_READ_TIMEOUT") {
		t.Fatalf("Load() error = %v, want read timeout rejection", err)
	}

	setCompleteConfiguration(t)
	t.Setenv("MEDIA_HTTP_READ_TIMEOUT", "5m")
	t.Setenv("MEDIA_HTTP_WRITE_TIMEOUT", "invalid")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "MEDIA_HTTP_WRITE_TIMEOUT") {
		t.Fatalf("Load() error = %v, want write timeout rejection", err)
	}
}

func TestLoadRequiresVideoPlaybackOutputLimitWhenVideoIsEnabled(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_MAX_VIDEO_OUTPUT_BYTES", "")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "MEDIA_MAX_VIDEO_OUTPUT_BYTES") {
		t.Fatalf("Load() error = %v, want explicit video output limit rejection", err)
	}
}

func TestLoadRejectsVideoPlaybackOutputLimitAboveUploadLimit(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_MAX_VIDEO_OUTPUT_BYTES", "1048577")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "must not exceed") {
		t.Fatalf("Load() error = %v, want output/upload bound rejection", err)
	}
}

func TestLoadRejectsUnsafeManagementListenerAddress(t *testing.T) {
	addresses := []string{
		":9095",
		"0.0.0.0:9095",
		"[::]:9095",
		"localhost:9095",
		"10.0.0.8:9095",
		"127.0.0.1:0",
		"127.0.0.1:65536",
	}
	for _, address := range addresses {
		t.Run(address, func(t *testing.T) {
			setCompleteConfiguration(t)
			t.Setenv("MEDIA_RUNTIME_PROFILE", "production")
			t.Setenv("MEDIA_MANAGEMENT_ADDRESS", address)
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), "MEDIA_MANAGEMENT_ADDRESS") {
				t.Fatalf("Load() error = %v, want loopback address rejection", err)
			}
		})
	}
}

func TestLoadAcceptsExplicitIPv6LoopbackManagementListener(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_RUNTIME_PROFILE", "production")
	t.Setenv("MEDIA_MANAGEMENT_ADDRESS", "[::1]:9095")
	configuration, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if configuration.ManagementAddress != "[::1]:9095" {
		t.Fatalf("management address = %q", configuration.ManagementAddress)
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

func TestLoadRejectsNonCanonicalDriverShiftOwnerProofKafkaConfiguration(t *testing.T) {
	setCompleteConfiguration(t)
	t.Setenv("MEDIA_KAFKA_DRIVER_SHIFT_OWNER_PROOF_TOPIC", "rwms.task-board.entry-owner-proof.v1")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want non-canonical driver-shift owner proof topic rejection")
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
		"MEDIA_MAX_VIDEO_DURATION":     "2m",
		"MEDIA_MAX_VIDEO_OUTPUT_BYTES": "524288",
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
