package persistence

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestV1UpgradeQuarantinedAssetsAreRuntimeInvisibleAndDoNotConsumeQuota(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	adminConfiguration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatalf("pgxpool.ParseConfig() error = %v", err)
	}
	adminConfiguration.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeSimpleProtocol
	adminPool, err := pgxpool.NewWithConfig(ctx, adminConfiguration)
	if err != nil {
		t.Fatalf("open migration integration admin pool: %v", err)
	}
	t.Cleanup(adminPool.Close)

	schema := "media_quarantine_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	quotedSchema := pgx.Identifier{schema}.Sanitize()
	if _, err := adminPool.Exec(ctx, "create schema "+quotedSchema); err != nil {
		t.Fatalf("create isolated migration schema: %v", err)
	}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cleanupCancel()
		if _, err := adminPool.Exec(cleanupCtx, "drop schema if exists "+quotedSchema+" cascade"); err != nil {
			t.Errorf("drop isolated migration schema: %v", err)
		}
	})

	runtimeConfiguration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatalf("parse isolated runtime configuration: %v", err)
	}
	runtimeConfiguration.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeSimpleProtocol
	runtimeConfiguration.ConnConfig.RuntimeParams["search_path"] = schema + ",public"
	runtimePool, err := pgxpool.NewWithConfig(ctx, runtimeConfiguration)
	if err != nil {
		t.Fatalf("open isolated runtime pool: %v", err)
	}
	t.Cleanup(runtimePool.Close)

	if _, err := runtimePool.Exec(ctx, string(mediamigration.V1)); err != nil {
		t.Fatalf("apply V1 in isolated schema: %v", err)
	}
	ownerID, warehouseID := uuid.New(), uuid.New()
	quarantinedIDs := make([]uuid.UUID, 100)
	seed, err := runtimePool.Begin(ctx)
	if err != nil {
		t.Fatalf("begin V1 seed transaction: %v", err)
	}
	defer seed.Rollback(ctx)
	for index := range quarantinedIDs {
		mediaID := uuid.New()
		quarantinedIDs[index] = mediaID
		if _, err := seed.Exec(ctx, `insert into media_asset (
			media_id,owner_type,owner_id,warehouse_id,media_kind,
			original_file_name,original_content_type,source_object_key,
			processing_status,current_generation,sort_order,size_bytes,version)
			values ($1,$2,$3,$4,'IMAGE',$5,'image/jpeg',$6,'READY',0,$7,128,1)`,
			mediaID, OwnerTypeInventoryFinding, ownerID.String(), warehouseID,
			fmt.Sprintf("legacy-%03d.jpg", index),
			fmt.Sprintf("media/%s/source/legacy.jpg", mediaID), index); err != nil {
			t.Fatalf("seed V1 asset %d: %v", index, err)
		}
		if _, err := seed.Exec(ctx, `insert into media_variant (
			media_id,generation,variant,object_key,content_type,size_bytes,width,height,checksum_sha256)
			values ($1,0,'ORIGINAL',$2,'image/jpeg',128,10,10,$3)`,
			mediaID, fmt.Sprintf("media/%s/generation/0/original.jpg", mediaID), hex64('a')); err != nil {
			t.Fatalf("seed V1 variant %d: %v", index, err)
		}
	}
	if err := seed.Commit(ctx); err != nil {
		t.Fatalf("commit V1 seed: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V2)); err != nil {
		t.Fatalf("upgrade isolated schema from V1 to V2: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V3)); err != nil {
		t.Fatalf("upgrade isolated schema from V2 to V3: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V4)); err != nil {
		t.Fatalf("upgrade isolated schema from V3 to V4: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V4_1)); err != nil {
		t.Fatalf("prepare isolated V4 schema for V5 folder backfill: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V5)); err != nil {
		t.Fatalf("upgrade isolated schema from V4 to V5: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V5_1)); err != nil {
		t.Fatalf("restore isolated V5 runtime source guard: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V6)); err != nil {
		t.Fatalf("upgrade isolated schema from V5 to V6: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V7)); err != nil {
		t.Fatalf("upgrade isolated schema from V6 to V7: %v", err)
	}
	if _, err := runtimePool.Exec(ctx, string(mediamigration.V8)); err != nil {
		t.Fatalf("upgrade isolated schema from V7 to V8: %v", err)
	}

	var quarantinedCount int
	if err := runtimePool.QueryRow(ctx, `select count(*) from media_recovery_quarantine
		where source_table='media_asset' and reason_code='LEGACY_UNPINNED_VARIANT'`).Scan(&quarantinedCount); err != nil {
		t.Fatalf("count migrated media-asset quarantine records: %v", err)
	}
	if quarantinedCount != len(quarantinedIDs) {
		t.Fatalf("migrated media-asset quarantine records = %d, want %d", quarantinedCount, len(quarantinedIDs))
	}
	var oneItemFolders, distinctFolders int
	if err := runtimePool.QueryRow(ctx, `select count(*) filter (where folder_id=media_id),
		count(distinct folder_id) from media_asset`).Scan(&oneItemFolders, &distinctFolders); err != nil {
		t.Fatalf("read V5 legacy folder backfill: %v", err)
	}
	if oneItemFolders != len(quarantinedIDs) || distinctFolders != len(quarantinedIDs) {
		t.Fatalf("legacy folder backfill = matching:%d distinct:%d, want %d one-item folders",
			oneItemFolders, distinctFolders, len(quarantinedIDs))
	}

	// The assertions above deliberately inspect the V8 quarantine result. The
	// repository below is current runtime code, so finish the immutable upgrade
	// path before using it instead of exercising it against an obsolete schema.
	remainingMigrations := []struct {
		name string
		body []byte
	}{
		{"V9", mediamigration.V9},
		{"V10", mediamigration.V10},
		{"V11", mediamigration.V11},
		{"V12", mediamigration.V12},
		{"V13", mediamigration.V13},
		{"V14", mediamigration.V14},
		{"V15", mediamigration.V15},
		{"V16", mediamigration.V16},
		{"V17", mediamigration.V17},
	}
	for _, migration := range remainingMigrations {
		if _, err := runtimePool.Exec(ctx, string(migration.body)); err != nil {
			t.Fatalf("upgrade isolated schema through %s: %v", migration.name, err)
		}
	}

	repository := NewRepository(runtimePool)
	proof := ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(), BodySHA256: hex64('1'),
		AggregateType: InventoryFindingAggregate, AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(), WarehouseID: warehouseID,
		OwnerRevision: 1, Active: true, RecordedAt: time.Now().UTC(),
	}
	if replayed, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil || replayed {
		t.Fatalf("ApplyValidatedOwnerProof() = %v, %v; want new proof", replayed, err)
	}

	assets, err := repository.ListOwner(ctx, OwnerTypeInventoryFinding, ownerID.String(), warehouseID, 100, nil)
	if err != nil {
		t.Fatalf("ListOwner() before fresh create error = %v", err)
	}
	if len(assets) != 0 {
		t.Fatalf("ListOwner() exposed %d quarantined assets", len(assets))
	}
	if _, err := repository.ListOwner(ctx, OwnerTypeInventoryFinding, ownerID.String(), warehouseID, 100, &quarantinedIDs[0]); !errors.Is(err, ErrConflict) {
		t.Fatalf("ListOwner(quarantined cursor) error = %v, want ErrConflict", err)
	}
	if _, err := repository.GetAsset(ctx, quarantinedIDs[0]); !errors.Is(err, ErrNotFound) {
		t.Fatalf("GetAsset(quarantined) error = %v, want ErrNotFound", err)
	}
	if _, err := repository.GetAssetScoped(ctx, quarantinedIDs[0], OwnerTypeInventoryFinding, ownerID.String(), warehouseID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("GetAssetScoped(quarantined) error = %v, want ErrNotFound", err)
	}
	if _, err := repository.Variants(ctx, quarantinedIDs[0], 0, true); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Variants(quarantined) error = %v, want ErrNotFound", err)
	}
	fresh := createCommand(ownerID, warehouseID, media.KindImage, 101)
	created, replayed, err := repository.CreateUpload(ctx, fresh)
	if err != nil || replayed {
		t.Fatalf("CreateUpload() with 100 quarantined predecessors = %#v, %v, replay=%v", created, err, replayed)
	}
	assets, err = repository.ListOwner(ctx, OwnerTypeInventoryFinding, ownerID.String(), warehouseID, 100, nil)
	if err != nil {
		t.Fatalf("ListOwner() after fresh create error = %v", err)
	}
	if len(assets) != 1 || assets[0].ID != fresh.MediaID {
		t.Fatalf("ListOwner() after fresh create = %#v, want only %s", assets, fresh.MediaID)
	}
}
