package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestTaskBoardWorkerProofGatesEvidenceAndSourceReadsIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media database: %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	warehouseID, entryID, workerID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	sourceID := readyInventorySourceForTaskBoard(t, ctx, database, repository, warehouseID)
	proof0 := taskBoardOwnerProofMessage(t, entryID, warehouseID, 0, true, []uuid.UUID{workerID},
		[]TaskBoardSourceMediaReference{{MediaID: sourceID, Generation: 1}})
	if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof0); applyErr != nil || result.Duplicate || result.Quarantined {
		t.Fatalf("apply task-board proof v0 = %#v, %v", result, applyErr)
	}
	if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof0); applyErr != nil || !result.Duplicate || result.Quarantined {
		t.Fatalf("replay task-board proof v0 = %#v, %v", result, applyErr)
	}
	if err := repository.AuthorizeTaskBoardEntryWorker(ctx, entryID, warehouseID, workerID); err != nil {
		t.Fatalf("authorize active worker: %v", err)
	}
	foreignWorkerID := uuid.New()
	if err := repository.AuthorizeTaskBoardEntryWorker(ctx, entryID, warehouseID, foreignWorkerID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("authorize foreign worker error = %v, want ErrOwnerProofMissing", err)
	}
	if err := repository.AuthorizeTaskBoardEntryWorker(ctx, entryID, uuid.New(), workerID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("authorize wrong warehouse error = %v, want ErrOwnerProofMissing", err)
	}

	evidenceID := uuid.New()
	create := taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID, workerID, evidenceID)
	asset, replayed, err := repository.CreateUpload(ctx, create)
	if err != nil || replayed || asset.CreatedBy == nil || asset.CreatedBy.PrincipalType != PrincipalTypeWorker ||
		asset.CreatedBy.SubjectID != workerID || asset.ClientReferenceID == nil || *asset.ClientReferenceID != evidenceID {
		t.Fatalf("create worker evidence = %#v replayed=%v error=%v", asset, replayed, err)
	}
	if session, sessionErr := repository.UploadSessionForPrincipal(ctx, asset.UploadSessionID, subjectID, PrincipalTypeWorker); sessionErr != nil || session.ID != asset.ID {
		t.Fatalf("worker upload session = %#v, %v", session, sessionErr)
	}
	retryCreate := create
	retryCreate.MediaID, retryCreate.FolderID, retryCreate.UploadSessionID = uuid.New(), uuid.New(), uuid.New()
	retriedAsset, retried, retryErr := repository.CreateUpload(ctx, retryCreate)
	if retryErr != nil || !retried || retriedAsset.ID != asset.ID ||
		retriedAsset.UploadSessionID != asset.UploadSessionID {
		t.Fatalf("replay worker evidence = %#v replayed=%v error=%v", retriedAsset, retried, retryErr)
	}
	foreignCreate := taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID, foreignWorkerID, uuid.New())
	if _, _, err := repository.CreateUpload(ctx, foreignCreate); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("foreign worker create error = %v, want ErrOwnerProofMissing", err)
	}
	wrongWarehouseCreate := taskBoardWorkerEvidenceCommand(entryID, uuid.New(), subjectID, workerID, uuid.New())
	if _, _, err := repository.CreateUpload(ctx, wrongWarehouseCreate); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("wrong warehouse create error = %v, want ErrOwnerProofMissing", err)
	}
	finalize := FinalizeCommand{
		SessionID: asset.UploadSessionID, SubjectID: subjectID, PrincipalType: PrincipalTypeWorker,
		Actor: ActorReference{SubjectID: workerID, PrincipalType: PrincipalTypeWorker}, WorkerID: &workerID,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('f'), ObjectVersionID: "task-source-v1",
		ETag: "task-etag-v1", ChecksumSHA256: create.ChecksumSHA256, ContentType: "image/jpeg",
		SizeBytes: create.ContentLength, CorrelationID: uuid.New(),
	}
	if _, finalizeReplayed, finalizeErr := repository.FinalizeUpload(ctx, finalize); finalizeErr != nil || finalizeReplayed {
		t.Fatalf("finalize worker evidence replayed=%v error=%v", finalizeReplayed, finalizeErr)
	}
	readyFact := completeWorkerEvidenceAndClaimReadyFact(t, ctx, repository, warehouseID, create, finalize)
	assertWorkerReadyFact(t, readyFact, workerID, evidenceID)
	if err := repository.MarkOutboxPublished(ctx, *readyFact); err != nil {
		t.Fatalf("publish ready fact: %v", err)
	}

	listCalls := 0
	if err := repository.ReadTaskBoardEntryAssetsForWorker(ctx, entryID, warehouseID, workerID, 10, nil,
		func(records []AssetWithVariants) error {
			listCalls++
			if len(records) != 1 || records[0].Asset.ID != asset.ID {
				t.Fatalf("worker result list = %#v", records)
			}
			return nil
		}); err != nil || listCalls != 1 {
		t.Fatalf("worker result list calls=%d error=%v", listCalls, err)
	}
	directRead := false
	if err := repository.ReadTaskBoardEntryOriginalForWorker(ctx, entryID, warehouseID, workerID, asset.ID, nil,
		func(record AssetRecord, original *VariantRecord) error {
			directRead = record.ID == asset.ID && record.Status == media.StatusReady && original != nil &&
				original.Variant == media.VariantOriginal
			return nil
		}); err != nil || !directRead {
		t.Fatalf("worker result original read=%v error=%v", directRead, err)
	}
	sourceGeneration := 1
	sourceRead := false
	if err := repository.ReadTaskBoardEntryOriginalForWorker(ctx, entryID, warehouseID, workerID, sourceID, &sourceGeneration,
		func(record AssetRecord, original *VariantRecord) error {
			sourceRead = record.ID == sourceID && record.Generation == sourceGeneration && record.Status == media.StatusReady &&
				original != nil && original.Variant == media.VariantOriginal
			return nil
		}); err != nil || !sourceRead {
		t.Fatalf("worker exact source original read=%v error=%v", sourceRead, err)
	}

	proof1 := taskBoardOwnerProofMessage(t, entryID, warehouseID, 1, true, []uuid.UUID{workerID}, nil)
	if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof1); applyErr != nil || result.Duplicate || result.Quarantined {
		t.Fatalf("apply source-change proof v1 = %#v, %v", result, applyErr)
	}
	if err := repository.ReadTaskBoardEntryOriginalForWorker(ctx, entryID, warehouseID, workerID, sourceID, &sourceGeneration,
		func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrNotFound) {
		t.Fatalf("source after proof change error = %v, want ErrNotFound", err)
	}

	expiredEvidenceID := uuid.New()
	expiredCommand := taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID, workerID, expiredEvidenceID)
	expiredAsset, expiredReplay, expiredErr := repository.CreateUpload(ctx, expiredCommand)
	if expiredErr != nil || expiredReplay {
		t.Fatalf("create expiring evidence replayed=%v error=%v", expiredReplay, expiredErr)
	}
	if _, err := database.Pool.Exec(ctx, `update media_upload_session
		set expires_at=clock_timestamp()-interval '1 minute'
		where upload_session_id=$1`, expiredAsset.UploadSessionID); err != nil {
		t.Fatalf("expire evidence upload session: %v", err)
	}
	reopenedCommand := expiredCommand
	reopenedCommand.MediaID, reopenedCommand.FolderID, reopenedCommand.UploadSessionID = uuid.New(), uuid.New(), uuid.New()
	reopenedCommand.UploadExpiresAt = time.Now().UTC().Add(time.Hour)
	reopenedAsset, reopenedReplay, reopenedErr := repository.CreateUpload(ctx, reopenedCommand)
	if reopenedErr != nil || !reopenedReplay || reopenedAsset.ID != expiredAsset.ID ||
		reopenedAsset.UploadSessionID != reopenedCommand.UploadSessionID ||
		reopenedAsset.UploadSessionID == expiredAsset.UploadSessionID {
		t.Fatalf("reopen expired evidence = %#v replayed=%v error=%v", reopenedAsset, reopenedReplay, reopenedErr)
	}
	if _, err := repository.UploadSessionForPrincipal(ctx, expiredAsset.UploadSessionID, subjectID, PrincipalTypeWorker); !errors.Is(err, ErrNotFound) {
		t.Fatalf("old expired session error = %v, want ErrNotFound", err)
	}
	if session, err := repository.UploadSessionForPrincipal(ctx, reopenedAsset.UploadSessionID, subjectID, PrincipalTypeWorker); err != nil || session.ID != expiredAsset.ID {
		t.Fatalf("reopened evidence session = %#v, %v", session, err)
	}
	var reopenedEvidenceRows int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_asset
		where owner_type='TASK_BOARD_ENTRY' and owner_id=$1 and client_reference_id=$2`,
		entryID.String(), expiredEvidenceID).Scan(&reopenedEvidenceRows); err != nil || reopenedEvidenceRows != 1 {
		t.Fatalf("reopened logical evidence rows=%d error=%v", reopenedEvidenceRows, err)
	}

	deletedEvidenceID := uuid.New()
	deletedCommand := taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID, workerID, deletedEvidenceID)
	deletedAsset, deletedReplay, deletedErr := repository.CreateUpload(ctx, deletedCommand)
	if deletedErr != nil || deletedReplay {
		t.Fatalf("create deletable evidence replayed=%v error=%v", deletedReplay, deletedErr)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set processing_status='DELETED',deleted_at=clock_timestamp()
		where media_id=$1`, deletedAsset.ID); err != nil {
		t.Fatalf("soft delete task evidence: %v", err)
	}
	retryDeleted := deletedCommand
	retryDeleted.MediaID, retryDeleted.FolderID, retryDeleted.UploadSessionID = uuid.New(), uuid.New(), uuid.New()
	if _, _, err := repository.CreateUpload(ctx, retryDeleted); !errors.Is(err, ErrConflict) {
		t.Fatalf("same deleted evidence ID create error = %v, want ErrConflict", err)
	}
	var deletedEvidenceRows int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_asset
		where owner_type='TASK_BOARD_ENTRY' and owner_id=$1 and client_reference_id=$2`, entryID.String(), deletedEvidenceID).
		Scan(&deletedEvidenceRows); err != nil || deletedEvidenceRows != 1 {
		t.Fatalf("logical deleted evidence rows=%d error=%v", deletedEvidenceRows, err)
	}

	proof2 := taskBoardOwnerProofMessage(t, entryID, warehouseID, 2, false, nil, nil)
	if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof2); applyErr != nil || result.Duplicate || result.Quarantined {
		t.Fatalf("apply revoked proof v2 = %#v, %v", result, applyErr)
	}
	if err := repository.AuthorizeTaskBoardEntryWorker(ctx, entryID, warehouseID, workerID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("authorize revoked worker error = %v, want ErrOwnerProofMissing", err)
	}
	if err := repository.ReadTaskBoardEntryAssetsForWorker(ctx, entryID, warehouseID, workerID, 10, nil,
		func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("revoked worker list error = %v, want ErrOwnerProofMissing", err)
	}
	if err := repository.ReadTaskBoardEntryOriginalForWorker(ctx, entryID, warehouseID, workerID, asset.ID, nil,
		func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("revoked worker result read error = %v, want ErrOwnerProofMissing", err)
	}
	if _, _, err := repository.CreateUpload(ctx, taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID, workerID, uuid.New())); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("revoked worker create error = %v, want ErrOwnerProofMissing", err)
	}
	if _, _, err := repository.FinalizeUpload(ctx, finalize); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("revoked worker finalize replay error = %v, want ErrOwnerProofMissing", err)
	}

	gapEntry := uuid.New()
	gap := taskBoardOwnerProofMessage(t, gapEntry, warehouseID, 1, true, []uuid.UUID{workerID}, nil)
	if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, gap); applyErr != nil || !result.Quarantined {
		t.Fatalf("version-gap proof result = %#v, %v", result, applyErr)
	}
	if err := repository.AuthorizeTaskBoardEntryWorker(ctx, gapEntry, warehouseID, workerID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("version-gap worker authorization error = %v, want ErrOwnerProofMissing", err)
	}
	var reason string
	if err := database.Pool.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3 and reconciled_at is null`,
		TaskBoardEntryOwnerProofConsumer, TaskBoardEntryOwnerProofAggregate, gapEntry).Scan(&reason); err != nil || reason != "VERSION_GAP" {
		t.Fatalf("version-gap quarantine reason=%q error=%v", reason, err)
	}
}

