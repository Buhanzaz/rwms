package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestInventoryOwnerResidualPostgresDatabaseURLTargetsRequestedDatabase(t *testing.T) {
	const databaseName = "rwms_media_task1b_unit_0123456789abcdef"
	databaseURL, err := testsupport.PostgresDatabaseURL(
		"postgresql://media:p%40ss@postgres.example:5432/rwms_media?sslmode=disable&application_name=task1b&dbname=rwms_media",
		databaseName,
	)
	if err != nil {
		t.Fatalf("PostgresDatabaseURL() error = %v", err)
	}
	configuration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatalf("parse targeted database URL: %v", err)
	}
	if configuration.ConnConfig.Database != databaseName {
		t.Fatalf("targeted database = %q, want %q", configuration.ConnConfig.Database, databaseName)
	}
	if configuration.ConnConfig.User != "media" || configuration.ConnConfig.Password != "p@ss" ||
		configuration.ConnConfig.Host != "postgres.example" || configuration.ConnConfig.Port != 5432 ||
		configuration.ConnConfig.RuntimeParams["application_name"] != "task1b" {
		t.Fatal("targeted database URL lost connection settings")
	}
}

func TestInventoryOwnerResidualMigrationGateReal(t *testing.T) {
	environment := testsupport.RequireRealEnvironment(t, testsupport.PostgreSQL)

	t.Run("V10 backfills cabin history and one explicit first READY cover", func(t *testing.T) {
		databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		pool := openResidualPool(t, ctx, databaseURL)
		installResidualMigrations(t, ctx, pool, 11)
		cabinID := uuid.MustParse("51000000-0000-4000-8000-000000000001")
		warehouseID := uuid.MustParse("00000000-0000-0000-0000-000000000001")
		firstID, secondID := uuid.New(), uuid.New()
		for index, fixture := range []struct {
			mediaID   uuid.UUID
			sortOrder int64
		}{
			{mediaID: firstID, sortOrder: 8},
			{mediaID: secondID, sortOrder: 2},
		} {
			checksum := hex64(byte('a' + index))
			if _, err := pool.Exec(ctx, `insert into media_asset (
				media_id,folder_id,owner_type,owner_id,warehouse_id,media_kind,
				original_file_name,original_content_type,source_object_key,
				source_version_id,source_etag,source_checksum_sha256,
				finalized_content_type,finalized_size_bytes,processing_status,
				current_generation,next_generation,sort_order,size_bytes,version)
			values ($1,$1,'CABIN',$2,$3,'IMAGE','backfill.jpg','image/jpeg',$4,
				'version','etag',$5,'image/jpeg',128,'READY',1,2,$6,128,2)`,
				fixture.mediaID, cabinID.String(), warehouseID,
				"media/"+fixture.mediaID.String()+"/source/backfill.jpg",
				checksum, fixture.sortOrder); err != nil {
				pool.Close()
				t.Fatalf("seed V9 CABIN media %s: %v", fixture.mediaID, err)
			}
		}
		applyResidualMigration(t, ctx, pool, 12, "10", "canonical cabin photo library",
			"V10__canonical_cabin_photo_library.sql", mediamigration.V10)
		var photoCount int
		var coverID uuid.UUID
		if err := pool.QueryRow(ctx, `select
			(select count(*) from media_cabin_photo where cabin_id=$1),
			(select cover_media_id from media_cabin_photo_library where cabin_id=$1)`,
			cabinID).Scan(&photoCount, &coverID); err != nil {
			pool.Close()
			t.Fatalf("read V10 cabin backfill: %v", err)
		}
		pool.Close()
		if photoCount != 2 || coverID != secondID {
			t.Fatalf("V10 backfill photoCount=%d cover=%s, want 2 and %s",
				photoCount, coverID, secondID)
		}
	})

	t.Run("clean V1 through V15 repeat and checksum drift", func(t *testing.T) {
		databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		pool := openResidualPool(t, ctx, databaseURL)
		installResidualMigrations(t, ctx, pool, 17)
		pool.Close()

		first, err := Open(ctx, databaseURL)
		if err != nil {
			t.Fatalf("open clean V1 through V15 database: %v", err)
		}
		assertTaskBoardV8ConstraintsValidated(t, ctx, first.Pool)
		first.Close()
		second, err := Open(ctx, databaseURL)
		if err != nil {
			t.Fatalf("repeat schema verification: %v", err)
		}
		if _, err := second.Pool.Exec(ctx, `update flyway_schema_history
			set checksum=checksum+1 where version='5'`); err != nil {
			second.Close()
			t.Fatalf("seed V5 checksum drift: %v", err)
		}
		second.Close()
		drifted, err := Open(ctx, databaseURL)
		if drifted != nil {
			drifted.Close()
		}
		if !errors.Is(err, ErrSchemaNotReady) {
			t.Fatalf("Open() checksum drift error = %v, want ErrSchemaNotReady", err)
		}
	})

	t.Run("V14 backfills existing task-board upload workers as readers", func(t *testing.T) {
		databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		pool := openResidualPool(t, ctx, databaseURL)
		installResidualMigrations(t, ctx, pool, 15)
		entryID, warehouseID, workerID := uuid.New(), uuid.New(), uuid.New()
		if _, err := pool.Exec(ctx, `insert into media_task_board_entry_owner_proof (
			entry_id,warehouse_id,route_index,active,aggregate_version,proof_event_id,
			body_sha256,quarantined,quarantine_reason)
		values ($1,$2,0,true,0,$3,repeat('a',64),false,null)`, entryID, warehouseID,
			uuid.New()); err != nil {
			pool.Close()
			t.Fatalf("seed V13 task-board owner proof: %v", err)
		}
		if _, err := pool.Exec(ctx, `insert into media_task_board_entry_allowed_worker
			(entry_id,worker_id) values ($1,$2)`, entryID, workerID); err != nil {
			pool.Close()
			t.Fatalf("seed V13 task-board worker: %v", err)
		}
		applyResidualMigration(t, ctx, pool, 16, "14", "task board reader audience",
			"V14__task_board_reader_audience.sql", mediamigration.V14)
		var readers int
		if err := pool.QueryRow(ctx, `select count(*)
			from media_task_board_entry_reader_worker
			where entry_id=$1 and worker_id=$2`, entryID, workerID).Scan(&readers); err != nil {
			pool.Close()
			t.Fatalf("read V14 reader backfill: %v", err)
		}
		if readers != 1 {
			pool.Close()
			t.Fatalf("V14 reader backfill count=%d, want 1", readers)
		}
		applyResidualMigration(t, ctx, pool, 17, "15", "inventory finding membership markers",
			"V15__inventory_finding_membership_markers.sql", mediamigration.V15)
		pool.Close()
		database, err := Open(ctx, databaseURL)
		if err != nil {
			t.Fatalf("open V14 reader-backfill database after V15: %v", err)
		}
		database.Close()
	})

	t.Run("V14 to V15 admits membership markers without owner revisions", func(t *testing.T) {
		databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		pool := openResidualPool(t, ctx, databaseURL)
		installResidualMigrations(t, ctx, pool, 16)
		pending, err := Open(ctx, databaseURL)
		if pending != nil {
			pending.Close()
		}
		if !errors.Is(err, ErrSchemaNotReady) {
			pool.Close()
			t.Fatalf("Open(V14) error = %v, want ErrSchemaNotReady until V15", err)
		}
		findingID, warehouseID := uuid.New(), uuid.New()
		markerBeforeV15 := residualFindingMessage(findingID, warehouseID, uuid.New(), 0,
			"inventory.finding.membership-restored.v1", 0, true)
		repository := NewRepository(pool)
		if _, err := repository.ApplyInventoryFindingMessage(ctx, markerBeforeV15); err == nil {
			pool.Close()
			t.Fatal("V14 inbox unexpectedly accepted membership-restored marker")
		} else {
			if !errors.Is(err, ErrConflict) ||
				!strings.Contains(err.Error(), "media_inventory_finding_inbox_check2") {
				pool.Close()
				t.Fatalf("V14 restored-marker error = %v, want inbox check2 violation", err)
			}
		}
		applyResidualMigration(t, ctx, pool, 17, "15", "inventory finding membership markers",
			"V15__inventory_finding_membership_markers.sql", mediamigration.V15)
		var constraintDefinition string
		var constraintValidated bool
		if err := pool.QueryRow(ctx, `select pg_get_constraintdef(oid),convalidated
			from pg_constraint
			where conrelid='media_inventory_finding_inbox'::regclass
			  and conname='media_inventory_finding_inbox_check2'`).
			Scan(&constraintDefinition, &constraintValidated); err != nil {
			pool.Close()
			t.Fatalf("read V15 inbox constraint: %v", err)
		}
		for _, eventType := range []string{
			"inventory.finding.membership-departed.v1",
			"inventory.finding.membership-refreshed.v1",
			"inventory.finding.membership-restored.v1",
		} {
			if !strings.Contains(constraintDefinition, eventType) {
				pool.Close()
				t.Fatalf("V15 constraint omits %s: %s", eventType, constraintDefinition)
			}
		}
		if !constraintValidated {
			pool.Close()
			t.Fatal("V15 inbox event constraint is not validated")
		}
		streamFindingID := uuid.New()
		applyResidualOrdered(t, ctx, repository,
			residualFindingMessage(streamFindingID, warehouseID, uuid.New(), 0,
				"inventory.finding.added.v1", 0, true),
			residualFindingMessage(streamFindingID, warehouseID, uuid.New(), 1,
				InventoryOwnerProofEvent, 0, true),
			residualFindingMessage(streamFindingID, warehouseID, uuid.New(), 2,
				"inventory.finding.membership-departed.v1", 0, false),
			residualFindingMessage(streamFindingID, warehouseID, uuid.New(), 3,
				"inventory.finding.membership-refreshed.v1", 0, true),
			residualFindingMessage(streamFindingID, warehouseID, uuid.New(), 4,
				"inventory.finding.membership-restored.v1", 0, true))
		var markers, markerOwnerRevisions int
		if err := pool.QueryRow(ctx, `select count(*),count(owner_revision)
			from media_inventory_finding_inbox
			where aggregate_id=$1 and event_type like 'inventory.finding.membership-%'`,
			streamFindingID).Scan(&markers, &markerOwnerRevisions); err != nil {
			pool.Close()
			t.Fatalf("read V15 membership inbox rows: %v", err)
		}
		if _, err := pool.Exec(ctx, `update media_inventory_finding_inbox
			set owner_revision=1
			where aggregate_id=$1 and event_type='inventory.finding.membership-restored.v1'`,
			streamFindingID); err == nil {
			pool.Close()
			t.Fatal("V15 membership marker unexpectedly accepted a non-null owner revision")
		} else {
			var constraintError *pgconn.PgError
			if !errors.As(err, &constraintError) ||
				constraintError.ConstraintName != "media_inventory_finding_inbox_check2" {
				pool.Close()
				t.Fatalf("V15 non-null marker owner revision error = %v, want inbox check2 violation", err)
			}
		}
		pool.Close()
		if markers != 3 || markerOwnerRevisions != 0 {
			t.Fatalf("V15 membership rows=%d owner revisions=%d, want 3/0",
				markers, markerOwnerRevisions)
		}
		upgraded, err := Open(ctx, databaseURL)
		if err != nil {
			t.Fatalf("Open(V15 membership-marker upgrade) error = %v", err)
		}
		upgraded.Close()
	})

	t.Run("V2 binding becomes inactive quarantined and public fail closed", func(t *testing.T) {
		databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		pool := openResidualPool(t, ctx, databaseURL)
		installResidualMigrations(t, ctx, pool, 2)
		ownerID, warehouseID, eventID := uuid.New(), uuid.New(), uuid.New()
		if _, err := pool.Exec(ctx, `insert into media_owner_binding (
			owner_type,owner_id,warehouse_id,owner_revision,proof_event_id,
			proof_consumer_name,proof_aggregate_type,proof_aggregate_id,
			proof_aggregate_version,proof_recorded_at,active)
		values ('INVENTORY_FINDING',$1,$2,7,$3,'legacy-fixture-consumer',
			'INVENTORY_FINDING',$4,1,clock_timestamp(),true)`,
			ownerID.String(), warehouseID, eventID, ownerID); err != nil {
			pool.Close()
			t.Fatalf("seed V2 owner binding: %v", err)
		}
		applyResidualMigration(t, ctx, pool, 3, "3", "inventory owner proof",
			"V3__inventory_owner_proof.sql", mediamigration.V3)
		applyResidualMigration(t, ctx, pool, 4, "4", "cabin owner bindings",
			"V4__cabin_owner_bindings.sql", mediamigration.V4)
		applyResidualMigration(t, ctx, pool, 5, "4.1", "prepare legacy photo folder backfill",
			"V4_1__prepare_legacy_photo_folder_backfill.sql", mediamigration.V4_1)
		applyResidualMigration(t, ctx, pool, 6, "5", "media photo folders",
			"V5__media_photo_folders.sql", mediamigration.V5)
		applyResidualMigration(t, ctx, pool, 7, "5.1", "restore runtime source guard",
			"V5_1__restore_runtime_source_guard.sql", mediamigration.V5_1)
		applyResidualMigration(t, ctx, pool, 8, "6", "service owner proofs and soft delete",
			"V6__service_owner_proofs_and_soft_delete.sql", mediamigration.V6)
		applyResidualMigration(t, ctx, pool, 9, "7", "dynamic cabin owner projection",
			"V7__dynamic_cabin_owner_projection.sql", mediamigration.V7)
		applyResidualMigration(t, ctx, pool, 10, "8", "task board worker media",
			"V8__task_board_worker_media.sql", mediamigration.V8)
		applyResidualMigration(t, ctx, pool, 11, "9", "asset import worker",
			"V9__asset_import_worker.sql", mediamigration.V9)
		applyResidualMigration(t, ctx, pool, 12, "10", "canonical cabin photo library",
			"V10__canonical_cabin_photo_library.sql", mediamigration.V10)
		applyResidualMigration(t, ctx, pool, 13, "11", "bounded media processing recovery",
			"V11__bounded_media_processing_recovery.sql", mediamigration.V11)
		applyResidualMigration(t, ctx, pool, 14, "12", "video playback variant",
			"V12__video_playback_variant.sql", mediamigration.V12)
		applyResidualMigration(t, ctx, pool, 15, "13", "authoritative inventory cabin photos",
			"V13__authoritative_inventory_cabin_photos.sql", mediamigration.V13)
		applyResidualMigration(t, ctx, pool, 16, "14", "task board reader audience",
			"V14__task_board_reader_audience.sql", mediamigration.V14)
		applyResidualMigration(t, ctx, pool, 17, "15", "inventory finding membership markers",
			"V15__inventory_finding_membership_markers.sql", mediamigration.V15)
		pool.Close()
		database, err := Open(ctx, databaseURL)
		if err != nil {
			t.Fatalf("open upgraded V15 database: %v", err)
		}
		defer database.Close()
		assertTaskBoardV8ConstraintsValidated(t, ctx, database.Pool)
		var active bool
		var proofAggregateType, proofConsumerName string
		if err := database.Pool.QueryRow(ctx, `select active,proof_aggregate_type,proof_consumer_name
			from media_owner_binding where owner_type='INVENTORY_FINDING' and owner_id=$1`,
			ownerID.String()).Scan(&active, &proofAggregateType, &proofConsumerName); err != nil {
			t.Fatalf("read upgraded V2 binding: %v", err)
		}
		if active || proofAggregateType != InventoryFindingAggregate ||
			proofConsumerName != InventoryOwnerConsumerGroup {
			t.Fatalf("upgraded binding = active:%v aggregate:%s consumer:%s",
				active, proofAggregateType, proofConsumerName)
		}
		assertResidualOpenQuarantine(t, ctx, database.Pool, ownerID,
			"UNVERIFIED_PRE_TASK1B_BINDING")
		repository := NewRepository(database.Pool)
		if _, _, err := repository.CreateUpload(ctx,
			residualCreateCommand(ownerID, warehouseID)); !errors.Is(err, ErrOwnerProofMissing) {
			t.Fatalf("public create against migrated legacy binding error = %v, want fail closed", err)
		}
		marker0 := residualFindingMessage(ownerID, warehouseID, uuid.New(), 0,
			"inventory.finding.added.v1", 0, true)
		proof1 := residualFindingMessage(ownerID, warehouseID, uuid.New(), 1,
			InventoryOwnerProofEvent, 0, true)
		if err := repository.ReconcileInventoryFindingAggregate(ctx, ownerID, -1, uuid.New(),
			"authoritative replacement of unverified V2 binding",
			[]InventoryFindingMessage{marker0, proof1}); err != nil {
			t.Fatalf("replace unverified V2 binding with lower authoritative revision: %v", err)
		}
		var replacementRevision int64
		if err := database.Pool.QueryRow(ctx, `select owner_revision from media_owner_binding
			where owner_type='INVENTORY_FINDING' and owner_id=$1 and active`, ownerID.String()).
			Scan(&replacementRevision); err != nil {
			t.Fatalf("read authoritative legacy replacement: %v", err)
		}
		if replacementRevision != 0 {
			t.Fatalf("authoritative legacy replacement revision = %d, want 0", replacementRevision)
		}
		if _, _, err := repository.CreateUpload(ctx,
			residualCreateCommand(ownerID, warehouseID)); err != nil {
			t.Fatalf("public create after reviewed legacy replacement: %v", err)
		}
	})

	t.Run("nonempty unversioned database is rejected", func(t *testing.T) {
		databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		pool := openResidualPool(t, ctx, databaseURL)
		if _, err := pool.Exec(ctx, `create table preexisting_unversioned_data (
			id bigint primary key, evidence text not null)`); err != nil {
			pool.Close()
			t.Fatal(err)
		}
		pool.Close()
		database, err := Open(ctx, databaseURL)
		if database != nil {
			database.Close()
		}
		if !errors.Is(err, ErrSchemaNotReady) {
			t.Fatalf("Open() unversioned error = %v, want ErrSchemaNotReady", err)
		}
	})
}

