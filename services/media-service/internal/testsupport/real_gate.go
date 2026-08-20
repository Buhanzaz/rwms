// Package testsupport provides isolated PostgreSQL, Kafka, and MinIO helpers
// for media-service integration tests. It must never be imported by runtime
// application code.
package testsupport

import (
	"bufio"
	"context"
	"fmt"
	"hash/crc32"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// RealGateEnvironment makes tests fail rather than skip when a real dependency
// environment is expected by CI.
const RealGateEnvironment = "MEDIA_TASK1B_REAL_GATE"

// Dependency identifies one external service required by an integration test.
type Dependency string

const (
	// PostgreSQL requires an isolated PostgreSQL test database.
	PostgreSQL Dependency = "postgresql"
	// Kafka requires the configured real Kafka test cluster.
	Kafka Dependency = "kafka"
	// MinIO requires the configured real versioned MinIO test bucket.
	MinIO Dependency = "minio"
)

// RealEnvironment contains the externally supplied integration-test endpoints
// and credentials after RequireRealEnvironment verifies them.
type RealEnvironment struct {
	DatabaseURL    string
	KafkaBrokers   []string
	MinIOEndpoint  string
	MinIOAccessKey string
	MinIOSecretKey string
	MinIOBucket    string
}

// RequireRealEnvironment makes the Stage 7 residual gate fail closed in CI.
// Developers may run narrower packages without infrastructure: those tests
// skip unless every dependency requested by the test is configured.
func RequireRealEnvironment(t testing.TB, dependencies ...Dependency) RealEnvironment {
	t.Helper()
	environment := RealEnvironment{
		DatabaseURL:    strings.TrimSpace(os.Getenv("MEDIA_TEST_DATABASE_URL")),
		KafkaBrokers:   splitNonEmpty(os.Getenv("MEDIA_TEST_KAFKA_BROKERS")),
		MinIOEndpoint:  strings.TrimSpace(os.Getenv("MEDIA_TEST_MINIO_ENDPOINT")),
		MinIOAccessKey: strings.TrimSpace(os.Getenv("MEDIA_TEST_MINIO_ACCESS_KEY")),
		MinIOSecretKey: strings.TrimSpace(os.Getenv("MEDIA_TEST_MINIO_SECRET_KEY")),
		MinIOBucket:    strings.TrimSpace(os.Getenv("MEDIA_TEST_MINIO_BUCKET")),
	}
	missingAll := environment.missing(PostgreSQL, Kafka, MinIO)
	if os.Getenv(RealGateEnvironment) == "1" && len(missingAll) != 0 {
		t.Fatalf("%s=1 requires real Task 1B dependencies; missing %s",
			RealGateEnvironment, strings.Join(missingAll, ", "))
	}
	if missing := environment.missing(dependencies...); len(missing) != 0 {
		t.Skipf("real Task 1B dependencies are not configured: %s", strings.Join(missing, ", "))
	}
	return environment
}

func (environment RealEnvironment) missing(dependencies ...Dependency) []string {
	var missing []string
	for _, dependency := range dependencies {
		switch dependency {
		case PostgreSQL:
			if environment.DatabaseURL == "" {
				missing = append(missing, "MEDIA_TEST_DATABASE_URL")
			}
		case Kafka:
			if len(environment.KafkaBrokers) == 0 {
				missing = append(missing, "MEDIA_TEST_KAFKA_BROKERS")
			}
		case MinIO:
			if environment.MinIOEndpoint == "" {
				missing = append(missing, "MEDIA_TEST_MINIO_ENDPOINT")
			}
			if environment.MinIOAccessKey == "" {
				missing = append(missing, "MEDIA_TEST_MINIO_ACCESS_KEY")
			}
			if environment.MinIOSecretKey == "" {
				missing = append(missing, "MEDIA_TEST_MINIO_SECRET_KEY")
			}
			if environment.MinIOBucket == "" {
				missing = append(missing, "MEDIA_TEST_MINIO_BUCKET")
			}
		}
	}
	return missing
}

func splitNonEmpty(value string) []string {
	var values []string
	for _, item := range strings.Split(value, ",") {
		if item = strings.TrimSpace(item); item != "" {
			values = append(values, item)
		}
	}
	return values
}

// NewIsolatedPostgresDatabase creates and drops only a uniquely named Task 1B
// database. It never reuses or drops the shared rwms_media database.
func NewIsolatedPostgresDatabase(t testing.TB, baseURL string) string {
	t.Helper()
	configuration, err := pgxpool.ParseConfig(baseURL)
	if err != nil {
		t.Fatalf("parse Task 1B PostgreSQL URL: %v", err)
	}
	databaseName := "rwms_media_task1b_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if databaseName == configuration.ConnConfig.Database || databaseName == "rwms_media" {
		t.Fatalf("refusing non-isolated Task 1B database name %q", databaseName)
	}
	adminConfiguration := configuration.ConnConfig.Copy()
	adminConfiguration.Database = "postgres"
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	admin, err := pgx.ConnectConfig(ctx, adminConfiguration)
	if err != nil {
		t.Fatalf("connect PostgreSQL admin database: %v", err)
	}
	identifier := pgx.Identifier{databaseName}.Sanitize()
	if _, err := admin.Exec(ctx, "create database "+identifier); err != nil {
		admin.Close(ctx)
		t.Fatalf("create isolated Task 1B database: %v", err)
	}
	if err := admin.Close(ctx); err != nil {
		t.Fatalf("close PostgreSQL admin connection: %v", err)
	}
	t.Cleanup(func() {
		if !strings.HasPrefix(databaseName, "rwms_media_task1b_") {
			t.Errorf("refusing to drop unexpected database %q", databaseName)
			return
		}
		cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cleanupCancel()
		cleanupAdmin, cleanupErr := pgx.ConnectConfig(cleanupContext, adminConfiguration)
		if cleanupErr != nil {
			t.Errorf("connect PostgreSQL cleanup database: %v", cleanupErr)
			return
		}
		defer cleanupAdmin.Close(cleanupContext)
		if _, cleanupErr = cleanupAdmin.Exec(cleanupContext,
			"drop database "+identifier+" with (force)"); cleanupErr != nil {
			t.Errorf("drop isolated Task 1B database %s: %v", databaseName, cleanupErr)
		}
	})
	databaseURL, err := PostgresDatabaseURL(baseURL, databaseName)
	if err != nil {
		t.Fatalf("construct isolated Task 1B PostgreSQL URL: %v", err)
	}
	targetConfiguration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatalf("parse isolated Task 1B PostgreSQL URL: %v", err)
	}
	if targetConfiguration.ConnConfig.Database != databaseName ||
		targetConfiguration.ConnConfig.Database == configuration.ConnConfig.Database {
		t.Fatalf("isolated Task 1B PostgreSQL URL targets %q, want unique %q",
			targetConfiguration.ConnConfig.Database, databaseName)
	}
	return databaseURL
}

