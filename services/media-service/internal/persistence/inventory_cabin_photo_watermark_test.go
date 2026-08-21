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

func TestValidateInventoryCabinPhotoWatermarkOrder(t *testing.T) {
	completedAt := time.Date(2026, time.August, 19, 8, 31, 15, 745162000, time.UTC)
	inventoryID, findingID := uuid.New(), uuid.New()
	command := ApplyInventoryCabinPhotosCommand{
		InventoryID:      inventoryID,
		FindingID:        findingID,
		CompletedAt:      completedAt,
		FinalPlanVersion: 11,
		RequestSHA256:    hex64('a'),
	}

	tests := []struct {
		name             string
		mutate           func(*ApplyInventoryCabinPhotosCommand)
		watermarkSHA     string
		inventoryID      uuid.UUID
		findingID        uuid.UUID
		finalPlanVersion int64
		completedAt      time.Time
		wantCorrection   bool
		wantConflict     bool
	}{
		{
			name:             "exact request replay",
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      inventoryID,
			findingID:        findingID,
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
		},
		{
			name: "corrected plan",
			mutate: func(value *ApplyInventoryCabinPhotosCommand) {
				value.FinalPlanVersion++
				value.RequestSHA256 = hex64('b')
			},
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      inventoryID,
			findingID:        findingID,
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
			wantCorrection:   true,
		},
		{
			name: "older completion",
			mutate: func(value *ApplyInventoryCabinPhotosCommand) {
				value.CompletedAt = completedAt.Add(-time.Microsecond)
			},
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      inventoryID,
			findingID:        findingID,
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
			wantConflict:     true,
		},
		{
			name: "lower plan version even with the prior request hash",
			mutate: func(value *ApplyInventoryCabinPhotosCommand) {
				value.FinalPlanVersion--
			},
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      inventoryID,
			findingID:        findingID,
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
			wantConflict:     true,
		},
		{
			name: "exact request hash from different inventory",
			mutate: func(value *ApplyInventoryCabinPhotosCommand) {
				value.InventoryID = uuid.New()
			},
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      inventoryID,
			findingID:        findingID,
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
			wantConflict:     true,
		},
		{
			name: "same plan version with different request",
			mutate: func(value *ApplyInventoryCabinPhotosCommand) {
				value.RequestSHA256 = hex64('b')
			},
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      inventoryID,
			findingID:        findingID,
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
			wantConflict:     true,
		},
		{
			name: "corrected plan from different inventory",
			mutate: func(value *ApplyInventoryCabinPhotosCommand) {
				value.FinalPlanVersion++
				value.RequestSHA256 = hex64('b')
			},
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      uuid.New(),
			findingID:        findingID,
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
			wantConflict:     true,
		},
		{
			name: "corrected plan from different finding",
			mutate: func(value *ApplyInventoryCabinPhotosCommand) {
				value.FinalPlanVersion++
				value.RequestSHA256 = hex64('b')
			},
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      inventoryID,
			findingID:        uuid.New(),
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
			wantConflict:     true,
		},
		{
			name: "newer completion starts an independent watermark order",
			mutate: func(value *ApplyInventoryCabinPhotosCommand) {
				value.CompletedAt = completedAt.Add(time.Microsecond)
				value.InventoryID = uuid.New()
				value.FindingID = uuid.New()
				value.FinalPlanVersion = 1
				value.RequestSHA256 = hex64('b')
			},
			watermarkSHA:     command.RequestSHA256,
			inventoryID:      inventoryID,
			findingID:        findingID,
			finalPlanVersion: command.FinalPlanVersion,
			completedAt:      completedAt,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			candidate := command
			if test.mutate != nil {
				test.mutate(&candidate)
			}
			corrected, err := validateInventoryCabinPhotoWatermarkOrder(candidate,
				test.completedAt, test.watermarkSHA, test.inventoryID,
				test.findingID, test.finalPlanVersion)
			if test.wantConflict && !errors.Is(err, ErrConflict) {
				t.Fatalf("error = %v, want ErrConflict", err)
			}
			if !test.wantConflict && err != nil {
				t.Fatalf("error = %v, want nil", err)
			}
			if corrected != test.wantCorrection {
				t.Fatalf("corrected = %v, want %v", corrected, test.wantCorrection)
			}
		})
	}
}

