package persistence

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"hash/crc32"
	"strings"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrSchemaNotReady indicates that PostgreSQL does not have exactly the
// approved immutable Flyway migration history.
var ErrSchemaNotReady = errors.New("media schema is not at the approved Flyway version")

// Database wraps the service-owned PostgreSQL pool and its schema readiness
// gate.
type Database struct {
	Pool *pgxpool.Pool
}

type migrationHistoryRow struct {
	version, description, migrationType, script string
	checksum                                    *int32
	success                                     bool
}

type approvedMigration struct {
	version, description, script string
	contents                     []byte
}

// Open creates the media PostgreSQL pool and fails closed unless Flyway history
// exactly matches the embedded approved migrations.
func Open(ctx context.Context, databaseURL string) (*Database, error) {
	configuration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse media database URL: %w", err)
	}
	configuration.MaxConns = 20
	configuration.MinConns = 1
	configuration.MaxConnLifetime = 30 * time.Minute
	pool, err := pgxpool.NewWithConfig(ctx, configuration)
	if err != nil {
		return nil, fmt.Errorf("open media database: %w", err)
	}
	database := &Database{Pool: pool}
	if err := database.VerifyMigrations(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return database, nil
}

// Close releases all database pool resources.
func (database *Database) Close() {
	if database != nil && database.Pool != nil {
		database.Pool.Close()
	}
}

// Ready verifies connectivity and the exact immutable migration history.
func (database *Database) Ready(ctx context.Context) error {
	if err := database.Pool.Ping(ctx); err != nil {
		return err
	}
	return database.VerifyMigrations(ctx)
}

// VerifyMigrations checks Flyway versions, descriptions, SQL type, success,
// and checksums against the approved V1–V20 and V22 sequence.
func (database *Database) VerifyMigrations(ctx context.Context) error {
	var historyTable *string
	if err := database.Pool.QueryRow(ctx, "select to_regclass('public.flyway_schema_history')::text").Scan(&historyTable); err != nil {
		return fmt.Errorf("inspect Flyway history: %w", err)
	}
	if historyTable == nil {
		return fmt.Errorf("%w: flyway_schema_history is absent", ErrSchemaNotReady)
	}
	rows, err := database.Pool.Query(ctx, `
		select version, description, type, script, checksum, success
		from flyway_schema_history
		order by installed_rank`)
	if err != nil {
		return fmt.Errorf("read Flyway history: %w", err)
	}
	defer rows.Close()
	var history []migrationHistoryRow
	for rows.Next() {
		var item migrationHistoryRow
		if err := rows.Scan(&item.version, &item.description, &item.migrationType, &item.script, &item.checksum, &item.success); err != nil {
			return fmt.Errorf("scan Flyway history: %w", err)
		}
		history = append(history, item)
	}
	if err := rows.Err(); err != nil {
		return fmt.Errorf("read Flyway history: %w", err)
	}
	return verifyMigrationHistory(history)
}

func verifyMigrationHistory(history []migrationHistoryRow) error {
	expected := []approvedMigration{
		{"1", "media schema", "V1__media_schema.sql", mediamigration.V1},
		{"2", "media runtime recovery", "V2__media_runtime_recovery.sql", mediamigration.V2},
		{"3", "inventory owner proof", "V3__inventory_owner_proof.sql", mediamigration.V3},
		{"4", "cabin owner bindings", "V4__cabin_owner_bindings.sql", mediamigration.V4},
		{"4.1", "prepare legacy photo folder backfill", "V4_1__prepare_legacy_photo_folder_backfill.sql", mediamigration.V4_1},
		{"5", "media photo folders", "V5__media_photo_folders.sql", mediamigration.V5},
		{"5.1", "restore runtime source guard", "V5_1__restore_runtime_source_guard.sql", mediamigration.V5_1},
		{"6", "service owner proofs and soft delete", "V6__service_owner_proofs_and_soft_delete.sql", mediamigration.V6},
		{"7", "dynamic cabin owner projection", "V7__dynamic_cabin_owner_projection.sql", mediamigration.V7},
		{"8", "task board worker media", "V8__task_board_worker_media.sql", mediamigration.V8},
		{"9", "asset import worker", "V9__asset_import_worker.sql", mediamigration.V9},
		{"10", "canonical cabin photo library", "V10__canonical_cabin_photo_library.sql", mediamigration.V10},
		{"11", "bounded media processing recovery", "V11__bounded_media_processing_recovery.sql", mediamigration.V11},
		{"12", "video playback variant", "V12__video_playback_variant.sql", mediamigration.V12},
		{"13", "authoritative inventory cabin photos", "V13__authoritative_inventory_cabin_photos.sql", mediamigration.V13},
		{"14", "task board reader audience", "V14__task_board_reader_audience.sql", mediamigration.V14},
		{"15", "inventory finding membership markers", "V15__inventory_finding_membership_markers.sql", mediamigration.V15},
		{"16", "client image variants", "V16__client_image_variants.sql", mediamigration.V16},
		{"17", "consolidate legacy cabin photo folders", "V17__consolidate_legacy_cabin_photo_folders.sql", mediamigration.V17},
		{"18", "customer shipment subject binding", "V18__customer_shipment_subject_binding.sql", mediamigration.V18},
		{"19", "customer profile avatar owner", "V19__customer_profile_avatar_owner.sql", mediamigration.V19},
		{"20", "driver shift media owner", "V20__driver_shift_media_owner.sql", mediamigration.V20},
		{"22", "task board worker profile avatar owner", "V22__task_board_worker_profile_avatar_owner.sql", mediamigration.V22},
	}
	if len(history) != len(expected) {
		return fmt.Errorf("%w: expected the exact approved V1 through V20 and V22 history, found %d versioned rows", ErrSchemaNotReady, len(history))
	}
	expectedByVersion := make(map[string]approvedMigration, len(expected))
	for _, migration := range expected {
		expectedByVersion[migration.version] = migration
	}
	seen := make(map[string]struct{}, len(history))
	for _, actual := range history {
		wanted, approved := expectedByVersion[actual.version]
		if !approved {
			return fmt.Errorf("%w: unexpected migration version %q", ErrSchemaNotReady, actual.version)
		}
		if _, duplicate := seen[actual.version]; duplicate {
			return fmt.Errorf("%w: duplicate migration version V%s", ErrSchemaNotReady, actual.version)
		}
		seen[actual.version] = struct{}{}
		checksum := flywayChecksum(wanted.contents)
		if actual.description != wanted.description || actual.script != wanted.script || actual.migrationType != "SQL" || !actual.success || actual.checksum == nil || *actual.checksum != checksum {
			return fmt.Errorf("%w: V%s metadata or checksum mismatch", ErrSchemaNotReady, wanted.version)
		}
	}
	return nil
}

// Flyway computes the SQL checksum as CRC-32 over UTF-8 lines after BOM
// removal. Hashing normalized lines separately matches Flyway's
// PositionTrackingReader behavior and is intentionally covered by migration
// integration tests against the real CLI.
func flywayChecksum(contents []byte) int32 {
	text := strings.TrimPrefix(string(contents), "\ufeff")
	checksum := crc32.NewIEEE()
	scanner := bufio.NewScanner(strings.NewReader(strings.ReplaceAll(text, "\r\n", "\n")))
	buffer := make([]byte, 64*1024)
	scanner.Buffer(buffer, 8*1024*1024)
	for scanner.Scan() {
		_, _ = checksum.Write(scanner.Bytes())
	}
	if err := scanner.Err(); err != nil {
		panic("embedded Flyway migration line exceeds checksum scanner bounds")
	}
	return int32(checksum.Sum32())
}
