package persistence

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestMigratedCabinOwnerBindingsAuthorizeOnlyTheirCanonicalWarehouseIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()

	var total, saintPetersburg, moscow int
	if err := database.Pool.QueryRow(ctx, `select count(*),
		count(*) filter (where warehouse_id='00000000-0000-0000-0000-000000000001'),
		count(*) filter (where warehouse_id='00000000-0000-0000-0000-000000000002')
		from media_owner_binding where owner_type='CABIN' and active`).
		Scan(&total, &saintPetersburg, &moscow); err != nil {
		t.Fatalf("count migrated cabin bindings: %v", err)
	}
	if total != 195 || saintPetersburg != 120 || moscow != 75 {
		t.Fatalf("migrated cabin bindings = total:%d SPB:%d MSK:%d", total, saintPetersburg, moscow)
	}

	repository := NewRepository(database.Pool)
	cabinID := uuid.MustParse("51000000-0000-4000-8000-000000000001")
	warehouseID := uuid.MustParse("00000000-0000-0000-0000-000000000001")
	foreignWarehouseID := uuid.MustParse("00000000-0000-0000-0000-000000000002")
	command := createCommand(cabinID, warehouseID, media.KindImage, 0)
	command.OwnerType = OwnerTypeCabin
	if _, replayed, err := repository.CreateUpload(ctx, command); err != nil || replayed {
		t.Fatalf("CreateUpload(migrated cabin) = replayed:%v error:%v", replayed, err)
	}

	assets, err := repository.ListOwner(ctx, OwnerTypeCabin, cabinID.String(), warehouseID, 10, nil)
	if err != nil || len(assets) != 1 || assets[0].ID != command.MediaID {
		t.Fatalf("ListOwner(migrated cabin) = %#v, %v", assets, err)
	}
	if err := repository.ReadOriginal(ctx, command.MediaID, OwnerTypeCabin, cabinID.String(),
		warehouseID, nil, func(asset AssetRecord, original *VariantRecord) error {
			if asset.ID != command.MediaID || original != nil {
				t.Fatalf("uploading cabin original projection = asset:%#v original:%#v", asset, original)
			}
			return nil
		}); err != nil {
		t.Fatalf("ReadOriginal(migrated cabin): %v", err)
	}

	var uploadingCovers []CabinCoverRecord
	if err := repository.ReadCabinCovers(ctx, warehouseID, []uuid.UUID{cabinID},
		func(records []CabinCoverRecord) error {
			uploadingCovers = records
			return nil
		}); err != nil || len(uploadingCovers) != 0 {
		t.Fatalf("ReadCabinCovers(uploading) = %#v, %v", uploadingCovers, err)
	}
	secondCommand := createCommand(cabinID, warehouseID, media.KindImage, 1)
	secondCommand.OwnerType = OwnerTypeCabin
	if _, replayed, err := repository.CreateUpload(ctx, secondCommand); err != nil || replayed {
		t.Fatalf("CreateUpload(second migrated cabin image) = replayed:%v error:%v", replayed, err)
	}
	withoutSmallCommand := createCommand(cabinID, warehouseID, media.KindImage, 2)
	withoutSmallCommand.OwnerType = OwnerTypeCabin
	if _, replayed, err := repository.CreateUpload(ctx, withoutSmallCommand); err != nil || replayed {
		t.Fatalf("CreateUpload(image without SMALL) = replayed:%v error:%v", replayed, err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set processing_status='READY',
		current_generation=1,next_generation=2,version=3,source_version_id='source-version',
		source_etag='source-etag',source_checksum_sha256=$2,finalized_content_type='image/jpeg',
		finalized_size_bytes=128 where media_id=$1`, command.MediaID, command.ChecksumSHA256); err != nil {
		t.Fatalf("prepare ready cabin cover asset: %v", err)
	}
	for _, ready := range []CreateUploadCommand{secondCommand, withoutSmallCommand} {
		if _, err := database.Pool.Exec(ctx, `update media_asset set processing_status='READY',
			current_generation=1,next_generation=2,version=3,source_version_id='source-version',
			source_etag='source-etag',source_checksum_sha256=$2,finalized_content_type='image/jpeg',
			finalized_size_bytes=128 where media_id=$1`, ready.MediaID, ready.ChecksumSHA256); err != nil {
			t.Fatalf("prepare additional ready cabin asset %s: %v", ready.MediaID, err)
		}
	}
	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo
		set media_generation=1 where cabin_id=$1`, cabinID); err != nil {
		t.Fatalf("project canonical cabin photo associations: %v", err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo_library
		set cover_media_id=$2,active_gallery_folder_id=$3,version=1,
			updated_at=clock_timestamp()
		where cabin_id=$1`, cabinID, command.MediaID, command.FolderID); err != nil {
		t.Fatalf("project canonical cabin cover pointer: %v", err)
	}
	for _, variant := range []struct {
		name   media.Variant
		suffix string
	}{
		{name: media.VariantLarge, suffix: "large"},
		{name: media.VariantMedium, suffix: "medium"},
		{name: media.VariantSmall, suffix: "small"},
	} {
		if _, err := database.Pool.Exec(ctx, `insert into media_variant
			(media_id,generation,variant,object_key,object_version_id,content_type,size_bytes,width,height,checksum_sha256)
			values ($1,1,$2,$3,$4,'image/webp',64,360,240,$5)`, command.MediaID,
			variant.name, "cabin/"+command.MediaID.String()+"/"+variant.suffix,
			"version-"+variant.suffix, command.ChecksumSHA256); err != nil {
			t.Fatalf("insert %s cabin cover variant: %v", variant.name, err)
		}
	}
	for _, variant := range []struct {
		mediaID uuid.UUID
		name    media.Variant
		suffix  string
	}{
		{mediaID: secondCommand.MediaID, name: media.VariantMedium, suffix: "second-medium"},
		{mediaID: secondCommand.MediaID, name: media.VariantSmall, suffix: "second-small"},
		{mediaID: withoutSmallCommand.MediaID, name: media.VariantMedium, suffix: "third-medium"},
	} {
		if _, err := database.Pool.Exec(ctx, `insert into media_variant
			(media_id,generation,variant,object_key,object_version_id,content_type,size_bytes,width,height,checksum_sha256)
			values ($1,1,$2,$3,$4,'image/webp',64,360,240,$5)`, variant.mediaID,
			variant.name, "cabin/"+variant.mediaID.String()+"/"+variant.suffix,
			"version-"+variant.suffix, command.ChecksumSHA256); err != nil {
			t.Fatalf("insert %s additional cabin variant: %v", variant.name, err)
		}
	}
	var readyCovers []CabinCoverRecord
	if err := repository.ReadCabinCovers(ctx, warehouseID, []uuid.UUID{cabinID},
		func(records []CabinCoverRecord) error {
			readyCovers = records
			return nil
		}); err != nil || len(readyCovers) != 1 || readyCovers[0].PhotoCount != 1 ||
		readyCovers[0].MediaID != command.MediaID || readyCovers[0].Generation != 1 ||
		readyCovers[0].Variant == nil || readyCovers[0].Variant.Variant != media.VariantSmall ||
		len(readyCovers[0].Previews) != 1 ||
		readyCovers[0].Previews[0].MediaID != command.MediaID ||
		readyCovers[0].Previews[0].Variant.Variant != media.VariantSmall {
		t.Fatalf("ReadCabinCovers(ready variants) = %#v, %v", readyCovers, err)
	}
	var presentationSnapshots []CabinPresentationSnapshotRecord
	if err := repository.ReadCabinPresentationSnapshots(ctx, warehouseID, []uuid.UUID{cabinID},
		func(records []CabinPresentationSnapshotRecord) error {
			presentationSnapshots = records
			return nil
		}); err != nil || len(presentationSnapshots) != 1 || presentationSnapshots[0].CabinID != cabinID ||
		len(presentationSnapshots[0].Photos) != 1 ||
		presentationSnapshots[0].Photos[0].MediaID != command.MediaID ||
		presentationSnapshots[0].Photos[0].Generation != 1 || presentationSnapshots[0].Photos[0].SortOrder != 0 ||
		!presentationSnapshots[0].Photos[0].HasSmall || !presentationSnapshots[0].Photos[0].HasLarge {
		t.Fatalf("ReadCabinPresentationSnapshots(ready variants) = %#v, %v", presentationSnapshots, err)
	}

	foreignCommand := createCommand(cabinID, foreignWarehouseID, media.KindImage, 1)
	foreignCommand.OwnerType = OwnerTypeCabin
	if _, _, err := repository.CreateUpload(ctx, foreignCommand); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("CreateUpload(cross-warehouse cabin) error = %v, want ErrOwnerProofMissing", err)
	}
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeCabin, cabinID.String(), foreignWarehouseID,
		10, nil, func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("ReadOwnerAssets(cross-warehouse cabin) error = %v, want ErrOwnerProofMissing", err)
	}
	if err := repository.ReadOriginal(ctx, command.MediaID, OwnerTypeCabin, cabinID.String(),
		foreignWarehouseID, nil, func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ReadOriginal(cross-warehouse cabin) error = %v, want ErrNotFound", err)
	}
	if err := repository.ReadCurrentVariant(ctx, command.MediaID, OwnerTypeCabin, cabinID.String(),
		foreignWarehouseID, 1, media.VariantSmall,
		func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ReadCurrentVariant(cross-warehouse cabin) error = %v, want ErrNotFound", err)
	}
	if _, err := repository.GetAssetScoped(ctx, command.MediaID, OwnerTypeCabin, cabinID.String(),
		foreignWarehouseID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("GetAssetScoped(cross-warehouse rotation preflight) error = %v, want ErrNotFound", err)
	}

	unmigratedCabinID := uuid.MustParse("51000000-0000-4000-8000-000000000196")
	unmigratedCommand := createCommand(unmigratedCabinID, warehouseID, media.KindImage, 2)
	unmigratedCommand.OwnerType = OwnerTypeCabin
	if _, _, err := repository.CreateUpload(ctx, unmigratedCommand); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("CreateUpload(unmigrated cabin) error = %v, want ErrOwnerProofMissing", err)
	}
	var mixedCovers []CabinCoverRecord
	if err := repository.ReadCabinCovers(ctx, warehouseID, []uuid.UUID{cabinID, unmigratedCabinID},
		func(records []CabinCoverRecord) error {
			mixedCovers = records
			return nil
		}); err != nil || len(mixedCovers) != 1 || mixedCovers[0].CabinID != cabinID {
		t.Fatalf("ReadCabinCovers(unproved owner omitted) = %#v, %v", mixedCovers, err)
	}
}