// PostgresDatabaseURL preserves credentials, address and connection options
// while replacing only the URL database path. Parsing the result through pgx
// prevents a query-string database override from silently selecting the base.
func PostgresDatabaseURL(baseURL, databaseName string) (string, error) {
	databaseName = strings.TrimSpace(databaseName)
	if databaseName == "" || strings.ContainsAny(databaseName, "/?#") {
		return "", fmt.Errorf("invalid PostgreSQL database name %q", databaseName)
	}
	targetURL, err := url.Parse(strings.TrimSpace(baseURL))
	if err != nil {
		return "", fmt.Errorf("parse PostgreSQL URL: %w", err)
	}
	if targetURL.Scheme != "postgres" && targetURL.Scheme != "postgresql" {
		return "", fmt.Errorf("PostgreSQL URL scheme %q is not postgres/postgresql", targetURL.Scheme)
	}
	targetURL.Path = "/" + databaseName
	targetURL.RawPath = ""
	query := targetURL.Query()
	query.Del("database")
	query.Del("dbname")
	targetURL.RawQuery = query.Encode()
	databaseURL := targetURL.String()
	configuration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return "", fmt.Errorf("parse targeted PostgreSQL URL: %w", err)
	}
	if configuration.ConnConfig.Database != databaseName {
		return "", fmt.Errorf("targeted PostgreSQL URL selects %q, want %q",
			configuration.ConnConfig.Database, databaseName)
	}
	return databaseURL, nil
}

