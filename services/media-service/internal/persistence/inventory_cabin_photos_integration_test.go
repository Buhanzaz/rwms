package persistence

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestAuthoritativeInventoryCabinPhotosCleanV1ThroughV13Integration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	warehouseID, cabinID, inventoryID, findingID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	cabinProof := cabinOwnerProofMessage(cabinID, cabinID, warehouseID, 0,
		cabinCreatedEvent, "FREE")
	if result, err := repository.ApplyCabinOwnerMessage(ctx, cabinProof); err != nil ||
		result.Duplicate || result.Quarantined {
		t.Fatalf("apply CABIN proof = %#v, %v", result, err)
	}
	for _, message := range []InventoryFindingMessage{
		inventoryMarker(findingID, warehouseID, 0),
		inventoryProof(findingID, warehouseID, 1, 0, true),
	} {
		if result, err := repository.ApplyInventoryFindingMessage(ctx, message); err != nil ||
			result.Duplicate || result.Quarantined {
			t.Fatalf("apply finding proof = %#v, %v", result, err)
		}
	}

	directCommand := createCommand(cabinID, warehouseID, media.KindImage, 0)
	directCommand.OwnerType = OwnerTypeCabin
	directAsset := seedReadyInventoryCabinPhoto(t, ctx, database, repository, directCommand)
	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo
		set media_generation=1 where cabin_id=$1 and media_id=$2`, cabinID,
		directAsset.ID); err != nil {
		t.Fatalf("seed direct association generation: %v", err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo_library
		set cover_media_id=$1,active_gallery_folder_id=$2,version=1
		where cabin_id=$3`, directAsset.ID, directAsset.FolderID, cabinID); err != nil {
		t.Fatalf("seed direct current folder: %v", err)
	}

	firstCommand := createCommand(findingID, warehouseID, media.KindImage, 0)
	firstCommand.FolderID = uuid.New()
	firstAsset := seedReadyInventoryCabinPhoto(t, ctx, database, repository, firstCommand)
	secondCommand := createCommand(findingID, warehouseID, media.KindImage, 1)
	secondCommand.FolderID = uuid.New()
	secondAsset := seedReadyInventoryCabinPhoto(t, ctx, database, repository, secondCommand)
	completedAt := time.Date(2026, time.August, 19, 8, 31, 15, 745162000, time.UTC)
	command := ApplyInventoryCabinPhotosCommand{
		InventoryID: inventoryID, FindingID: findingID, WarehouseID: warehouseID,
		CabinID: cabinID, CompletedAt: completedAt, SourceRevision: 80,
		FinalPlanVersion: 11, FinalPlanSHA256: hex64('c'),
		CoverMediaID: secondAsset.ID,
		MediaReferences: []InventoryCabinPhotoReference{
			{MediaID: firstAsset.ID, Generation: 1},
			{MediaID: secondAsset.ID, Generation: 1},
		},
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('d'), CorrelationID: uuid.New(),
	}
	result, replayed, changed, err := repository.ApplyInventoryCabinPhotos(ctx, command)
	if err != nil || replayed || !changed || result.InventoryID != inventoryID ||
		result.FindingID != findingID || result.CabinID != cabinID ||
		result.FolderID != inventoryGalleryFolderID(command.RequestSHA256) ||
		result.CoverMediaID != secondAsset.ID || result.PhotoCount != 2 ||
		result.LibraryVersion != 2 || result.CoverGeneration != 1 {
		t.Fatalf("ApplyInventoryCabinPhotos() = %#v replay=%v changed=%v error=%v",
			result, replayed, changed, err)
	}

	assertCurrentInventoryFolderAndArchive(t, ctx, repository, warehouseID, cabinID,
		directAsset, firstAsset, secondAsset, result.FolderID, completedAt)
	assertInventoryMediaRetained(t, ctx, database, findingID, firstAsset.ID,
		secondAsset.ID)

	replayResult, replayed, changed, err := repository.ApplyInventoryCabinPhotos(ctx, command)
	if err != nil || !replayed || changed || replayResult.InventoryID != result.InventoryID ||
		replayResult.FindingID != result.FindingID || replayResult.CabinID != result.CabinID ||
		replayResult.FolderID != result.FolderID || replayResult.CoverMediaID != result.CoverMediaID ||
		replayResult.PhotoCount != result.PhotoCount || replayResult.LibraryVersion != result.LibraryVersion {
		t.Fatalf("exact frozen replay = %#v replay=%v changed=%v error=%v",
			replayResult, replayed, changed, err)
	}
	mismatch := command
	mismatch.RequestSHA256 = hex64('e')
	if _, _, _, err := repository.ApplyInventoryCabinPhotos(ctx, mismatch); !errors.Is(err, ErrIdempotencyMismatch) {
		t.Fatalf("changed key reuse error = %v, want ErrIdempotencyMismatch", err)
	}

	older := command
	older.IdempotencyKey, older.CorrelationID = uuid.New(), uuid.New()
	older.CompletedAt = completedAt.Add(-time.Second)
	older.RequestSHA256 = hex64('f')
	if _, _, _, err := repository.ApplyInventoryCabinPhotos(ctx, older); !errors.Is(err, ErrConflict) {
		t.Fatalf("older completed inventory error = %v, want ErrConflict", err)
	}
	equalDifferent := command
	equalDifferent.IdempotencyKey, equalDifferent.CorrelationID = uuid.New(), uuid.New()
	equalDifferent.FinalPlanVersion++
	equalDifferent.RequestSHA256 = hex64('1')
	if _, _, _, err := repository.ApplyInventoryCabinPhotos(ctx, equalDifferent); !errors.Is(err, ErrConflict) {
		t.Fatalf("equal-time different source error = %v, want ErrConflict", err)
	}

	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo_library
		set cover_media_id=$1,active_gallery_folder_id=$2,version=version+1
		where cabin_id=$3`, directAsset.ID, directAsset.FolderID, cabinID); err != nil {
		t.Fatalf("simulate later task/direct cover: %v", err)
	}
	reassert := command
	reassert.IdempotencyKey, reassert.CorrelationID = uuid.New(), uuid.New()
	reassertResult, replayed, changed, err := repository.ApplyInventoryCabinPhotos(ctx, reassert)
	if err != nil || replayed || !changed || reassertResult.FolderID != result.FolderID ||
		reassertResult.LibraryVersion != 4 {
		t.Fatalf("same-source new-key reassert = %#v replay=%v changed=%v error=%v",
			reassertResult, replayed, changed, err)
	}

	assertInventoryCabinPhotoValidationRollback(t, ctx, database, repository, command,
		directAsset.ID)
	assertInventoryMediaRetained(t, ctx, database, findingID, firstAsset.ID,
		secondAsset.ID)
}

func TestAuthoritativeInventoryCabinPhotosV12ToV13BackfillIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabaseThroughV12(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open V12 database: %v", err)
	}
	cabinID := uuid.MustParse("51000000-0000-4000-8000-000000000001")
	warehouseID := uuid.MustParse("00000000-0000-0000-0000-000000000001")
	mediaID, folderID := uuid.New(), uuid.New()
	if _, err := pool.Exec(ctx, `insert into media_asset (
		media_id,folder_id,owner_type,owner_id,warehouse_id,media_kind,
		original_file_name,original_content_type,source_object_key,
		source_version_id,source_etag,source_checksum_sha256,
		finalized_content_type,finalized_size_bytes,processing_status,
		current_generation,next_generation,sort_order,size_bytes,version)
	values ($1,$2,'CABIN',$3,$4,'IMAGE','upgrade.jpg','image/jpeg',$5,
		'version','etag',$6,'image/jpeg',128,'READY',1,2,0,128,2)`, mediaID,
		folderID, cabinID.String(), warehouseID,
		"media/"+mediaID.String()+"/source/upgrade.jpg", hex64('a')); err != nil {
		pool.Close()
		t.Fatalf("seed V12 media asset: %v", err)
	}
	if _, err := pool.Exec(ctx, `insert into media_cabin_photo (
		cabin_id,media_id,warehouse_id,media_generation,task_board_entry_id,
		association_source,sort_order,attached_at)
	values ($1,$2,$3,1,null,'DIRECT',0,clock_timestamp())`, cabinID, mediaID,
		warehouseID); err != nil {
		pool.Close()
		t.Fatalf("seed V12 cabin photo: %v", err)
	}
	if _, err := pool.Exec(ctx, `insert into media_cabin_photo_library (
		cabin_id,warehouse_id,cover_media_id,version,updated_at)
	values ($1,$2,$3,1,clock_timestamp())`, cabinID, warehouseID, mediaID); err != nil {
		pool.Close()
		t.Fatalf("seed V12 cabin library: %v", err)
	}
	before := readUpgradeAssetEvidence(t, ctx, pool, mediaID)
	started := time.Now()
	if _, err := pool.Exec(ctx, string(mediamigration.V13)); err != nil {
		pool.Close()
		t.Fatalf("apply V13 upgrade: %v", err)
	}
	if _, err := pool.Exec(ctx, `insert into flyway_schema_history (
		installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
	values (15,'13','authoritative inventory cabin photos','SQL',
		'V13__authoritative_inventory_cabin_photos.sql',$1,current_user,$2,true)`,
		flywayChecksum(mediamigration.V13), int(time.Since(started)/time.Millisecond)); err != nil {
		pool.Close()
		t.Fatalf("record V13 history: %v", err)
	}
	after := readUpgradeAssetEvidence(t, ctx, pool, mediaID)
	if before != after {
		pool.Close()
		t.Fatalf("V13 changed media_asset evidence: before=%#v after=%#v", before, after)
	}
	var galleryFolderID, activeFolderID uuid.UUID
	if err := pool.QueryRow(ctx, `select photo.gallery_folder_id,library.active_gallery_folder_id
		from media_cabin_photo photo join media_cabin_photo_library library
		  on library.cabin_id=photo.cabin_id
		where photo.cabin_id=$1 and photo.media_id=$2`, cabinID, mediaID).
		Scan(&galleryFolderID, &activeFolderID); err != nil {
		pool.Close()
		t.Fatalf("read V13 backfill: %v", err)
	}
	pool.Close()
	if galleryFolderID != folderID || activeFolderID != folderID {
		t.Fatalf("V13 folder backfill = gallery:%s active:%s, want %s",
			galleryFolderID, activeFolderID, folderID)
	}
	verified, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open(V13 upgrade) error = %v", err)
	}
	verified.Close()
}

func seedReadyInventoryCabinPhoto(
	t *testing.T,
	ctx context.Context,
	database *Database,
	repository *Repository,
	command CreateUploadCommand,
) AssetRecord {
	t.Helper()
	asset, replayed, err := repository.CreateUpload(ctx, command)
	if err != nil || replayed {
		t.Fatalf("CreateUpload(%s) = %#v replay=%v error=%v", command.OwnerType,
			asset, replayed, err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set
		processing_status='READY',current_generation=1,next_generation=2,version=2,
		source_version_id='source-v1',source_etag='source-etag',
		source_checksum_sha256=$2,finalized_content_type='image/jpeg',
		finalized_size_bytes=128,size_bytes=128 where media_id=$1`,
		asset.ID, command.ChecksumSHA256); err != nil {
		t.Fatalf("mark media READY: %v", err)
	}
	if _, err := database.Pool.Exec(ctx, `insert into media_variant (
		media_id,generation,variant,object_key,object_version_id,content_type,
		size_bytes,width,height,checksum_sha256)
	values ($1,1,'SMALL',$2,'small-v1','image/webp',64,360,240,$3)`,
		asset.ID, "media/"+asset.ID.String()+"/1/small.webp", command.ChecksumSHA256); err != nil {
		t.Fatalf("seed SMALL variant: %v", err)
	}
	asset.Status, asset.Generation, asset.Version = media.StatusReady, 1, 2
	return asset
}

