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

func TestCabinTaskEvidenceAtomicallyReplacesOneCoverIntegration(t *testing.T) {
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
	repository := NewRepository(database.Pool)

	cabinID := uuid.MustParse("51000000-0000-4000-8000-000000000001")
	warehouseID := uuid.MustParse("00000000-0000-0000-0000-000000000001")
	direct := createCommand(cabinID, warehouseID, media.KindImage, 0)
	direct.OwnerType = OwnerTypeCabin
	directAsset, replayed, err := repository.CreateUpload(ctx, direct)
	if err != nil || replayed {
		t.Fatalf("CreateUpload(direct cover) = %#v, %v, %v", directAsset, replayed, err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set
		processing_status='READY',current_generation=1,next_generation=2,version=2,
		source_version_id='direct-v1',source_etag='direct-etag',
		source_checksum_sha256=$2,finalized_content_type='image/jpeg',
		finalized_size_bytes=128,size_bytes=128 where media_id=$1`,
		directAsset.ID, direct.ChecksumSHA256); err != nil {
		t.Fatalf("seed direct canonical cover: %v", err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo
		set media_generation=1 where media_id=$1`, directAsset.ID); err != nil {
		t.Fatalf("seed direct photo generation: %v", err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo_library
		set cover_media_id=$1,version=1 where cabin_id=$2`,
		directAsset.ID, cabinID); err != nil {
		t.Fatalf("seed direct cover pointer: %v", err)
	}

	entryID, workerID, subjectID := uuid.New(), uuid.New(), uuid.New()
	proof := taskBoardOwnerProofMessage(t, entryID, warehouseID, 0, true,
		[]uuid.UUID{workerID}, nil)
	if _, err := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof); err != nil {
		t.Fatalf("apply task owner proof: %v", err)
	}
	firstEvidence := readyTaskEvidence(t, ctx, database, repository,
		entryID, warehouseID, subjectID, workerID, uuid.New())
	completedProof := taskBoardOwnerProofMessage(t, entryID, warehouseID, 1, false, nil, nil)
	if _, err := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, completedProof); err != nil {
		t.Fatalf("apply completed task owner proof: %v", err)
	}

	foreignCabinID := uuid.New()
	foreignWarehouseID := uuid.New()
	foreignCabinProof := cabinOwnerProofMessage(foreignCabinID, foreignCabinID,
		foreignWarehouseID, 0, cabinCreatedEvent, "FREE")
	if result, err := repository.ApplyCabinOwnerMessage(ctx, foreignCabinProof); err != nil ||
		result.Duplicate || result.Quarantined {
		t.Fatalf("apply foreign cabin owner proof = %#v, %v", result, err)
	}
	wrongWarehouse := SetCabinCoverFromTaskEvidenceCommand{
		CabinID: foreignCabinID, TaskBoardEntryID: entryID, EvidenceMediaID: firstEvidence,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('0'), CorrelationID: uuid.New(),
	}
	if _, _, err := repository.SetCabinCoverFromTaskEvidence(ctx, wrongWarehouse); !errors.Is(err, ErrConflict) {
		t.Fatalf("cross-warehouse evidence error = %v, want ErrConflict", err)
	}

	wrong := SetCabinCoverFromTaskEvidenceCommand{
		CabinID: cabinID, TaskBoardEntryID: entryID, EvidenceMediaID: uuid.New(),
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('1'), CorrelationID: uuid.New(),
	}
	if _, _, err := repository.SetCabinCoverFromTaskEvidence(ctx, wrong); !errors.Is(err, ErrConflict) {
		t.Fatalf("wrong evidence error = %v, want ErrConflict", err)
	}

	first := SetCabinCoverFromTaskEvidenceCommand{
		CabinID: cabinID, TaskBoardEntryID: entryID, EvidenceMediaID: firstEvidence,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('2'), CorrelationID: uuid.New(),
	}
	firstResult, replayed, err := repository.SetCabinCoverFromTaskEvidence(ctx, first)
	if err != nil || replayed || firstResult.MediaID != firstEvidence ||
		firstResult.Version != 2 || firstResult.Generation != 1 {
		t.Fatalf("first cover result = %#v replayed=%v error=%v", firstResult, replayed, err)
	}
	replayedResult, replayed, err := repository.SetCabinCoverFromTaskEvidence(ctx, first)
	if err != nil || !replayed || replayedResult.CabinID != firstResult.CabinID ||
		replayedResult.WarehouseID != firstResult.WarehouseID ||
		replayedResult.MediaID != firstResult.MediaID ||
		replayedResult.Generation != firstResult.Generation ||
		replayedResult.TaskBoardEntryID != firstResult.TaskBoardEntryID ||
		replayedResult.Version != firstResult.Version ||
		!replayedResult.ChangedAt.Equal(firstResult.ChangedAt) {
		t.Fatalf("exact cover replay = %#v replayed=%v error=%v", replayedResult, replayed, err)
	}
	changedReplay := first
	changedReplay.EvidenceMediaID = uuid.New()
	changedReplay.RequestSHA256 = hex64('3')
	if _, _, err := repository.SetCabinCoverFromTaskEvidence(ctx, changedReplay); !errors.Is(err, ErrIdempotencyMismatch) {
		t.Fatalf("changed idempotency replay error = %v", err)
	}

	secondEntryID := uuid.New()
	secondProof := taskBoardOwnerProofMessage(t, secondEntryID, warehouseID, 0, true,
		[]uuid.UUID{workerID}, nil)
	if _, err := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, secondProof); err != nil {
		t.Fatalf("apply second task owner proof: %v", err)
	}
	secondEvidence := readyTaskEvidence(t, ctx, database, repository,
		secondEntryID, warehouseID, uuid.New(), workerID, uuid.New())
	secondCompletedProof := taskBoardOwnerProofMessage(t, secondEntryID, warehouseID, 1,
		false, nil, nil)
	if _, err := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, secondCompletedProof); err != nil {
		t.Fatalf("apply second completed task owner proof: %v", err)
	}
	second := SetCabinCoverFromTaskEvidenceCommand{
		CabinID: cabinID, TaskBoardEntryID: secondEntryID, EvidenceMediaID: secondEvidence,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('4'), CorrelationID: uuid.New(),
	}
	secondResult, replayed, err := repository.SetCabinCoverFromTaskEvidence(ctx, second)
	if err != nil || replayed || secondResult.MediaID != secondEvidence || secondResult.Version != 3 {
		t.Fatalf("second cover result = %#v replayed=%v error=%v", secondResult, replayed, err)
	}

	var libraryRows, associatedRows, historyRows int
	var currentCover uuid.UUID
	if err := database.Pool.QueryRow(ctx, `select 1,cover_media_id
		from media_cabin_photo_library where cabin_id=$1`, cabinID).
		Scan(&libraryRows, &currentCover); err != nil {
		t.Fatalf("read canonical cover pointer: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_cabin_photo
		where cabin_id=$1`, cabinID).Scan(&associatedRows); err != nil {
		t.Fatalf("count cabin history: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_cabin_cover_history
		where cabin_id=$1`, cabinID).Scan(&historyRows); err != nil {
		t.Fatalf("count cover history: %v", err)
	}
	var coverFactRows int
	var finalPreviousCoverID uuid.UUID
	if err := database.Pool.QueryRow(ctx, `select count(*),
		(max(envelope_body->'payload'->>'previousCoverMediaId')
			filter (where aggregate_version=3))::uuid
		from media_transport_outbox
		where aggregate_type='CABIN_PHOTO_LIBRARY' and aggregate_id=$1
		  and event_type='media.cabin.cover-changed.v1'
		  and topic=$2 and record_key=$1`,
		cabinID, CabinPhotoTopic).Scan(&coverFactRows, &finalPreviousCoverID); err != nil {
		t.Fatalf("read canonical cover facts: %v", err)
	}
	if libraryRows != 1 || currentCover != secondEvidence || associatedRows != 3 ||
		historyRows != 2 || coverFactRows != 2 || finalPreviousCoverID != firstEvidence {
		t.Fatalf("canonical cover state rows=%d cover=%s photos=%d history=%d facts=%d previous=%s",
			libraryRows, currentCover, associatedRows, historyRows, coverFactRows,
			finalPreviousCoverID)
	}
	var evidenceOwnerCount int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_asset
		where media_id in ($1,$2) and owner_type='TASK_BOARD_ENTRY'`,
		firstEvidence, secondEvidence).Scan(&evidenceOwnerCount); err != nil || evidenceOwnerCount != 2 {
		t.Fatalf("evidence assets were copied or re-owned: count=%d error=%v", evidenceOwnerCount, err)
	}
}

func readyTaskEvidence(
	t *testing.T,
	ctx context.Context,
	database *Database,
	repository *Repository,
	entryID, warehouseID, subjectID, workerID, evidenceID uuid.UUID,
) uuid.UUID {
	t.Helper()
	command := taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID, workerID, evidenceID)
	asset, replayed, err := repository.CreateUpload(ctx, command)
	if err != nil || replayed {
		t.Fatalf("create task evidence = %#v replayed=%v error=%v", asset, replayed, err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set
		processing_status='READY',current_generation=1,next_generation=2,version=2,
		source_version_id='evidence-v1',source_etag='evidence-etag',
		source_checksum_sha256=$2,finalized_content_type='image/jpeg',
		finalized_size_bytes=128,size_bytes=128 where media_id=$1`,
		asset.ID, command.ChecksumSHA256); err != nil {
		t.Fatalf("mark task evidence ready: %v", err)
	}
	return asset.ID
}