// NewMigratedMediaDatabase returns an isolated database at the exact current schema.
// It is used by legacy integration tests that otherwise share mutable outbox
// and owner-projection state through MEDIA_TEST_DATABASE_URL.
func NewMigratedMediaDatabase(t testing.TB, baseURL string) string {
	return newMigratedMediaDatabase(t, baseURL, true)
}

// NewMigratedMediaDatabaseThroughV12 returns an isolated database immediately
// before the authoritative inventory photo migration so its additive upgrade
// path can be tested without touching a shared database.
func NewMigratedMediaDatabaseThroughV12(t testing.TB, baseURL string) string {
	return newMigratedMediaDatabase(t, baseURL, false)
}

func newMigratedMediaDatabase(t testing.TB, baseURL string, includeV13 bool) string {
	t.Helper()
	databaseURL := NewIsolatedPostgresDatabase(t, baseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open isolated migrated media database: %v", err)
	}
	defer pool.Close()
	if _, err := pool.Exec(ctx, `create extension if not exists pgcrypto`); err != nil {
		t.Fatalf("enable isolated media pgcrypto: %v", err)
	}
	if _, err := pool.Exec(ctx, `create table flyway_schema_history (
		installed_rank integer not null primary key,
		version varchar(50), description varchar(200) not null, type varchar(20) not null,
		script varchar(1000) not null, checksum integer, installed_by varchar(100) not null,
		installed_on timestamp not null default now(), execution_time integer not null,
		success boolean not null)`); err != nil {
		t.Fatalf("create isolated media Flyway history: %v", err)
	}
	migrations := []struct {
		description string
		script      string
		body        []byte
	}{
		{"media schema", "V1__media_schema.sql", mediamigration.V1},
		{"media runtime recovery", "V2__media_runtime_recovery.sql", mediamigration.V2},
		{"inventory owner proof", "V3__inventory_owner_proof.sql", mediamigration.V3},
		{"cabin owner bindings", "V4__cabin_owner_bindings.sql", mediamigration.V4},
		{"prepare legacy photo folder backfill", "V4_1__prepare_legacy_photo_folder_backfill.sql", mediamigration.V4_1},
		{"media photo folders", "V5__media_photo_folders.sql", mediamigration.V5},
		{"restore runtime source guard", "V5_1__restore_runtime_source_guard.sql", mediamigration.V5_1},
		{"service owner proofs and soft delete", "V6__service_owner_proofs_and_soft_delete.sql", mediamigration.V6},
		{"dynamic cabin owner projection", "V7__dynamic_cabin_owner_projection.sql", mediamigration.V7},
		{"task board worker media", "V8__task_board_worker_media.sql", mediamigration.V8},
		{"asset import worker", "V9__asset_import_worker.sql", mediamigration.V9},
		{"canonical cabin photo library", "V10__canonical_cabin_photo_library.sql", mediamigration.V10},
		{"bounded media processing recovery", "V11__bounded_media_processing_recovery.sql", mediamigration.V11},
		{"video playback variant", "V12__video_playback_variant.sql", mediamigration.V12},
		{"authoritative inventory cabin photos", "V13__authoritative_inventory_cabin_photos.sql", mediamigration.V13},
	}
	if !includeV13 {
		migrations = migrations[:len(migrations)-1]
	}
	for index, migration := range migrations {
		started := time.Now()
		if _, err := pool.Exec(ctx, string(migration.body)); err != nil {
			t.Fatalf("apply isolated media %s: %v", migration.script, err)
		}
		if _, err := pool.Exec(ctx, `insert into flyway_schema_history (
			installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
		values ($1,$2,$3,'SQL',$4,$5,current_user,$6,true)`, index+1,
			[]string{"1", "2", "3", "4", "4.1", "5", "5.1", "6", "7", "8", "9", "10", "11", "12", "13"}[index],
			migration.description, migration.script, realFlywayChecksum(migration.body),
			int(time.Since(started)/time.Millisecond)); err != nil {
			t.Fatalf("record isolated media %s: %v", migration.script, err)
		}
	}
	return databaseURL
}