func assertCurrentInventoryFolderAndArchive(
	t *testing.T,
	ctx context.Context,
	repository *Repository,
	warehouseID, cabinID uuid.UUID,
	directAsset, firstAsset, secondAsset AssetRecord,
	inventoryFolderID uuid.UUID,
	completedAt time.Time,
) {
	t.Helper()
	var covers []CabinCoverRecord
	if err := repository.ReadCabinCovers(ctx, warehouseID, []uuid.UUID{cabinID},
		func(records []CabinCoverRecord) error {
			covers = records
			return nil
		}); err != nil || len(covers) != 1 || covers[0].PhotoCount != 2 ||
		covers[0].MediaID != secondAsset.ID || len(covers[0].Previews) != 2 ||
		covers[0].Previews[0].MediaID != firstAsset.ID ||
		covers[0].Previews[1].MediaID != secondAsset.ID {
		t.Fatalf("current inventory cover projection = %#v error=%v", covers, err)
	}
	var presentation []CabinPresentationSnapshotRecord
	if err := repository.ReadCabinPresentationSnapshots(ctx, warehouseID,
		[]uuid.UUID{cabinID}, func(records []CabinPresentationSnapshotRecord) error {
			presentation = records
			return nil
		}); err != nil || len(presentation) != 1 ||
		presentation[0].CoverMediaID == nil ||
		*presentation[0].CoverMediaID != secondAsset.ID ||
		len(presentation[0].Photos) != 2 {
		t.Fatalf("current inventory presentation = %#v error=%v", presentation, err)
	}
	var archive []AssetWithVariants
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeCabin, cabinID.String(),
		warehouseID, 10, nil, func(records []AssetWithVariants) error {
			archive = records
			return nil
		}); err != nil || len(archive) != 3 {
		t.Fatalf("CABIN archive = %#v error=%v", archive, err)
	}
	folders := map[uuid.UUID]int{}
	for _, item := range archive {
		folders[item.Asset.FolderID]++
		if item.Asset.ID == firstAsset.ID || item.Asset.ID == secondAsset.ID {
			if !item.Asset.CreatedAt.Equal(completedAt) {
				t.Fatalf("inventory archive occurrence = %s, want %s",
					item.Asset.CreatedAt, completedAt)
			}
		}
	}
	if folders[directAsset.FolderID] != 1 || folders[inventoryFolderID] != 2 ||
		len(folders) != 2 {
		t.Fatalf("CABIN archive folders = %#v", folders)
	}
}

