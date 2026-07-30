package main

import (
	"bufio"
	"context"
	"errors"
	"hash/crc32"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestInventoryOwnerRuntimeIsRequiredAndTerminalExitStopsAllProcesses(t *testing.T) {
	t.Run("owner consumer is mandatory", func(t *testing.T) {
		var ran atomic.Bool
		processes := []mediaRuntimeProcess{
			{name: "outbox-relay", run: func(context.Context) error { ran.Store(true); return nil }},
			{name: "processing-consumer", run: func(context.Context) error { ran.Store(true); return nil }},
			{name: "http-server", run: func(context.Context) error { ran.Store(true); return nil }},
		}
		err := superviseMediaRuntime(context.Background(), time.Second, processes,
			func(context.Context) error { return nil })
		if err == nil || !strings.Contains(err.Error(), "all required supervised processes") {
			t.Fatalf("missing owner consumer error = %v", err)
		}
		if ran.Load() {
			t.Fatal("runtime started before mandatory owner consumer validation")
		}
	})

	t.Run("terminal owner error cancels peers and unfinished work is not committed", func(t *testing.T) {
		terminalError := errors.New("inventory owner terminal dependency failure")
		ownerStarted := make(chan struct{})
		allowOwnerFailure := make(chan struct{})
		var ownerStartOnce sync.Once
		var peersCancelled atomic.Int32
		peer := func(ctx context.Context) error {
			<-ctx.Done()
			peersCancelled.Add(1)
			return nil
		}
		processes := []mediaRuntimeProcess{
			{name: "outbox-relay", run: peer},
			{name: "processing-consumer", run: peer},
			{name: "inventory-owner-consumer", run: func(ctx context.Context) error {
				ownerStartOnce.Do(func() { close(ownerStarted) })
				select {
				case <-ctx.Done():
					return ctx.Err()
				case <-allowOwnerFailure:
					return terminalError
				}
			}},
			{name: "cabin-owner-consumer", run: peer},
			{name: "task-board-entry-owner-proof-consumer", run: peer},
			{name: "asset-import-worker", run: peer},
			{name: "http-server", run: peer},
		}
		shutdownCalled := make(chan struct{}, 1)
		done := make(chan error, 1)
		go func() {
			done <- superviseMediaRuntime(context.Background(), 3*time.Second, processes,
				func(context.Context) error {
					shutdownCalled <- struct{}{}
					return nil
				})
		}()
		select {
		case <-ownerStarted:
		case <-time.After(time.Second):
			t.Fatal("owner consumer did not start")
		}
		close(allowOwnerFailure)
		select {
		case err := <-done:
			if !errors.Is(err, terminalError) {
				t.Fatalf("supervisor error = %v, want owner terminal error", err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("supervisor did not stop after owner terminal error")
		}
		if peersCancelled.Load() != 6 {
			t.Fatalf("cancelled peers = %d, want 6", peersCancelled.Load())
		}
		select {
		case <-shutdownCalled:
		default:
			t.Fatal("bounded shutdown was not called")
		}
	})
}

func TestInventoryOwnerReconcileCommandRequiresV3AndRejectsUnsafeFilesWithoutMutationReal(t *testing.T) {
	environment := testsupport.RequireRealEnvironment(t, testsupport.PostgreSQL)
	databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open reconcile command database: %v", err)
	}
	defer pool.Close()
	installMainResidualMigrations(t, ctx, pool)
	aggregateID := uuid.New()
	if _, err := pool.Exec(ctx, `insert into media_quarantined_aggregate (
		consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,
		reason_code,first_event_id)
	values ($1,'FINDING',$2,0,0,'INVALID_STREAM_BOOTSTRAP',$3)`,
		persistence.InventoryOwnerConsumerGroup, aggregateID, uuid.New()); err != nil {
		t.Fatalf("seed command quarantine: %v", err)
	}

	t.Setenv("MEDIA_DATABASE_URL", databaseURL)
	// These deliberately invalid runtime variables prove the operator command
	// returns through the early migration-only branch without starting HTTP/MinIO/Kafka.
	t.Setenv("MEDIA_HTTP_ADDRESS", "not-a-listen-address")
	t.Setenv("MEDIA_MINIO_ENDPOINT", "")
	t.Setenv("MEDIA_KAFKA_BROKERS", "")
	originalArgs := os.Args
	t.Cleanup(func() { os.Args = originalArgs })
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	tests := []struct {
		name      string
		contents  []byte
		errorText string
	}{
		{name: "malformed", contents: []byte(`{"aggregateId":`), errorText: "decode inventory owner reconciliation file"},
		{name: "trailing", contents: []byte(`{"aggregateId":"bad","expectedCheckpointVersion":0,"reviewerId":"bad","reason":"reviewed","records":[]} {}`), errorText: "trailing data"},
		{name: "oversized", contents: make([]byte, (16<<20)+1), errorText: "bounded regular file"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			path := t.TempDir() + "/reviewed-batch.json"
			if err := os.WriteFile(path, test.contents, 0o600); err != nil {
				t.Fatalf("write %s command fixture: %v", test.name, err)
			}
			before := mainResidualQuarantineFingerprint(t, ctx, pool, aggregateID)
			os.Args = []string{"media-service", "reconcile-inventory-owner", path}
			err := run(logger)
			if err == nil || !strings.Contains(err.Error(), test.errorText) {
				t.Fatalf("run(%s) error = %v, want %q", test.name, err, test.errorText)
			}
			after := mainResidualQuarantineFingerprint(t, ctx, pool, aggregateID)
			if after != before {
				t.Fatalf("%s reconciliation mutated quarantine: before=%q after=%q",
					test.name, before, after)
			}
		})
	}

	if _, err := pool.Exec(ctx, `update flyway_schema_history set checksum=checksum+1 where version='4'`); err != nil {
		t.Fatalf("seed command V4 checksum drift: %v", err)
	}
	path := t.TempDir() + "/malformed-after-drift.json"
	if err := os.WriteFile(path, []byte(`{"aggregateId":`), 0o600); err != nil {
		t.Fatal(err)
	}
	os.Args = []string{"media-service", "reconcile-inventory-owner", path}
	err = run(logger)
	if !errors.Is(err, persistence.ErrSchemaNotReady) {
		t.Fatalf("reconcile command with V3 drift error = %v, want ErrSchemaNotReady", err)
	}
}

func installMainResidualMigrations(t testing.TB, ctx context.Context, pool *pgxpool.Pool) {
	t.Helper()
	if _, err := pool.Exec(ctx, `create extension if not exists pgcrypto`); err != nil {
		t.Fatalf("enable command pgcrypto: %v", err)
	}
	if _, err := pool.Exec(ctx, `create table flyway_schema_history (
		installed_rank integer not null primary key,
		version varchar(50), description varchar(200) not null, type varchar(20) not null,
		script varchar(1000) not null, checksum integer, installed_by varchar(100) not null,
		installed_on timestamp not null default now(), execution_time integer not null,
		success boolean not null)`); err != nil {
		t.Fatalf("create command Flyway history: %v", err)
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
	}
	versions := []string{"1", "2", "3", "4", "4.1", "5", "5.1", "6", "7", "8"}
	for index, migration := range migrations {
		if _, err := pool.Exec(ctx, string(migration.body)); err != nil {
			t.Fatalf("apply command %s: %v", migration.script, err)
		}
		if _, err := pool.Exec(ctx, `insert into flyway_schema_history (
			installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
		values ($1,$2,$3,'SQL',$4,$5,current_user,0,true)`, index+1,
			versions[index], migration.description, migration.script,
			mainResidualFlywayChecksum(migration.body)); err != nil {
			t.Fatalf("record command %s: %v", migration.script, err)
		}
	}
}

func mainResidualFlywayChecksum(contents []byte) int32 {
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

func mainResidualQuarantineFingerprint(t testing.TB, ctx context.Context,
	pool *pgxpool.Pool, aggregateID uuid.UUID) string {
	t.Helper()
	var fingerprint string
	if err := pool.QueryRow(ctx, `select concat_ws('|',consumer_name,aggregate_type,aggregate_id,
		expected_version,observed_version,reason_code,first_event_id,quarantined_at,
		coalesce(reconciled_at::text,''),coalesce(resolution_reason,''),
		coalesce(resolved_by_subject_id::text,''))
		from media_quarantined_aggregate where consumer_name=$1 and aggregate_id=$2`,
		persistence.InventoryOwnerConsumerGroup, aggregateID).Scan(&fingerprint); err != nil {
		t.Fatalf("read command quarantine fingerprint: %v", err)
	}
	return fingerprint
}