func TestCorrectedInventoryCabinPhotoPlanIntegration(t *testing.T) {
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

	warehouseID, cabinID := uuid.New(), uuid.New()
	inventoryID, findingID := uuid.New(), uuid.New()
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

	firstCommand := createCommand(findingID, warehouseID, media.KindImage, 0)
	firstCommand.FolderID = uuid.New()
	firstAsset := seedReadyInventoryCabinPhoto(t, ctx, database, repository,
		firstCommand)
	secondCommand := createCommand(findingID, warehouseID, media.KindImage, 1)
	secondCommand.FolderID = uuid.New()
	secondAsset := seedReadyInventoryCabinPhoto(t, ctx, database, repository,
		secondCommand)
	completedAt := time.Date(2026, time.August, 19, 8, 31, 15, 745162000,
		time.UTC)
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
	initial, replayed, changed, err := repository.ApplyInventoryCabinPhotos(ctx, command)
	if err != nil || replayed || !changed {
		t.Fatalf("initial publication = %#v replay=%v changed=%v error=%v",
			initial, replayed, changed, err)
	}
	thirdCommand := createCommand(findingID, warehouseID, media.KindImage, 2)
	thirdCommand.FolderID = uuid.New()
	thirdAsset := seedReadyInventoryCabinPhoto(t, ctx, database, repository,
		thirdCommand)
	for _, message := range []InventoryFindingMessage{
		inventoryProof(findingID, warehouseID, 2, 1, false),
		inventoryFindingMessage(findingID, warehouseID, 3,
			"inventory.finding.membership-restored.v1", nil),
	} {
		if projection, err := repository.ApplyInventoryFindingMessage(ctx, message); err != nil ||
			projection.Duplicate || projection.Quarantined {
			t.Fatalf("close/restore finding proof = %#v, %v", projection, err)
		}
	}
	if _, _, err := repository.CreateUpload(ctx,
		createCommand(findingID, warehouseID, media.KindImage, 3)); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("restored completed finding upload error = %v, want ErrOwnerProofMissing", err)
	}

	corrected := command
	corrected.IdempotencyKey, corrected.CorrelationID = uuid.New(), uuid.New()
	corrected.FinalPlanVersion = 12
	corrected.FinalPlanSHA256 = hex64('e')
	corrected.RequestSHA256 = hex64('f')
	result, replayed, changed, err := repository.ApplyInventoryCabinPhotos(ctx, corrected)
	if err != nil || replayed || changed || result.FolderID != initial.FolderID ||
		result.LibraryVersion != initial.LibraryVersion {
		t.Fatalf("corrected publication = %#v replay=%v changed=%v error=%v",
			result, replayed, changed, err)
	}
	assertCorrectedInventoryCabinPhotoSource(t, ctx, database, corrected,
		initial.FolderID, 2)

	reassert := corrected
	reassert.IdempotencyKey, reassert.CorrelationID = uuid.New(), uuid.New()
	reassertResult, replayed, changed, err := repository.ApplyInventoryCabinPhotos(ctx,
		reassert)
	if err != nil || replayed || changed || reassertResult.FolderID != initial.FolderID ||
		reassertResult.LibraryVersion != initial.LibraryVersion {
		t.Fatalf("corrected exact-source reassert = %#v replay=%v changed=%v error=%v",
			reassertResult, replayed, changed, err)
	}
	changedGeneration := corrected
	changedGeneration.IdempotencyKey, changedGeneration.CorrelationID =
		uuid.New(), uuid.New()
	changedGeneration.FinalPlanVersion = 13
	changedGeneration.FinalPlanSHA256 = hex64('1')
	changedGeneration.RequestSHA256 = hex64('2')
	changedGeneration.MediaReferences = append(
		[]InventoryCabinPhotoReference(nil), corrected.MediaReferences...)
	changedGeneration.MediaReferences[0].Generation++
	if _, _, _, err := repository.ApplyInventoryCabinPhotos(ctx,
		changedGeneration); !errors.Is(err, ErrConflict) {
		t.Fatalf("changed corrected generation error = %v, want ErrConflict", err)
	}
	assertCorrectedInventoryCabinPhotoSource(t, ctx, database, corrected,
		initial.FolderID, 2)

	changedComposition := corrected
	changedComposition.IdempotencyKey, changedComposition.CorrelationID =
		uuid.New(), uuid.New()
	changedComposition.FinalPlanVersion = 13
	changedComposition.FinalPlanSHA256 = hex64('3')
	changedComposition.RequestSHA256 = hex64('4')
	changedComposition.MediaReferences = append(
		append([]InventoryCabinPhotoReference(nil), corrected.MediaReferences...),
		InventoryCabinPhotoReference{MediaID: thirdAsset.ID, Generation: 1})
	if _, _, _, err := repository.ApplyInventoryCabinPhotos(ctx,
		changedComposition); !errors.Is(err, ErrConflict) {
		t.Fatalf("changed corrected photo identity error = %v, want ErrConflict", err)
	}
	assertCorrectedInventoryCabinPhotoSource(t, ctx, database, corrected,
		initial.FolderID, 2)
}