func TestTaskBoardUserAcceptanceReadsInactiveProofSourcesIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media database: %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	warehouseID, entryID, workerID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	sourceID := readyInventorySourceForTaskBoard(t, ctx, database, repository, warehouseID)
	unreferencedID := readyInventorySourceForTaskBoard(t, ctx, database, repository, warehouseID)
	proof0 := taskBoardOwnerProofMessage(t, entryID, warehouseID, 0, true, []uuid.UUID{workerID},
		[]TaskBoardSourceMediaReference{{MediaID: sourceID, Generation: 1}})
	if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof0); applyErr != nil || result.Duplicate || result.Quarantined {
		t.Fatalf("apply active task-board proof = %#v, %v", result, applyErr)
	}

	resultCommand := taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID, workerID, uuid.New())
	resultAsset, replayed, createErr := repository.CreateUpload(ctx, resultCommand)
	if createErr != nil || replayed {
		t.Fatalf("create task result replayed=%v error=%v", replayed, createErr)
	}
	seedReadyTaskBoardMedia(t, ctx, database, resultAsset.ID, resultCommand.ChecksumSHA256, 1)

	// The task board cites generation 1, then the source advances. The user
	// scope must keep returning generation 1 rather than following the source's
	// current generation 2.
	seedReadyTaskBoardMedia(t, ctx, database, sourceID, hex64('b'), 2)
	proof1 := taskBoardOwnerProofMessage(t, entryID, warehouseID, 1, false, nil,
		[]TaskBoardSourceMediaReference{{MediaID: sourceID, Generation: 1}})
	if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof1); applyErr != nil || result.Duplicate || result.Quarantined {
		t.Fatalf("apply inactive task-board proof = %#v, %v", result, applyErr)
	}

	if err := repository.ReadTaskBoardEntryAssetsForWorker(ctx, entryID, warehouseID, workerID, 10, nil,
		func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("inactive proof worker list error = %v, want ErrOwnerProofMissing", err)
	}

	var listed []AssetWithVariants
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID, 10, nil,
		func(records []AssetWithVariants) error {
			listed = append([]AssetWithVariants(nil), records...)
			return nil
		}); err != nil {
		t.Fatalf("inactive task-board user list: %v", err)
	}
	if len(listed) != 2 {
		t.Fatalf("inactive task-board user list = %#v, want result and pinned source", listed)
	}
	listedResult, listedSource := taskBoardListedAsset(listed, resultAsset.ID), taskBoardListedAsset(listed, sourceID)
	if listedResult == nil || listedResult.Asset.OwnerType != OwnerTypeTaskBoardEntry ||
		listedResult.Asset.Generation != 1 || taskBoardListedVariant(listedResult, media.VariantSmall) == nil {
		t.Fatalf("listed result asset = %#v", listedResult)
	}
	if listedSource == nil || listedSource.Asset.Generation != 1 || listedSource.Asset.Status != media.StatusReady {
		t.Fatalf("listed pinned source asset = %#v", listedSource)
	}
	listedSourceSmall := taskBoardListedVariant(listedSource, media.VariantSmall)
	if listedSourceSmall == nil || listedSourceSmall.ObjectKey != taskBoardProcessedVariant(sourceID, 1, media.VariantSmall).ObjectKey {
		t.Fatalf("listed pinned source small variant = %#v", listedSourceSmall)
	}
	var firstPage []AssetWithVariants
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID, 1, nil,
		func(records []AssetWithVariants) error {
			firstPage = append([]AssetWithVariants(nil), records...)
			return nil
		}); err != nil || len(firstPage) != 1 {
		t.Fatalf("first task-board user page = %#v, %v", firstPage, err)
	}
	var secondPage []AssetWithVariants
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID, 1,
		&firstPage[0].Asset.ID, func(records []AssetWithVariants) error {
			secondPage = append([]AssetWithVariants(nil), records...)
			return nil
		}); err != nil || len(secondPage) != 1 || secondPage[0].Asset.ID == firstPage[0].Asset.ID {
		t.Fatalf("second task-board user page = %#v, %v", secondPage, err)
	}

	pinnedGeneration := 1
	if err := repository.ReadOriginal(ctx, sourceID, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		&pinnedGeneration, func(asset AssetRecord, original *VariantRecord) error {
			if asset.ID != sourceID || asset.Generation != pinnedGeneration || original == nil ||
				original.ObjectKey != taskBoardProcessedVariant(sourceID, pinnedGeneration, media.VariantOriginal).ObjectKey {
				t.Fatalf("pinned source original asset=%#v original=%#v", asset, original)
			}
			return nil
		}); err != nil {
		t.Fatalf("inactive task-board user source original: %v", err)
	}
	if err := repository.ReadCurrentVariant(ctx, sourceID, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		pinnedGeneration, media.VariantSmall, func(asset AssetRecord, variant *VariantRecord) error {
			if asset.ID != sourceID || asset.Generation != pinnedGeneration || variant == nil ||
				variant.ObjectKey != taskBoardProcessedVariant(sourceID, pinnedGeneration, media.VariantSmall).ObjectKey {
				t.Fatalf("pinned source variant asset=%#v variant=%#v", asset, variant)
			}
			return nil
		}); err != nil {
		t.Fatalf("inactive task-board user source variant: %v", err)
	}
	if err := repository.ReadOriginal(ctx, resultAsset.ID, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		nil, func(asset AssetRecord, original *VariantRecord) error {
			if asset.ID != resultAsset.ID || asset.Generation != 1 || original == nil {
				t.Fatalf("inactive task-board user result original asset=%#v original=%#v", asset, original)
			}
			return nil
		}); err != nil {
		t.Fatalf("inactive task-board user result original: %v", err)
	}
	if err := repository.ReadCurrentVariant(ctx, resultAsset.ID, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		1, media.VariantSmall, func(asset AssetRecord, variant *VariantRecord) error {
			if asset.ID != resultAsset.ID || asset.Generation != 1 || variant == nil {
				t.Fatalf("inactive task-board user result variant asset=%#v variant=%#v", asset, variant)
			}
			return nil
		}); err != nil {
		t.Fatalf("inactive task-board user result variant: %v", err)
	}

	if err := repository.ReadCurrentVariant(ctx, sourceID, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		2, media.VariantSmall, func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrNotFound) {
		t.Fatalf("source current generation leak error = %v, want ErrNotFound", err)
	}
	if err := repository.ReadOriginal(ctx, unreferencedID, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		&pinnedGeneration, func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unreferenced source original error = %v, want ErrNotFound", err)
	}
	if err := repository.ReadCurrentVariant(ctx, unreferencedID, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		pinnedGeneration, media.VariantSmall, func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unreferenced source variant error = %v, want ErrNotFound", err)
	}
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeTaskBoardEntry, entryID.String(), uuid.New(), 10, nil,
		func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("wrong warehouse user list error = %v, want ErrOwnerProofMissing", err)
	}
	if err := repository.ReadCurrentVariant(ctx, sourceID, OwnerTypeTaskBoardEntry, entryID.String(), uuid.New(),
		pinnedGeneration, media.VariantSmall, func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("wrong warehouse user source read error = %v, want ErrOwnerProofMissing", err)
	}
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeTaskBoardEntry, uuid.New().String(), warehouseID, 10, nil,
		func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("missing task-board proof user list error = %v, want ErrOwnerProofMissing", err)
	}

	staleEntryID := uuid.New()
	for version := int64(0); version <= 1; version++ {
		proof := taskBoardOwnerProofMessage(t, staleEntryID, warehouseID, version, false, nil, nil)
		if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof); applyErr != nil || result.Quarantined {
			t.Fatalf("apply stale-proof fixture v%d = %#v, %v", version, result, applyErr)
		}
	}
	if _, err := database.Pool.Exec(ctx, `update media_consumer_aggregate_checkpoint
		set aggregate_version=0 where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3`,
		TaskBoardEntryOwnerProofConsumer, TaskBoardEntryOwnerProofAggregate, staleEntryID); err != nil {
		t.Fatalf("make task-board proof stale: %v", err)
	}
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeTaskBoardEntry, staleEntryID.String(), warehouseID, 10, nil,
		func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("stale task-board proof user list error = %v, want ErrOwnerProofMissing", err)
	}

	gap := taskBoardOwnerProofMessage(t, entryID, warehouseID, 3, false, nil,
		[]TaskBoardSourceMediaReference{{MediaID: sourceID, Generation: 1}})
	if result, applyErr := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, gap); applyErr != nil || !result.Quarantined {
		t.Fatalf("quarantine task-board proof result = %#v, %v", result, applyErr)
	}
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID, 10, nil,
		func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("quarantined task-board proof user list error = %v, want ErrOwnerProofMissing", err)
	}
	if err := repository.ReadCurrentVariant(ctx, sourceID, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		pinnedGeneration, media.VariantSmall, func(AssetRecord, *VariantRecord) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("quarantined task-board proof user source read error = %v, want ErrOwnerProofMissing", err)
	}
}