func realFlywayChecksum(contents []byte) int32 {
	text := strings.TrimPrefix(string(contents), "\ufeff")
	checksum := crc32.NewIEEE()
	scanner := bufio.NewScanner(strings.NewReader(strings.ReplaceAll(text, "\r\n", "\n")))
	buffer := make([]byte, 64*1024)
	scanner.Buffer(buffer, 8*1024*1024)
	for scanner.Scan() {
		_, _ = checksum.Write(scanner.Bytes())
	}
	if err := scanner.Err(); err != nil {
		panic(err)
	}
	return int32(checksum.Sum32())
}

// UniqueKafkaName returns an isolated physical Kafka resource name with the
// requested safe prefix.
func UniqueKafkaName(prefix string) string {
	prefix = strings.Trim(strings.ToLower(prefix), "-._")
	return fmt.Sprintf("%s-%s", prefix, uuid.NewString())
}

// UniqueBucketName returns an isolated MinIO bucket name with the requested
// safe prefix.
func UniqueBucketName(prefix string) string {
	prefix = strings.Trim(strings.ToLower(prefix), "-.")
	return fmt.Sprintf("%s-%s", prefix, strings.ReplaceAll(uuid.NewString(), "-", ""))
}

// NewVersionedMinIOBucket creates a unique integration bucket and enables
// versioning before the service verifies it. Cleanup enumerates and removes
// only that exact bucket's versions before dropping the bucket itself.
func NewVersionedMinIOBucket(t testing.TB, endpoint, accessKey, secretKey string,
	useSSL bool) string {
	t.Helper()
	client, err := minio.New(endpoint, &minio.Options{
		Creds: credentials.NewStaticV4(accessKey, secretKey, ""), Secure: useSSL,
	})
	if err != nil {
		t.Fatalf("create isolated Task 1B MinIO client: %v", err)
	}
	bucket := UniqueBucketName("rwms-media-task1b")
	if !isolatedMinIOBucket(bucket) {
		t.Fatalf("refusing non-isolated Task 1B MinIO bucket %q", bucket)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := client.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); err != nil {
		t.Fatalf("create isolated Task 1B MinIO bucket %q: %v", bucket, err)
	}
	if err := client.SetBucketVersioning(ctx, bucket, minio.BucketVersioningConfiguration{
		Status: minio.Enabled,
	}); err != nil {
		t.Fatalf("enable isolated Task 1B MinIO bucket versioning: %v", err)
	}
	configuration, err := client.GetBucketVersioning(ctx, bucket)
	if err != nil || !configuration.Enabled() {
		t.Fatalf("verify isolated Task 1B MinIO bucket versioning: status=%q error=%v",
			configuration.Status, err)
	}
	t.Cleanup(func() {
		if !isolatedMinIOBucket(bucket) {
			t.Errorf("refusing to remove non-isolated MinIO bucket %q", bucket)
			return
		}
		cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cleanupCancel()
		for object := range client.ListObjects(cleanupContext, bucket, minio.ListObjectsOptions{
			Recursive: true, WithVersions: true,
		}) {
			if object.Err != nil {
				t.Errorf("list isolated MinIO bucket %q versions: %v", bucket, object.Err)
				return
			}
			if err := client.RemoveObject(cleanupContext, bucket, object.Key, minio.RemoveObjectOptions{
				VersionID: object.VersionID,
			}); err != nil {
				t.Errorf("remove isolated MinIO object %q version %q: %v",
					object.Key, object.VersionID, err)
				return
			}
		}
		if err := client.RemoveBucket(cleanupContext, bucket); err != nil {
			t.Errorf("remove isolated Task 1B MinIO bucket %q: %v", bucket, err)
		}
	})
	return bucket
}

func isolatedMinIOBucket(bucket string) bool {
	return strings.HasPrefix(bucket, "rwms-media-task1b-") && len(bucket) <= 63
}
