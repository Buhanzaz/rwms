package config

import (
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	RuntimeProfile                string
	HTTPAddress                   string
	DatabaseURL                   string
	Issuer                        string
	Audience                      string
	JWKSURL                       string
	MinIOEndpoint                 string
	MinIOAccessKey                string
	MinIOSecretKey                string
	MinIOBucket                   string
	MinIOUseSSL                   bool
	MaxUploadBytes                int64
	AllowedMIMETypes              map[string]struct{}
	UploadExpiry                  time.Duration
	MaxDecodedPixels              int64
	MaxImageOutputBytes           int64
	MaxVideoOutputBytes           int64
	MaxVideoDuration              time.Duration
	AllowedVideoCodecs            map[string]struct{}
	ProcessingTimeout             time.Duration
	KafkaBrokers                  []string
	MediaTopic                    string
	ProcessingTopic               string
	ProcessingGroup               string
	InventoryTopic                string
	InventoryOwnerGroup           string
	InventoryOwnerDLT             string
	AssetRentalItemTopic          string
	CabinOwnerGroup               string
	TaskBoardEntryOwnerProofTopic string
	TaskBoardEntryOwnerProofGroup string
	InstanceID                    string
}

func Load() (Config, error) {
	configuration := Config{
		RuntimeProfile:  strings.TrimSpace(os.Getenv("MEDIA_RUNTIME_PROFILE")),
		HTTPAddress:     value("MEDIA_HTTP_ADDRESS", ":8085"),
		DatabaseURL:     os.Getenv("MEDIA_DATABASE_URL"),
		Issuer:          os.Getenv("MEDIA_AUTH_ISSUER"),
		Audience:        value("MEDIA_AUTH_AUDIENCE", "rwms-services"),
		JWKSURL:         os.Getenv("MEDIA_AUTH_JWKS_URL"),
		MinIOEndpoint:   os.Getenv("MEDIA_MINIO_ENDPOINT"),
		MinIOAccessKey:  os.Getenv("MEDIA_MINIO_ACCESS_KEY"),
		MinIOSecretKey:  os.Getenv("MEDIA_MINIO_SECRET_KEY"),
		MinIOBucket:     os.Getenv("MEDIA_MINIO_BUCKET"),
		KafkaBrokers:    split(os.Getenv("MEDIA_KAFKA_BROKERS")),
		MediaTopic:      value("MEDIA_KAFKA_MEDIA_TOPIC", "rwms.media.media.v1"),
		ProcessingTopic: value("MEDIA_KAFKA_PROCESSING_TOPIC", "rwms.media.processing.v1"),
		ProcessingGroup: value("MEDIA_KAFKA_PROCESSING_GROUP", "media-service-processing-v1"),
		InventoryTopic:  value("MEDIA_KAFKA_INVENTORY_TOPIC", "rwms.inventory.session.v1"),
		InventoryOwnerGroup: value(
			"MEDIA_KAFKA_INVENTORY_OWNER_GROUP", "media-service-inventory-owner-v1"),
		InventoryOwnerDLT: value("MEDIA_KAFKA_INVENTORY_OWNER_DLT_TOPIC",
			"rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt"),
		AssetRentalItemTopic: value(
			"MEDIA_KAFKA_ASSET_RENTAL_ITEM_TOPIC", "rwms.asset.rental-item.v1"),
		CabinOwnerGroup: value(
			"MEDIA_KAFKA_CABIN_OWNER_GROUP", "media-service-cabin-owner-v1"),
		TaskBoardEntryOwnerProofTopic: value(
			"MEDIA_KAFKA_TASK_BOARD_ENTRY_OWNER_PROOF_TOPIC", "rwms.task-board.entry-owner-proof.v1"),
		TaskBoardEntryOwnerProofGroup: value(
			"MEDIA_KAFKA_TASK_BOARD_ENTRY_OWNER_PROOF_GROUP", "media-service-task-board-entry-owner-proof-v1"),
		InstanceID: os.Getenv("MEDIA_INSTANCE_ID"),
	}

	var err error
	if configuration.MinIOUseSSL, err = requiredBool("MEDIA_MINIO_USE_SSL"); err != nil {
		return Config{}, err
	}
	if configuration.MaxUploadBytes, err = requiredPositiveInt64("MEDIA_MAX_UPLOAD_BYTES"); err != nil {
		return Config{}, err
	}
	if configuration.MaxDecodedPixels, err = requiredPositiveInt64("MEDIA_MAX_DECODED_PIXELS"); err != nil {
		return Config{}, err
	}
	if configuration.MaxImageOutputBytes, err = requiredPositiveInt64("MEDIA_MAX_IMAGE_OUTPUT_BYTES"); err != nil {
		return Config{}, err
	}
	if configuration.MaxVideoOutputBytes, err = requiredPositiveInt64("MEDIA_MAX_VIDEO_OUTPUT_BYTES"); err != nil {
		return Config{}, err
	}
	if configuration.UploadExpiry, err = requiredPositiveDuration("MEDIA_UPLOAD_EXPIRY"); err != nil {
		return Config{}, err
	}
	if configuration.ProcessingTimeout, err = requiredPositiveDuration("MEDIA_PROCESSING_TIMEOUT"); err != nil {
		return Config{}, err
	}
	configuration.AllowedMIMETypes, err = allowedMIMETypes(os.Getenv("MEDIA_ALLOWED_MIME_TYPES"))
	if err != nil {
		return Config{}, err
	}
	if containsVideo(configuration.AllowedMIMETypes) {
		if configuration.MaxVideoDuration, err = requiredPositiveDuration("MEDIA_MAX_VIDEO_DURATION"); err != nil {
			return Config{}, err
		}
		configuration.AllowedVideoCodecs, err = allowedVideoCodecs(os.Getenv("MEDIA_ALLOWED_VIDEO_CODECS"))
		if err != nil {
			return Config{}, err
		}
	} else {
		configuration.AllowedVideoCodecs = map[string]struct{}{}
	}

	for name, required := range map[string]string{
		"MEDIA_RUNTIME_PROFILE":  configuration.RuntimeProfile,
		"MEDIA_DATABASE_URL":     configuration.DatabaseURL,
		"MEDIA_AUTH_ISSUER":      configuration.Issuer,
		"MEDIA_AUTH_JWKS_URL":    configuration.JWKSURL,
		"MEDIA_MINIO_ENDPOINT":   configuration.MinIOEndpoint,
		"MEDIA_MINIO_ACCESS_KEY": configuration.MinIOAccessKey,
		"MEDIA_MINIO_SECRET_KEY": configuration.MinIOSecretKey,
		"MEDIA_MINIO_BUCKET":     configuration.MinIOBucket,
		"MEDIA_INSTANCE_ID":      configuration.InstanceID,
	} {
		if strings.TrimSpace(required) == "" {
			return Config{}, fmt.Errorf("%s is required", name)
		}
	}
	if len(configuration.KafkaBrokers) == 0 {
		return Config{}, fmt.Errorf("MEDIA_KAFKA_BROKERS is required")
	}
	if configuration.MediaTopic != "rwms.media.media.v1" || configuration.ProcessingTopic != "rwms.media.processing.v1" {
		return Config{}, fmt.Errorf("media Kafka topics must match the canonical contracts")
	}
	if configuration.ProcessingGroup != "media-service-processing-v1" {
		return Config{}, fmt.Errorf("MEDIA_KAFKA_PROCESSING_GROUP must match the canonical DLT contract")
	}
	if configuration.InventoryTopic != "rwms.inventory.session.v1" ||
		configuration.InventoryOwnerGroup != "media-service-inventory-owner-v1" ||
		configuration.InventoryOwnerDLT != "rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt" {
		return Config{}, fmt.Errorf("inventory owner Kafka topic, group and DLT must match the canonical contracts")
	}
	if configuration.AssetRentalItemTopic != "rwms.asset.rental-item.v1" ||
		configuration.CabinOwnerGroup != "media-service-cabin-owner-v1" {
		return Config{}, fmt.Errorf("cabin owner Kafka topic and group must match the canonical contracts")
	}
	if configuration.TaskBoardEntryOwnerProofTopic != "rwms.task-board.entry-owner-proof.v1" ||
		configuration.TaskBoardEntryOwnerProofGroup != "media-service-task-board-entry-owner-proof-v1" {
		return Config{}, fmt.Errorf("task-board entry owner proof Kafka topic and group must match the canonical contracts")
	}
	if !validInstanceID(configuration.InstanceID) {
		return Config{}, fmt.Errorf("MEDIA_INSTANCE_ID must be 1-64 safe ASCII characters")
	}
	if configuration.RuntimeProfile != "production" && configuration.RuntimeProfile != "local-test" {
		return Config{}, fmt.Errorf("MEDIA_RUNTIME_PROFILE must be production or local-test")
	}
	requireHTTPS := configuration.RuntimeProfile == "production"
	if requireHTTPS && !configuration.MinIOUseSSL {
		return Config{}, fmt.Errorf("MEDIA_MINIO_USE_SSL must be true in production")
	}
	if err := validateHTTPURL("MEDIA_AUTH_ISSUER", configuration.Issuer, requireHTTPS); err != nil {
		return Config{}, err
	}
	if err := validateHTTPURL("MEDIA_AUTH_JWKS_URL", configuration.JWKSURL, requireHTTPS); err != nil {
		return Config{}, err
	}
	return configuration, nil
}