func TestInventoryOwnerResidualStreamAndReconciliationGateReal(t *testing.T) {
	environment := testsupport.RequireRealEnvironment(t, testsupport.PostgreSQL)
	databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	pool := openResidualPool(t, ctx, databaseURL)
	installResidualMigrations(t, ctx, pool, 17)
	pool.Close()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open residual stream database: %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	t.Run("only added v0 bootstraps and warehouse remains continuous", func(t *testing.T) {
		warehouseID := uuid.New()
		for _, eventType := range []string{InventoryOwnerProofEvent, "inventory.finding.inspection-saved.v1"} {
			ownerID := uuid.New()
			message := residualFindingMessage(ownerID, warehouseID, uuid.New(), 0, eventType, 0, true)
			result, applyErr := repository.ApplyInventoryFindingMessage(ctx, message)
			if applyErr != nil || !result.Quarantined {
				t.Fatalf("%s v0 result = %#v, %v; want quarantine", eventType, result, applyErr)
			}
			assertResidualOpenQuarantine(t, ctx, database.Pool, ownerID, "INVALID_STREAM_BOOTSTRAP")
		}

		lateAddedOwner := uuid.New()
		applyResidualOrdered(t, ctx, repository,
			residualFindingMessage(lateAddedOwner, warehouseID, uuid.New(), 0,
				"inventory.finding.added.v1", 0, true),
			residualFindingMessage(lateAddedOwner, warehouseID, uuid.New(), 1,
				InventoryOwnerProofEvent, 0, true))
		lateAdded := residualFindingMessage(lateAddedOwner, warehouseID, uuid.New(), 2,
			"inventory.finding.added.v1", 0, true)
		if result, applyErr := repository.ApplyInventoryFindingMessage(ctx, lateAdded); applyErr != nil || !result.Quarantined {
			t.Fatalf("late added result = %#v, %v; want quarantine", result, applyErr)
		}
		assertResidualOpenQuarantine(t, ctx, database.Pool, lateAddedOwner, "DUPLICATE_STREAM_BOOTSTRAP")

		foreignWarehouseOwner := uuid.New()
		applyResidualOrdered(t, ctx, repository, residualFindingMessage(foreignWarehouseOwner,
			warehouseID, uuid.New(), 0, "inventory.finding.added.v1", 0, true))
		foreignWarehouse := residualFindingMessage(foreignWarehouseOwner, uuid.New(), uuid.New(), 1,
			InventoryOwnerProofEvent, 0, true)
		if result, applyErr := repository.ApplyInventoryFindingMessage(ctx, foreignWarehouse); applyErr != nil || !result.Quarantined {
			t.Fatalf("foreign warehouse proof = %#v, %v; want quarantine", result, applyErr)
		}
		assertResidualOpenQuarantine(t, ctx, database.Pool, foreignWarehouseOwner, "WAREHOUSE_CONFLICT")

		lateWarehouseOwner, canonicalWarehouse := uuid.New(), uuid.New()
		applyResidualOrdered(t, ctx, repository,
			residualFindingMessage(lateWarehouseOwner, canonicalWarehouse, uuid.New(), 0,
				"inventory.finding.added.v1", 0, true),
			residualFindingMessage(lateWarehouseOwner, canonicalWarehouse, uuid.New(), 1,
				InventoryOwnerProofEvent, 0, true))
		lateForeignMarker := residualFindingMessage(lateWarehouseOwner, uuid.New(), uuid.New(), 2,
			"inventory.finding.inspection-saved.v1", 0, true)
		if result, applyErr := repository.ApplyInventoryFindingMessage(ctx, lateForeignMarker); applyErr != nil || !result.Quarantined {
			t.Fatalf("late foreign warehouse marker = %#v, %v; want quarantine", result, applyErr)
		}
		assertResidualOpenQuarantine(t, ctx, database.Pool, lateWarehouseOwner, "WAREHOUSE_CONFLICT")
		if _, _, createErr := repository.CreateUpload(ctx,
			residualCreateCommand(lateWarehouseOwner, canonicalWarehouse)); !errors.Is(createErr, ErrOwnerProofMissing) {
			t.Fatalf("active binding after foreign marker error = %v, want fail closed", createErr)
		}
	})

	t.Run("gap blocks later records then exact reviewed batch reconciles", func(t *testing.T) {
		ownerID, warehouseID := uuid.New(), uuid.New()
		marker0 := residualFindingMessage(ownerID, warehouseID, uuid.New(), 0,
			"inventory.finding.added.v1", 0, true)
		missing1 := residualFindingMessage(ownerID, warehouseID, uuid.New(), 1,
			InventoryOwnerProofEvent, 0, true)
		gap2 := residualFindingMessage(ownerID, warehouseID, uuid.New(), 2,
			"inventory.finding.inspection-saved.v1", 0, true)
		later3 := residualFindingMessage(ownerID, warehouseID, uuid.New(), 3,
			InventoryOwnerProofEvent, 1, true)
		applyResidualOrdered(t, ctx, repository, marker0)
		for _, message := range []InventoryFindingMessage{gap2, later3} {
			result, applyErr := repository.ApplyInventoryFindingMessage(ctx, message)
			if applyErr != nil || !result.Quarantined {
				t.Fatalf("quarantine v%d = %#v, %v", message.AggregateVersion, result, applyErr)
			}
		}
		assertResidualCheckpoint(t, ctx, database.Pool, ownerID, 0)
		if _, _, createErr := repository.CreateUpload(ctx,
			residualCreateCommand(ownerID, warehouseID)); !errors.Is(createErr, ErrOwnerProofMissing) {
			t.Fatalf("public create during gap error = %v, want fail closed", createErr)
		}
		if reconcileErr := repository.ReconcileInventoryFindingAggregate(ctx, ownerID, 0, uuid.New(),
			"reviewed exact inventory event-store batch",
			[]InventoryFindingMessage{missing1, gap2, later3}); reconcileErr != nil {
			t.Fatalf("reconcile exact gap batch: %v", reconcileErr)
		}
		assertResidualCheckpoint(t, ctx, database.Pool, ownerID, 3)
		assertResidualInboxRows(t, ctx, database.Pool, ownerID, 4, 4, 0)
		if _, _, createErr := repository.CreateUpload(ctx,
			residualCreateCommand(ownerID, warehouseID)); createErr != nil {
			t.Fatalf("public create after reconciliation: %v", createErr)
		}
	})

	t.Run("regression equal revision conflict inactive and reactivated", func(t *testing.T) {
		regressionOwner, regressionWarehouse := uuid.New(), uuid.New()
		applyResidualOrdered(t, ctx, repository,
			residualFindingMessage(regressionOwner, regressionWarehouse, uuid.New(), 0,
				"inventory.finding.added.v1", 0, true),
			residualFindingMessage(regressionOwner, regressionWarehouse, uuid.New(), 1,
				InventoryOwnerProofEvent, 1, true))
		regression := residualFindingMessage(regressionOwner, regressionWarehouse, uuid.New(), 2,
			InventoryOwnerProofEvent, 0, true)
		if result, applyErr := repository.ApplyInventoryFindingMessage(ctx, regression); applyErr != nil || !result.Quarantined {
			t.Fatalf("aggregate regression = %#v, %v", result, applyErr)
		}
		assertResidualOpenQuarantine(t, ctx, database.Pool, regressionOwner,
			"OWNER_REVISION_REGRESSION")

		conflictOwner, conflictWarehouse := uuid.New(), uuid.New()
		applyResidualOrdered(t, ctx, repository,
			residualFindingMessage(conflictOwner, conflictWarehouse, uuid.New(), 0,
				"inventory.finding.added.v1", 0, true),
			residualFindingMessage(conflictOwner, conflictWarehouse, uuid.New(), 1,
				InventoryOwnerProofEvent, 4, true))
		equalConflict := residualFindingMessage(conflictOwner, conflictWarehouse, uuid.New(), 2,
			InventoryOwnerProofEvent, 4, false)
		if result, applyErr := repository.ApplyInventoryFindingMessage(ctx, equalConflict); applyErr != nil || !result.Quarantined {
			t.Fatalf("equal owner revision conflict = %#v, %v", result, applyErr)
		}
		assertResidualOpenQuarantine(t, ctx, database.Pool, conflictOwner, "OWNER_REVISION_CONFLICT")

		lifecycleOwner, lifecycleWarehouse := uuid.New(), uuid.New()
		applyResidualOrdered(t, ctx, repository,
			residualFindingMessage(lifecycleOwner, lifecycleWarehouse, uuid.New(), 0,
				"inventory.finding.added.v1", 0, true),
			residualFindingMessage(lifecycleOwner, lifecycleWarehouse, uuid.New(), 1,
				InventoryOwnerProofEvent, 0, true),
			residualFindingMessage(lifecycleOwner, lifecycleWarehouse, uuid.New(), 2,
				InventoryOwnerProofEvent, 1, false))
		if _, _, createErr := repository.CreateUpload(ctx,
			residualCreateCommand(lifecycleOwner, lifecycleWarehouse)); !errors.Is(createErr, ErrOwnerProofMissing) {
			t.Fatalf("inactive binding create error = %v, want ErrOwnerProofMissing", createErr)
		}
		applyResidualOrdered(t, ctx, repository,
			residualFindingMessage(lifecycleOwner, lifecycleWarehouse, uuid.New(), 3,
				InventoryOwnerProofEvent, 2, true))
		if _, _, createErr := repository.CreateUpload(ctx,
			residualCreateCommand(lifecycleOwner, lifecycleWarehouse)); createErr != nil {
			t.Fatalf("reactivated binding create error: %v", createErr)
		}
		assertResidualCheckpoint(t, ctx, database.Pool, lifecycleOwner, 3)
		assertResidualInboxRows(t, ctx, database.Pool, lifecycleOwner, 4, 4, 0)
	})

	t.Run("cross aggregate event id conflict quarantines both and preserves evidence", func(t *testing.T) {
		priorOwner, incomingOwner := uuid.New(), uuid.New()
		priorWarehouse, incomingWarehouse := uuid.New(), uuid.New()
		sharedEventID := uuid.New()
		applyResidualOrdered(t, ctx, repository,
			residualFindingMessage(priorOwner, priorWarehouse, uuid.New(), 0,
				"inventory.finding.added.v1", 0, true),
			residualFindingMessage(incomingOwner, incomingWarehouse, uuid.New(), 0,
				"inventory.finding.added.v1", 0, true))
		prior := residualFindingMessage(priorOwner, priorWarehouse, sharedEventID, 1,
			InventoryOwnerProofEvent, 0, true)
		incoming := residualFindingMessage(incomingOwner, incomingWarehouse, sharedEventID, 1,
			InventoryOwnerProofEvent, 0, true)
		applyResidualOrdered(t, ctx, repository, prior)
		result, applyErr := repository.ApplyInventoryFindingMessage(ctx, incoming)
		if applyErr != nil || !result.Quarantined {
			t.Fatalf("cross aggregate event ID conflict = %#v, %v", result, applyErr)
		}
		assertResidualOpenQuarantine(t, ctx, database.Pool, priorOwner, "EVENT_ID_CONFLICT")
		assertResidualOpenQuarantine(t, ctx, database.Pool, incomingOwner, "EVENT_ID_CONFLICT")
		var priorAggregateID, recordedIncomingID uuid.UUID
		var priorHash, incomingHash string
		if err := database.Pool.QueryRow(ctx, `select prior_aggregate_id,incoming_aggregate_id,
			prior_body_sha256,incoming_body_sha256
			from media_inventory_finding_event_conflict
			where consumer_name=$1 and event_id=$2`, InventoryOwnerConsumerGroup,
			sharedEventID).Scan(&priorAggregateID, &recordedIncomingID, &priorHash, &incomingHash); err != nil {
			t.Fatalf("read durable event ID conflict: %v", err)
		}
		if priorAggregateID != priorOwner || recordedIncomingID != incomingOwner ||
			priorHash != prior.BodySHA256 || incomingHash != incoming.BodySHA256 {
			t.Fatalf("event ID conflict evidence = prior:%s/%s incoming:%s/%s",
				priorAggregateID, priorHash, recordedIncomingID, incomingHash)
		}
		var inboxAggregate uuid.UUID
		var inboxHash, outcome string
		if err := database.Pool.QueryRow(ctx, `select aggregate_id,body_sha256,outcome
			from media_inventory_finding_inbox where consumer_name=$1 and event_id=$2`,
			InventoryOwnerConsumerGroup, sharedEventID).Scan(&inboxAggregate, &inboxHash, &outcome); err != nil {
			t.Fatal(err)
		}
		if inboxAggregate != priorOwner || inboxHash != prior.BodySHA256 || outcome != "APPLIED" {
			t.Fatalf("prior exact inbox was replaced: aggregate=%s hash=%s outcome=%s",
				inboxAggregate, inboxHash, outcome)
		}
		if _, _, createErr := repository.CreateUpload(ctx,
			residualCreateCommand(priorOwner, priorWarehouse)); !errors.Is(createErr, ErrOwnerProofMissing) {
			t.Fatalf("active prior binding during conflict error = %v, want fail closed", createErr)
		}

		reviewerID := uuid.New()
		if reconcileErr := repository.ReconcileInventoryFindingConflict(ctx, priorOwner, 1,
			reviewerID, "authoritative prior bytes verified", prior); reconcileErr != nil {
			t.Fatalf("verify authoritative prior conflict bytes: %v", reconcileErr)
		}
		if _, _, createErr := repository.CreateUpload(ctx,
			residualCreateCommand(priorOwner, priorWarehouse)); !errors.Is(createErr, ErrOwnerProofMissing) {
			t.Fatalf("verified prior binding must remain inactive until explicit reactivation: %v",
				createErr)
		}
		assertResidualOpenQuarantine(t, ctx, database.Pool, incomingOwner, "EVENT_ID_CONFLICT")
		var priorResolvedBy uuid.UUID
		var priorResolutionReason string
		if err := database.Pool.QueryRow(ctx, `select prior_resolved_by_subject_id,
			prior_resolution_reason from media_inventory_finding_event_conflict
			where consumer_name=$1 and event_id=$2 and prior_aggregate_id=$3`,
			InventoryOwnerConsumerGroup, sharedEventID, priorOwner).
			Scan(&priorResolvedBy, &priorResolutionReason); err != nil {
			t.Fatalf("read prior conflict resolution evidence: %v", err)
		}
		if priorResolvedBy != reviewerID || priorResolutionReason != "authoritative prior bytes verified" {
			t.Fatalf("prior conflict resolution = reviewer:%s reason:%q",
				priorResolvedBy, priorResolutionReason)
		}

		replacement := residualFindingMessage(incomingOwner, incomingWarehouse, uuid.New(), 1,
			InventoryOwnerProofEvent, 0, true)
		if reconcileErr := repository.ReconcileInventoryFindingAggregate(ctx, incomingOwner, 0,
			uuid.New(), "authoritative incoming event-id replacement",
			[]InventoryFindingMessage{replacement}); reconcileErr != nil {
			t.Fatalf("reconcile incoming conflict replacement: %v", reconcileErr)
		}
		assertResidualCheckpoint(t, ctx, database.Pool, incomingOwner, 1)
		if _, _, createErr := repository.CreateUpload(ctx,
			residualCreateCommand(incomingOwner, incomingWarehouse)); createErr != nil {
			t.Fatalf("incoming binding after replacement reconciliation: %v", createErr)
		}
	})
}