func assertCorrectedInventoryCabinPhotoSource(
	t *testing.T,
	ctx context.Context,
	database *Database,
	command ApplyInventoryCabinPhotosCommand,
	folderID uuid.UUID,
	wantPhotoCount int,
) {
	t.Helper()
	var associationCount, exactAssociationCount int
	if err := database.Pool.QueryRow(ctx, `select count(*),count(*) filter (
		where gallery_folder_id=$2 and association_source='INVENTORY'
			and inventory_id=$3 and inventory_finding_id=$4
			and inventory_completed_at=$5 and inventory_source_revision=$6
			and inventory_final_plan_version=$7
			and inventory_final_plan_sha256=$8)
		from media_cabin_photo where cabin_id=$1`, command.CabinID, folderID,
		command.InventoryID, command.FindingID, command.CompletedAt,
		command.SourceRevision, command.FinalPlanVersion,
		command.FinalPlanSHA256).Scan(&associationCount, &exactAssociationCount); err != nil {
		t.Fatalf("read corrected associations: %v", err)
	}
	var watermarkRequestSHA, watermarkPlanSHA string
	var watermarkPlanVersion int64
	var watermarkFolderID uuid.UUID
	if err := database.Pool.QueryRow(ctx, `select request_sha256,final_plan_version,
		final_plan_sha256,gallery_folder_id
		from media_inventory_cabin_photo_watermark where cabin_id=$1`,
		command.CabinID).Scan(&watermarkRequestSHA, &watermarkPlanVersion,
		&watermarkPlanSHA, &watermarkFolderID); err != nil {
		t.Fatalf("read corrected watermark: %v", err)
	}
	if associationCount != wantPhotoCount || exactAssociationCount != wantPhotoCount ||
		watermarkRequestSHA != command.RequestSHA256 ||
		watermarkPlanVersion != command.FinalPlanVersion ||
		watermarkPlanSHA != command.FinalPlanSHA256 || watermarkFolderID != folderID {
		t.Fatalf("corrected source associations=%d exact=%d watermark=%s/%d/%s/%s",
			associationCount, exactAssociationCount, watermarkRequestSHA,
			watermarkPlanVersion, watermarkPlanSHA, watermarkFolderID)
	}
}
