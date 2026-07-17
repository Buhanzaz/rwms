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

var ErrSchemaNotReady = errors.New("media schema is not at the approved Flyway version")

type Database struct {
	Pool *pgxpool.Pool
}

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

func (database *Database) Close() {
	if database != nil && database.Pool != nil {
		database.Pool.Close()
	}
}

func (database *Database) Ready(ctx context.Context) error {
	if err := database.Pool.Ping(ctx); err != nil {
		return err
	}
	return database.VerifyMigrations(ctx)
}

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
	type row struct {
		version, description, migrationType, script string
		checksum                                    *int32
		success                                     bool
	}
	var history []row
	for rows.Next() {
		var item row
		if err := rows.Scan(&item.version, &item.description, &item.migrationType, &item.script, &item.checksum, &item.success); err != nil {
			return fmt.Errorf("scan Flyway history: %w", err)
		}
		history = append(history, item)
	}
	if err := rows.Err(); err != nil {
		return fmt.Errorf("read Flyway history: %w", err)
	}
	expected := []struct {
		version, description, script string
		contents                     []byte
	}{
		{"1", "media schema", "V1__media_schema.sql", mediamigration.V1},
		{"2", "media runtime recovery", "V2__media_runtime_recovery.sql", mediamigration.V2},
		{"3", "inventory owner proof", "V3__inventory_owner_proof.sql", mediamigration.V3},
	}
	if len(history) != len(expected) {
		return fmt.Errorf("%w: expected exactly V1, V2 and V3, found %d versioned rows", ErrSchemaNotReady, len(history))
	}
	for index, wanted := range expected {
		actual := history[index]
		checksum := flywayChecksum(wanted.contents)
		if actual.version != wanted.version || actual.description != wanted.description || actual.script != wanted.script || actual.migrationType != "SQL" || !actual.success || actual.checksum == nil || *actual.checksum != checksum {
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