func openResidualPool(t testing.TB, ctx context.Context, databaseURL string) *pgxpool.Pool {
	t.Helper()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open residual PostgreSQL pool: %v", err)
	}
	if _, err := pool.Exec(ctx, `create extension if not exists pgcrypto`); err != nil {
		pool.Close()
		t.Fatalf("enable pgcrypto in isolated database: %v", err)
	}
	return pool
}

func installResidualMigrations(t testing.TB, ctx context.Context, pool *pgxpool.Pool, through int) {
	t.Helper()
	if _, err := pool.Exec(ctx, `create table flyway_schema_history (
		installed_rank integer not null primary key,
		version varchar(50), description varchar(200) not null, type varchar(20) not null,
		script varchar(1000) not null, checksum integer, installed_by varchar(100) not null,
		installed_on timestamp not null default now(), execution_time integer not null,
		success boolean not null)`); err != nil {
		t.Fatalf("create Flyway history: %v", err)
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
		{"task board reader audience", "V14__task_board_reader_audience.sql", mediamigration.V14},
		{"inventory finding membership markers", "V15__inventory_finding_membership_markers.sql", mediamigration.V15},
	}
	for index := 0; index < through; index++ {
		migration := migrations[index]
		versions := []string{"1", "2", "3", "4", "4.1", "5", "5.1", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"}
		applyResidualMigration(t, ctx, pool, index+1, versions[index], migration.description,
			migration.script, migration.body)
	}
}

func applyResidualMigration(t testing.TB, ctx context.Context, pool *pgxpool.Pool,
	installedRank int, version, description, script string, body []byte) {
	t.Helper()
	started := time.Now()
	if _, err := pool.Exec(ctx, string(body)); err != nil {
		t.Fatalf("apply %s: %v", script, err)
	}
	if _, err := pool.Exec(ctx, `insert into flyway_schema_history (
		installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
	values ($1,$2,$3,'SQL',$4,$5,current_user,$6,true)`, installedRank, version,
		description, script, flywayChecksum(body), int(time.Since(started)/time.Millisecond)); err != nil {
		t.Fatalf("record %s history: %v", script, err)
	}
}

func assertTaskBoardV8ConstraintsValidated(t testing.TB, ctx context.Context, pool *pgxpool.Pool) {
	t.Helper()
	for _, name := range []string{
		"media_asset_task_board_client_reference_check",
		"media_asset_created_by_actor_check",
	} {
		var validated bool
		if err := pool.QueryRow(ctx, `select convalidated from pg_constraint
			where conrelid='media_asset'::regclass and conname=$1`, name).Scan(&validated); err != nil {
			t.Fatalf("read V8 constraint %s: %v", name, err)
		}
		if !validated {
			t.Fatalf("V8 constraint %s remains NOT VALID", name)
		}
	}
}

func residualFindingMessage(ownerID, warehouseID, eventID uuid.UUID, version int64,
	eventType string, ownerRevision int64, active bool) InventoryFindingMessage {
	body := []byte(fmt.Sprintf("%s:%s:%s:%d:%s:%d:%t", eventID, ownerID, warehouseID,
		version, eventType, ownerRevision, active))
	sum := sha256.Sum256(body)
	message := InventoryFindingMessage{
		EventID: eventID, BodySHA256: hex.EncodeToString(sum[:]), WireBody: body,
		Topic: InventorySessionTopic, EventType: eventType,
		AggregateType: InventoryFindingAggregate, AggregateID: ownerID,
		AggregateVersion: version, RecordKey: ownerID, WarehouseID: warehouseID,
		RecordedAt: time.Now().UTC(),
	}
	if eventType == InventoryOwnerProofEvent {
		message.Proof = &InventoryOwnerProof{
			OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID, WarehouseID: warehouseID,
			OwnerRevision: ownerRevision, Active: active,
		}
	}
	return message
}

func applyResidualOrdered(t testing.TB, ctx context.Context, repository *Repository,
	messages ...InventoryFindingMessage) {
	t.Helper()
	for _, message := range messages {
		result, err := repository.ApplyInventoryFindingMessage(ctx, message)
		if err != nil || result.Duplicate || result.Quarantined {
			t.Fatalf("apply ordered inventory event v%d/%s = %#v, %v",
				message.AggregateVersion, message.EventType, result, err)
		}
	}
}

func assertResidualOpenQuarantine(t testing.TB, ctx context.Context, pool *pgxpool.Pool,
	aggregateID uuid.UUID, reason string) {
	t.Helper()
	var actualReason string
	var reconciledAt *time.Time
	if err := pool.QueryRow(ctx, `select reason_code,reconciled_at
		from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2`,
		InventoryOwnerConsumerGroup, aggregateID).Scan(&actualReason, &reconciledAt); err != nil {
		t.Fatalf("read aggregate quarantine %s: %v", aggregateID, err)
	}
	if actualReason != reason || reconciledAt != nil {
		t.Fatalf("quarantine %s = reason:%s reconciled:%v, want %s/open",
			aggregateID, actualReason, reconciledAt, reason)
	}
}

func assertResidualCheckpoint(t testing.TB, ctx context.Context, pool *pgxpool.Pool,
	aggregateID uuid.UUID, version int64) {
	t.Helper()
	var actual int64
	if err := pool.QueryRow(ctx, `select aggregate_version
		from media_consumer_aggregate_checkpoint
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2`,
		InventoryOwnerConsumerGroup, aggregateID).Scan(&actual); err != nil {
		t.Fatalf("read checkpoint %s: %v", aggregateID, err)
	}
	if actual != version {
		t.Fatalf("checkpoint %s = %d, want %d", aggregateID, actual, version)
	}
}

func assertResidualInboxRows(t testing.TB, ctx context.Context, pool *pgxpool.Pool,
	aggregateID uuid.UUID, total, applied, quarantined int) {
	t.Helper()
	var actualTotal, actualApplied, actualQuarantined int
	if err := pool.QueryRow(ctx, `select count(*),
		count(*) filter (where outcome='APPLIED'),
		count(*) filter (where outcome='QUARANTINED')
		from media_inventory_finding_inbox
		where consumer_name=$1 and aggregate_id=$2`, InventoryOwnerConsumerGroup,
		aggregateID).Scan(&actualTotal, &actualApplied, &actualQuarantined); err != nil {
		t.Fatalf("read inbox rows %s: %v", aggregateID, err)
	}
	if actualTotal != total || actualApplied != applied || actualQuarantined != quarantined {
		t.Fatalf("inbox %s = total:%d applied:%d quarantined:%d, want %d/%d/%d",
			aggregateID, actualTotal, actualApplied, actualQuarantined, total, applied, quarantined)
	}
}

func residualCreateCommand(ownerID, warehouseID uuid.UUID) CreateUploadCommand {
	mediaID := uuid.New()
	return CreateUploadCommand{
		MediaID: mediaID, UploadSessionID: uuid.New(), SubjectID: uuid.New(),
		IdempotencyKey: uuid.New(), RequestSHA256: stringsOf('5', 64),
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(), WarehouseID: warehouseID,
		Kind: media.KindImage, FileName: "residual.jpg", ContentType: "image/jpeg",
		ContentLength: 128, ChecksumSHA256: stringsOf('a', 64), SortOrder: 0,
		SourceObjectKey: "media/" + mediaID.String() + "/source/residual.jpg",
		UploadExpiresAt: time.Now().UTC().Add(time.Hour), CorrelationID: uuid.New(),
	}
}

func stringsOf(value byte, count int) string {
	buffer := make([]byte, count)
	for index := range buffer {
		buffer[index] = value
	}
	return string(buffer)
}