func seedReadyTaskBoardMedia(t *testing.T, ctx context.Context, database *Database, mediaID uuid.UUID, checksum string, generation int) {
	t.Helper()
	if _, err := database.Pool.Exec(ctx, `update media_asset set processing_status='READY',
		current_generation=$2,next_generation=$2+1,version=version+1,
		source_version_id='source-v'||$2::text,source_etag='source-etag',
		source_checksum_sha256=$3,finalized_content_type='image/jpeg',finalized_size_bytes=128,size_bytes=128,
		updated_at=clock_timestamp() where media_id=$1`, mediaID, generation, checksum); err != nil {
		t.Fatalf("mark task-board media generation %d ready: %v", generation, err)
	}
	for _, variant := range processedImageVariants(mediaID, generation) {
		if _, err := database.Pool.Exec(ctx, `insert into media_variant (
			media_id,generation,variant,object_key,object_version_id,content_type,size_bytes,width,height,checksum_sha256)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, mediaID, generation, variant.Variant, variant.ObjectKey,
			variant.ObjectVersionID, variant.ContentType, variant.SizeBytes, variant.Width, variant.Height,
			variant.ChecksumSHA256); err != nil {
			t.Fatalf("seed task-board media generation %d variant %s: %v", generation, variant.Variant, err)
		}
	}
}

func taskBoardListedAsset(records []AssetWithVariants, mediaID uuid.UUID) *AssetWithVariants {
	for index := range records {
		if records[index].Asset.ID == mediaID {
			return &records[index]
		}
	}
	return nil
}

func taskBoardListedVariant(record *AssetWithVariants, requested media.Variant) *VariantRecord {
	if record == nil {
		return nil
	}
	for index := range record.Variants {
		if record.Variants[index].Variant == requested {
			return &record.Variants[index]
		}
	}
	return nil
}

func taskBoardProcessedVariant(mediaID uuid.UUID, generation int, requested media.Variant) media.ProcessedVariant {
	for _, variant := range processedImageVariants(mediaID, generation) {
		if variant.Variant == requested {
			return variant
		}
	}
	panic("missing task-board processed variant")
}

func readyInventorySourceForTaskBoard(t *testing.T, ctx context.Context, database *Database, repository *Repository, warehouseID uuid.UUID) uuid.UUID {
	t.Helper()
	ownerID := uuid.New()
	proof := ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(), BodySHA256: hex64('1'),
		AggregateType: InventoryFindingAggregate, AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(), WarehouseID: warehouseID,
		OwnerRevision: 1, Active: true, RecordedAt: time.Now().UTC(),
	}
	if replayed, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil || replayed {
		t.Fatalf("apply source inventory proof replayed=%v error=%v", replayed, err)
	}
	command := createCommand(ownerID, warehouseID, media.KindImage, 0)
	asset, replayed, err := repository.CreateUpload(ctx, command)
	if err != nil || replayed {
		t.Fatalf("create source media replayed=%v error=%v", replayed, err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set processing_status='READY',
		current_generation=1,next_generation=2,version=2,source_version_id='source-v1',source_etag='source-etag',
		source_checksum_sha256=$2,finalized_content_type='image/jpeg',finalized_size_bytes=128,size_bytes=128,
		updated_at=clock_timestamp() where media_id=$1`, asset.ID, command.ChecksumSHA256); err != nil {
		t.Fatalf("mark source ready: %v", err)
	}
	for _, variant := range processedImageVariants(asset.ID, 1) {
		if _, err := database.Pool.Exec(ctx, `insert into media_variant (
			media_id,generation,variant,object_key,object_version_id,content_type,size_bytes,width,height,checksum_sha256)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, asset.ID, 1, variant.Variant, variant.ObjectKey,
			variant.ObjectVersionID, variant.ContentType, variant.SizeBytes, variant.Width, variant.Height,
			variant.ChecksumSHA256); err != nil {
			t.Fatalf("seed source %s variant: %v", variant.Variant, err)
		}
	}
	return asset.ID
}

func taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID, workerID, evidenceID uuid.UUID) CreateUploadCommand {
	mediaID := uuid.New()
	return CreateUploadCommand{
		MediaID: mediaID, FolderID: mediaID, UploadSessionID: uuid.New(), SubjectID: subjectID,
		PrincipalType: PrincipalTypeWorker, Actor: ActorReference{SubjectID: workerID, PrincipalType: PrincipalTypeWorker},
		WorkerID: &workerID, IdempotencyKey: evidenceID, RequestSHA256: hex64('a'), OwnerType: OwnerTypeTaskBoardEntry,
		OwnerID: entryID.String(), WarehouseID: warehouseID, ClientReferenceID: &evidenceID, Kind: media.KindImage,
		FileName: "result.jpg", ContentType: "image/jpeg", ContentLength: 128, ChecksumSHA256: hex64('b'),
		SortOrder: 0, SourceObjectKey: "media/" + mediaID.String() + "/source/result.jpg",
		UploadExpiresAt: time.Now().UTC().Add(time.Hour), CorrelationID: uuid.New(),
	}
}

func taskBoardOwnerProofMessage(t *testing.T, entryID, warehouseID uuid.UUID, version int64, active bool,
	workers []uuid.UUID, references []TaskBoardSourceMediaReference,
) TaskBoardEntryOwnerProofMessage {
	t.Helper()
	workerValues := make([]string, len(workers))
	for index, workerID := range workers {
		workerValues[index] = workerID.String()
	}
	referenceValues := make([]map[string]any, len(references))
	for index, reference := range references {
		referenceValues[index] = map[string]any{"mediaId": reference.MediaID.String(), "generation": reference.Generation}
	}
	recordedAt := time.Now().UTC()
	wireBody, err := json.Marshal(map[string]any{
		"eventType": "task-board.entry-owner-proof.changed.v1", "entryId": entryID.String(),
		"warehouseId": warehouseID.String(), "version": version, "active": active,
		"allowedWorkerIds": workerValues, "sourceMediaReferences": referenceValues,
	})
	if err != nil {
		t.Fatalf("marshal task-board proof wire body: %v", err)
	}
	sum := sha256.Sum256(wireBody)
	return TaskBoardEntryOwnerProofMessage{
		EventID: uuid.New(), BodySHA256: hex.EncodeToString(sum[:]), WireBody: wireBody,
		Topic: TaskBoardEntryOwnerProofTopic, EventType: "task-board.entry-owner-proof.changed.v1",
		AggregateType: TaskBoardEntryOwnerProofAggregate, AggregateID: entryID, AggregateVersion: version,
		RecordKey: entryID, RecordedAt: recordedAt, WarehouseID: warehouseID, RouteIndex: 0,
		Active: active, AllowedWorkerIDs: append([]uuid.UUID(nil), workers...),
		SourceMediaRefs: append([]TaskBoardSourceMediaReference(nil), references...),
	}
}

func completeWorkerEvidenceAndClaimReadyFact(t *testing.T, ctx context.Context, repository *Repository, warehouseID uuid.UUID,
	create CreateUploadCommand, finalize FinalizeCommand,
) *OutboxClaim {
	t.Helper()
	uploaded, err := repository.ClaimOutbox(ctx, "task-board-worker-media-relay", time.Minute)
	if err != nil || uploaded == nil || uploaded.Topic != MediaTopic {
		t.Fatalf("claim uploaded fact = %#v, %v", uploaded, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *uploaded); err != nil {
		t.Fatalf("publish uploaded fact: %v", err)
	}
	request, err := repository.ClaimOutbox(ctx, "task-board-worker-media-relay", time.Minute)
	if err != nil || request == nil || request.Topic != ProcessingTopic {
		t.Fatalf("claim processing request = %#v, %v", request, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *request); err != nil {
		t.Fatalf("publish processing request: %v", err)
	}
	message := ProcessingMessage{
		EventID: request.EventID, BodySHA256: request.BodySHA256, Topic: ProcessingTopic,
		EventType: "media.processing.request.v1", AggregateType: "PROCESSING_JOB", AggregateID: request.RecordKey,
		AggregateVersion: 1, RecordKey: request.RecordKey, CorrelationID: finalize.CorrelationID,
		ExpectedMediaID: create.MediaID, ExpectedWarehouseID: warehouseID, ExpectedKind: media.KindImage,
		ExpectedProcessingKind: media.ProcessingInitial, ExpectedGeneration: 1, ExpectedRotation: media.Rotation0,
		ExpectedSourceVersionID: finalize.ObjectVersionID,
	}
	claim, err := repository.ClaimProcessingJob(ctx, message, "task-board-worker-media-processor", time.Minute)
	if err != nil || claim.Duplicate {
		t.Fatalf("claim worker evidence processing = %#v, %v", claim, err)
	}
	if err := repository.CompleteProcessingJob(ctx, claim.Job, processedImageVariants(create.MediaID, 1)); err != nil {
		t.Fatalf("complete worker evidence processing: %v", err)
	}
	ready, err := repository.ClaimOutbox(ctx, "task-board-worker-media-relay", time.Minute)
	if err != nil || ready == nil || ready.Topic != MediaTopic {
		t.Fatalf("claim ready fact = %#v, %v", ready, err)
	}
	return ready
}

func assertWorkerReadyFact(t *testing.T, claim *OutboxClaim, workerID, evidenceID uuid.UUID) {
	t.Helper()
	var envelope map[string]any
	if err := json.Unmarshal(claim.Body, &envelope); err != nil {
		t.Fatalf("decode ready fact: %v", err)
	}
	if envelope["eventType"] != "media.media.ready.v1" {
		t.Fatalf("ready fact type = %#v", envelope["eventType"])
	}
	actor, actorOK := envelope["actorRef"].(map[string]any)
	payload, payloadOK := envelope["payload"].(map[string]any)
	if !actorOK || actor["subjectId"] != workerID.String() || actor["principalType"] != PrincipalTypeWorker ||
		!payloadOK || payload["clientReferenceId"] != evidenceID.String() || payload["ownerType"] != OwnerTypeTaskBoardEntry {
		t.Fatalf("ready fact actor=%#v payload=%#v", envelope["actorRef"], envelope["payload"])
	}
}