func assertInventoryCabinPhotoValidationRollback(
	t *testing.T,
	ctx context.Context,
	database *Database,
	repository *Repository,
	base ApplyInventoryCabinPhotosCommand,
	wrongOwnerMediaID uuid.UUID,
) {
	t.Helper()
	var beforeVersion int64
	var beforeReceiptCount, beforeAssociationCount int
	if err := database.Pool.QueryRow(ctx, `select
		(select version from media_cabin_photo_library where cabin_id=$1),
		(select count(*) from media_inventory_cabin_photo_receipt where cabin_id=$1),
		(select count(*) from media_cabin_photo where cabin_id=$1)`, base.CabinID).
		Scan(&beforeVersion, &beforeReceiptCount, &beforeAssociationCount); err != nil {
		t.Fatalf("read rollback baseline: %v", err)
	}
	tests := []struct {
		name   string
		mutate func(*ApplyInventoryCabinPhotosCommand)
	}{
		{name: "warehouse", mutate: func(command *ApplyInventoryCabinPhotosCommand) {
			command.WarehouseID = uuid.New()
		}},
		{name: "generation", mutate: func(command *ApplyInventoryCabinPhotosCommand) {
			command.MediaReferences[0].Generation++
		}},
		{name: "owner", mutate: func(command *ApplyInventoryCabinPhotosCommand) {
			command.CoverMediaID = wrongOwnerMediaID
			command.MediaReferences = []InventoryCabinPhotoReference{{
				MediaID: wrongOwnerMediaID, Generation: 1,
			}}
		}},
		{name: "cover", mutate: func(command *ApplyInventoryCabinPhotosCommand) {
			command.CoverMediaID = uuid.New()
		}},
	}
	for index, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			command := base
			command.MediaReferences = append([]InventoryCabinPhotoReference(nil),
				base.MediaReferences...)
			command.CompletedAt = base.CompletedAt.Add(time.Duration(index+1) * time.Minute)
			command.IdempotencyKey, command.CorrelationID = uuid.New(), uuid.New()
			command.RequestSHA256 = hex64(byte('2' + index))
			test.mutate(&command)
			if _, _, _, err := repository.ApplyInventoryCabinPhotos(ctx, command); !errors.Is(err, ErrConflict) {
				t.Fatalf("validation error = %v, want ErrConflict", err)
			}
		})
	}
	var afterVersion int64
	var afterReceiptCount, afterAssociationCount int
	if err := database.Pool.QueryRow(ctx, `select
		(select version from media_cabin_photo_library where cabin_id=$1),
		(select count(*) from media_inventory_cabin_photo_receipt where cabin_id=$1),
		(select count(*) from media_cabin_photo where cabin_id=$1)`, base.CabinID).
		Scan(&afterVersion, &afterReceiptCount, &afterAssociationCount); err != nil {
		t.Fatalf("read rollback result: %v", err)
	}
	if afterVersion != beforeVersion || afterReceiptCount != beforeReceiptCount ||
		afterAssociationCount != beforeAssociationCount {
		t.Fatalf("validation rollback changed state: version %d->%d receipts %d->%d associations %d->%d",
			beforeVersion, afterVersion, beforeReceiptCount, afterReceiptCount,
			beforeAssociationCount, afterAssociationCount)
	}
}