func value(name, fallback string) string {
	if candidate := strings.TrimSpace(os.Getenv(name)); candidate != "" {
		return candidate
	}
	return fallback
}

func split(raw string) []string {
	var result []string
	for _, candidate := range strings.Split(raw, ",") {
		if candidate = strings.TrimSpace(candidate); candidate != "" {
			result = append(result, candidate)
		}
	}
	return result
}

func requiredPositiveInt64(name string) (int64, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	parsed, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be an explicit positive integer", name)
	}
	return parsed, nil
}

func requiredBool(name string) (bool, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return false, fmt.Errorf("%s is required", name)
	}
	parsed, err := strconv.ParseBool(raw)
	if err != nil {
		return false, fmt.Errorf("%s must be true or false", name)
	}
	return parsed, nil
}

func requiredPositiveDuration(name string) (time.Duration, error) {
	parsed, err := time.ParseDuration(strings.TrimSpace(os.Getenv(name)))
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be an explicit positive duration", name)
	}
	return parsed, nil
}

func allowedMIMETypes(raw string) (map[string]struct{}, error) {
	allowed := make(map[string]struct{})
	for _, candidate := range split(raw) {
		candidate = strings.ToLower(candidate)
		switch candidate {
		case "image/jpeg", "image/png", "image/webp", "video/mp4", "video/webm":
			allowed[candidate] = struct{}{}
		default:
			return nil, fmt.Errorf("MEDIA_ALLOWED_MIME_TYPES contains unsupported type %q", candidate)
		}
	}
	if len(allowed) == 0 {
		return nil, fmt.Errorf("MEDIA_ALLOWED_MIME_TYPES is required")
	}
	return allowed, nil
}

