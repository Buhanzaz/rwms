package persistence

import (
	"context"
	"errors"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func TestPublicReadsUseOneAuthorizationBearingStatementAndFailClosedWhenRevocationWinsIntegration(
	t *testing.T,
) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	ownerID, warehouseID := uuid.New(), uuid.New()
	proof := ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(), BodySHA256: hex64('7'),
		AggregateType: InventoryFindingAggregate, AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(), WarehouseID: warehouseID,
		OwnerRevision: 1, Active: true, RecordedAt: time.Now().UTC(),
	}
	if replayed, applyErr := repository.ApplyValidatedOwnerProof(ctx, proof); applyErr != nil || replayed {
		t.Fatalf("apply owner proof: replayed=%v error=%v", replayed, applyErr)
	}
	command := createCommand(ownerID, warehouseID, media.KindImage, 0)
	if _, _, createErr := repository.CreateUpload(ctx, command); createErr != nil {
		t.Fatal(createErr)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set
		processing_status='READY',current_generation=1,next_generation=2,version=2,
		source_version_id='source-version',source_etag='source-etag',
		source_checksum_sha256=$2,finalized_content_type='image/jpeg',finalized_size_bytes=128,
		size_bytes=128,updated_at=clock_timestamp()
		where media_id=$1`, command.MediaID, hex64('a')); err != nil {
		t.Fatalf("seed ready asset: %v", err)
	}
	for _, variant := range processedImageVariants(command.MediaID, 1) {
		if _, err := database.Pool.Exec(ctx, `insert into media_variant (
			media_id,generation,variant,object_key,object_version_id,content_type,
			size_bytes,width,height,checksum_sha256)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, command.MediaID, 1,
			variant.Variant, variant.ObjectKey, variant.ObjectVersionID, variant.ContentType,
			variant.SizeBytes, variant.Width, variant.Height, variant.ChecksumSHA256); err != nil {
			t.Fatalf("seed %s variant: %v", variant.Variant, err)
		}
	}

	stableListCalls := 0
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeInventoryFinding, ownerID.String(),
		warehouseID, 10, nil, func(records []AssetWithVariants) error {
			stableListCalls++
			if len(records) != 1 || records[0].Asset.ID != command.MediaID ||
				len(records[0].Variants) != 3 {
				t.Fatalf("stable metadata/variants=%#v", records)
			}
			return nil
		}); err != nil {
		t.Fatalf("stable list read: %v", err)
	}
	stableOriginalCalls := 0
	if err := repository.ReadOriginal(ctx, command.MediaID, OwnerTypeInventoryFinding,
		ownerID.String(), warehouseID, func(asset AssetRecord, original *VariantRecord) error {
			stableOriginalCalls++
			if asset.ID != command.MediaID || original == nil ||
				original.Variant != media.VariantOriginal || original.ObjectVersionID == "" {
				t.Fatalf("stable original asset=%#v original=%#v", asset, original)
			}
			return nil
		}); err != nil {
		t.Fatalf("stable original read: %v", err)
	}
	if stableListCalls != 1 || stableOriginalCalls != 1 {
		t.Fatalf("stable callbacks list=%d original=%d", stableListCalls, stableOriginalCalls)
	}

	revocation, err := database.Pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		t.Fatal(err)
	}
	defer revocation.Rollback(ctx)
	if _, err := revocation.Exec(ctx, `update media_owner_binding set active=false,
		updated_at=clock_timestamp() where owner_type=$1 and owner_id=$2`,
		OwnerTypeInventoryFinding, ownerID.String()); err != nil {
		t.Fatalf("acquire revocation lock: %v", err)
	}

	var leakedInputs atomic.Int32
	listResult := make(chan error, 1)
	originalResult := make(chan error, 1)
	go func() {
		listResult <- repository.ReadOwnerAssets(ctx, OwnerTypeInventoryFinding,
			ownerID.String(), warehouseID, 10, nil, func([]AssetWithVariants) error {
				leakedInputs.Add(1)
				return nil
			})
	}()
	go func() {
		originalResult <- repository.ReadOriginal(ctx, command.MediaID,
			OwnerTypeInventoryFinding, ownerID.String(), warehouseID,
			func(AssetRecord, *VariantRecord) error {
				leakedInputs.Add(1)
				return nil
			})
	}()
	waitForPublicReadLockWait(t, ctx, database, 2)
	if err := revocation.Commit(ctx); err != nil {
		t.Fatalf("commit winning revocation: %v", err)
	}
	listErr := <-listResult
	originalErr := <-originalResult
	if !errors.Is(listErr, ErrOwnerProofMissing) || !errors.Is(originalErr, ErrNotFound) ||
		leakedInputs.Load() != 0 {
		t.Fatalf("revocation-wins results list=%v original=%v leaked callbacks=%d",
			listErr, originalErr, leakedInputs.Load())
	}
	if variants, variantErr := repository.Variants(ctx, command.MediaID, 1, true); !errors.Is(variantErr, ErrNotFound) || len(variants) != 0 {
		t.Fatalf("post-revocation variants=%#v error=%v", variants, variantErr)
	}
}

func waitForPublicReadLockWait(t *testing.T, ctx context.Context, database *Database, expected int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiting int
		if err := database.Pool.QueryRow(ctx, `select count(*) from pg_stat_activity
			where datname=current_database() and wait_event_type='Lock'
			and (query like '/* media_public_owner_read */%'
			 or query like '/* media_public_original_read */%')`).Scan(&waiting); err != nil {
			t.Fatalf("inspect public read lock wait: %v", err)
		}
		if waiting >= expected {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("public reads did not block behind winning revocation")
}