func assertInventoryMediaRetained(
	t *testing.T,
	ctx context.Context,
	database *Database,
	findingID, firstMediaID, secondMediaID uuid.UUID,
) {
	t.Helper()
	var assetCount, variantCount, deletedCount int
	if err := database.Pool.QueryRow(ctx, `select
		count(*),count(*) filter (where deleted_at is not null),
		(select count(*) from media_variant where media_id in ($1,$2))
		from media_asset where media_id in ($1,$2)
		  and owner_type='INVENTORY_FINDING' and owner_id=$3`, firstMediaID,
		secondMediaID, findingID.String()).Scan(&assetCount, &deletedCount,
		&variantCount); err != nil {
		t.Fatalf("read retained inventory media: %v", err)
	}
	if assetCount != 2 || deletedCount != 0 || variantCount != 2 {
		t.Fatalf("retained inventory media assets=%d deleted=%d variants=%d",
			assetCount, deletedCount, variantCount)
	}
}

// upgradeAssetEvidence captures the exact media_asset columns that V13 must
// leave unchanged while backfilling association-owned gallery state.
type upgradeAssetEvidence struct {
	FolderID        uuid.UUID
	OwnerType       string
	OwnerID         string
	Status          string
	Version         int64
	SourceObjectKey string
}

func readUpgradeAssetEvidence(
	t *testing.T,
	ctx context.Context,
	pool *pgxpool.Pool,
	mediaID uuid.UUID,
) upgradeAssetEvidence {
	t.Helper()
	var evidence upgradeAssetEvidence
	if err := pool.QueryRow(ctx, `select folder_id,owner_type,owner_id,
		processing_status,version,source_object_key from media_asset where media_id=$1`,
		mediaID).Scan(&evidence.FolderID, &evidence.OwnerType, &evidence.OwnerID,
		&evidence.Status, &evidence.Version, &evidence.SourceObjectKey); err != nil {
		t.Fatalf("read upgrade media evidence: %v", err)
	}
	return evidence
}