func validateHTTPURL(name, raw string, requireHTTPS bool) error {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" || parsed.RawQuery != "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return fmt.Errorf("%s must be an absolute HTTP(S) URL without credentials", name)
	}
	if requireHTTPS && parsed.Scheme != "https" {
		return fmt.Errorf("%s must use HTTPS in production", name)
	}
	return nil
}

func containsVideo(types map[string]struct{}) bool {
	for candidate := range types {
		if strings.HasPrefix(candidate, "video/") {
			return true
		}
	}
	return false
}

func allowedVideoCodecs(raw string) (map[string]struct{}, error) {
	allowed := make(map[string]struct{})
	for _, candidate := range split(raw) {
		candidate = strings.ToLower(candidate)
		if candidate == "" || len(candidate) > 32 {
			return nil, fmt.Errorf("MEDIA_ALLOWED_VIDEO_CODECS contains an invalid codec")
		}
		for _, character := range candidate {
			if !((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') || character == '_' || character == '-') {
				return nil, fmt.Errorf("MEDIA_ALLOWED_VIDEO_CODECS contains an invalid codec")
			}
		}
		allowed[candidate] = struct{}{}
	}
	if len(allowed) == 0 {
		return nil, fmt.Errorf("MEDIA_ALLOWED_VIDEO_CODECS is required when video is enabled")
	}
	return allowed, nil
}

func validInstanceID(value string) bool {
	if len(value) < 1 || len(value) > 64 {
		return false
	}
	for index, character := range value {
		alphaNumeric := (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9')
		if !alphaNumeric && !(index > 0 && (character == '.' || character == '_' || character == '-')) {
			return false
		}
	}
	return true
}
