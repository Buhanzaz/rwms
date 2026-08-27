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
		set cover_media_id=$1,active_gallery_folder_id=$2,version=1 where cabin_id=$3`,
		directAsset.ID, directAsset.FolderID, cabinID); err != nil {
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

	// A later processing replay of an older direct photo must not replace the
	// explicit task-evidence cover or emit another cover transition.
	tx, err := database.Pool.Begin(ctx)
	if err != nil {
		t.Fatalf("begin direct processing replay: %v", err)
	}
	defer tx.Rollback(ctx)
	replayedDirect, err := repository.assetForUpdate(ctx, tx, directAsset.ID)
	if err != nil {
		t.Fatalf("load direct processing replay: %v", err)
	}
	if err := repository.associateProcessedCabinImage(ctx, tx, replayedDirect, uuid.New()); err != nil {
		t.Fatalf("associate direct processing replay: %v", err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("commit direct processing replay: %v", err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset
		set current_generation=2,next_generation=3,version=version+1
		where media_id=$1`, secondEvidence); err != nil {
		t.Fatalf("advance current task-evidence cover generation: %v", err)
	}
	refreshTx, err := database.Pool.Begin(ctx)
	if err != nil {
		t.Fatalf("begin task-evidence cover refresh: %v", err)
	}
	defer refreshTx.Rollback(ctx)
	refreshedEvidence, err := repository.assetForUpdate(ctx, refreshTx, secondEvidence)
	if err != nil {
		t.Fatalf("load reprocessed task-evidence cover: %v", err)
	}
	if err := repository.associateProcessedCabinImage(ctx, refreshTx, refreshedEvidence, uuid.New()); err != nil {
		t.Fatalf("refresh reprocessed task-evidence cover: %v", err)
	}
	if err := refreshTx.Commit(ctx); err != nil {
		t.Fatalf("commit task-evidence cover refresh: %v", err)
	}

	var libraryRows, associatedRows, historyRows int
	var currentCover uuid.UUID
	var currentVersion int64
	if err := database.Pool.QueryRow(ctx, `select 1,cover_media_id,version
		from media_cabin_photo_library where cabin_id=$1`, cabinID).
		Scan(&libraryRows, &currentCover, &currentVersion); err != nil {
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
	var refreshedPreviousCoverID uuid.UUID
	var refreshedGeneration int
	if err := database.Pool.QueryRow(ctx, `select count(*),
		(max(envelope_body->'payload'->>'previousCoverMediaId')
			filter (where aggregate_version=3))::uuid,
		(max(envelope_body->'payload'->>'previousCoverMediaId')
			filter (where aggregate_version=4))::uuid,
		max((envelope_body->'payload'->>'generation')::integer)
			filter (where aggregate_version=4)
		from media_transport_outbox
		where aggregate_type='CABIN_PHOTO_LIBRARY' and aggregate_id=$1
		  and event_type='media.cabin.cover-changed.v1'
		  and topic=$2 and record_key=$1`,
		cabinID, CabinPhotoTopic).Scan(&coverFactRows, &finalPreviousCoverID,
		&refreshedPreviousCoverID, &refreshedGeneration); err != nil {
		t.Fatalf("read canonical cover facts: %v", err)
	}
	if libraryRows != 1 || currentCover != secondEvidence || associatedRows != 3 ||
		currentVersion != 4 || historyRows != 3 || coverFactRows != 3 ||
		finalPreviousCoverID != firstEvidence || refreshedPreviousCoverID != secondEvidence ||
		refreshedGeneration != 2 {
		t.Fatalf("canonical cover state rows=%d cover=%s version=%d photos=%d history=%d facts=%d previous=%s refreshedPrevious=%s refreshedGeneration=%d",
			libraryRows, currentCover, currentVersion, associatedRows, historyRows, coverFactRows,
			finalPreviousCoverID, refreshedPreviousCoverID, refreshedGeneration)
	}
	var evidenceOwnerCount int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_asset
		where media_id in ($1,$2) and owner_type='TASK_BOARD_ENTRY'`,
		firstEvidence, secondEvidence).Scan(&evidenceOwnerCount); err != nil || evidenceOwnerCount != 2 {
		t.Fatalf("evidence assets were copied or re-owned: count=%d error=%v", evidenceOwnerCount, err)
	}
}

func TestDirectCabinCoverUsesLatestFolderAndStableOrderIntegration(t *testing.T) {
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
	olderFolderID := uuid.New()
	newerFolderID := uuid.New()
	olderTitle := createCommand(cabinID, warehouseID, media.KindImage, 0)
	olderTitle.OwnerType = OwnerTypeCabin
	olderTitle.FolderID = olderFolderID
	olderLater := createCommand(cabinID, warehouseID, media.KindImage, 1)
	olderLater.OwnerType = OwnerTypeCabin
	olderLater.FolderID = olderFolderID
	newerTitle := createCommand(cabinID, warehouseID, media.KindImage, 0)
	newerTitle.OwnerType = OwnerTypeCabin
	newerTitle.FolderID = newerFolderID
	newerLater := createCommand(cabinID, warehouseID, media.KindImage, 1)
	newerLater.OwnerType = OwnerTypeCabin
	newerLater.FolderID = newerFolderID
	newerPending := createCommand(cabinID, warehouseID, media.KindImage, 2)
	newerPending.OwnerType = OwnerTypeCabin
	newerPending.FolderID = newerFolderID

	commands := []CreateUploadCommand{olderTitle, olderLater, newerTitle, newerLater}
	for _, command := range commands {
		if _, replayed, createErr := repository.CreateUpload(ctx, command); createErr != nil || replayed {
			t.Fatalf("CreateUpload(%s) = replayed:%v error:%v", command.MediaID, replayed, createErr)
		}
		if _, updateErr := database.Pool.Exec(ctx, `update media_asset set
			processing_status='READY',current_generation=1,next_generation=2,version=2,
			source_version_id='ready-version',source_etag='ready-etag',
			source_checksum_sha256=$2,finalized_content_type='image/jpeg',
			finalized_size_bytes=128,size_bytes=128 where media_id=$1`,
			command.MediaID, command.ChecksumSHA256); updateErr != nil {
			t.Fatalf("mark %s READY: %v", command.MediaID, updateErr)
		}
		if _, variantErr := database.Pool.Exec(ctx, `insert into media_variant (
			media_id,generation,variant,object_key,object_version_id,content_type,
			size_bytes,width,height,checksum_sha256)
		values ($1,1,'SMALL',$2,$3,'image/webp',64,360,240,$4)`, command.MediaID,
			"media/"+command.MediaID.String()+"/1/small.webp",
			"small-"+command.MediaID.String(), command.ChecksumSHA256); variantErr != nil {
			t.Fatalf("insert SMALL variant for %s: %v", command.MediaID, variantErr)
		}
	}
	if _, replayed, createErr := repository.CreateUpload(ctx, newerPending); createErr != nil || replayed {
		t.Fatalf("CreateUpload(%s) = replayed:%v error:%v", newerPending.MediaID, replayed, createErr)
	}
	olderAttachedAt := time.Date(2026, 8, 24, 10, 0, 0, 0, time.UTC)
	for _, attached := range []struct {
		mediaID    uuid.UUID
		attachedAt time.Time
	}{
		{mediaID: olderTitle.MediaID, attachedAt: olderAttachedAt},
		{mediaID: olderLater.MediaID, attachedAt: olderAttachedAt.Add(3 * time.Minute)},
		{mediaID: newerTitle.MediaID, attachedAt: olderAttachedAt.Add(time.Minute)},
		{mediaID: newerLater.MediaID, attachedAt: olderAttachedAt.Add(2 * time.Minute)},
		{mediaID: newerPending.MediaID, attachedAt: olderAttachedAt.Add(4 * time.Minute)},
	} {
		updated, updateErr := database.Pool.Exec(ctx, `update media_cabin_photo
			set attached_at=$2 where media_id=$1`, attached.mediaID, attached.attachedAt)
		if updateErr != nil {
			t.Fatalf("set association timestamp for %s: %v", attached.mediaID, updateErr)
		}
		if updated.RowsAffected() != 1 {
			t.Fatalf("association timestamp rows for %s = %d, want 1",
				attached.mediaID, updated.RowsAffected())
		}
	}

	associate := func(command CreateUploadCommand) {
		t.Helper()
		tx, beginErr := database.Pool.Begin(ctx)
		if beginErr != nil {
			t.Fatalf("begin association transaction: %v", beginErr)
		}
		defer tx.Rollback(ctx)
		asset, loadErr := repository.assetForUpdate(ctx, tx, command.MediaID)
		if loadErr != nil {
			t.Fatalf("load READY asset %s: %v", command.MediaID, loadErr)
		}
		if associateErr := repository.associateProcessedCabinImage(ctx, tx, asset, uuid.New()); associateErr != nil {
			t.Fatalf("associate READY asset %s: %v", command.MediaID, associateErr)
		}
		if commitErr := tx.Commit(ctx); commitErr != nil {
			t.Fatalf("commit READY asset %s: %v", command.MediaID, commitErr)
		}
	}

	associate(olderTitle)
	// A later-position photo may complete first, but its newer folder must
	// immediately replace the prior folder.
	associate(newerLater)
	// Delayed work from the older folder has a later READY timestamp than the
	// current cover. It still must not reclaim the active pointer because the
	// newer folder's retained batch includes a later pending association.
	associate(olderLater)
	var delayedCoverID, delayedFolderID uuid.UUID
	var delayedVersion int64
	if err := database.Pool.QueryRow(ctx, `select cover_media_id,active_gallery_folder_id,version
		from media_cabin_photo_library where cabin_id=$1`, cabinID).
		Scan(&delayedCoverID, &delayedFolderID, &delayedVersion); err != nil {
		t.Fatalf("read cover after delayed older completion: %v", err)
	}
	if delayedCoverID != newerLater.MediaID || delayedFolderID != newerFolderID || delayedVersion != 2 {
		t.Fatalf("delayed older completion changed latest folder = media:%s folder:%s version:%d",
			delayedCoverID, delayedFolderID, delayedVersion)
	}
	// When the title finishes later, the same folder converges to its stable
	// earliest READY association.
	associate(newerTitle)

	var coverMediaID, activeFolderID uuid.UUID
	var version, historyRows, coverFactRows int64
	if err := database.Pool.QueryRow(ctx, `select cover_media_id,active_gallery_folder_id,version
		from media_cabin_photo_library where cabin_id=$1`, cabinID).
		Scan(&coverMediaID, &activeFolderID, &version); err != nil {
		t.Fatalf("read direct cover: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_cabin_cover_history
		where cabin_id=$1`, cabinID).Scan(&historyRows); err != nil {
		t.Fatalf("count direct cover history: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_transport_outbox
		where aggregate_type='CABIN_PHOTO_LIBRARY' and aggregate_id=$1
		  and event_type='media.cabin.cover-changed.v1'`, cabinID).Scan(&coverFactRows); err != nil {
		t.Fatalf("count direct cover facts: %v", err)
	}
	if coverMediaID != newerTitle.MediaID || activeFolderID != newerFolderID || version != 3 ||
		historyRows != 3 || coverFactRows != 3 {
		t.Fatalf("stable direct cover = media:%s folder:%s version:%d history:%d facts:%d",
			coverMediaID, activeFolderID, version, historyRows, coverFactRows)
	}
	var covers []CabinCoverRecord
	if err := repository.ReadCabinCovers(ctx, warehouseID, []uuid.UUID{cabinID},
		func(records []CabinCoverRecord) error {
			covers = records
			return nil
		}); err != nil || len(covers) != 1 || covers[0].PhotoCount != 3 ||
		covers[0].MediaID != newerTitle.MediaID || len(covers[0].Previews) != 2 ||
		covers[0].Previews[0].MediaID != newerTitle.MediaID ||
		covers[0].Previews[1].MediaID != newerLater.MediaID {
		t.Fatalf("latest-folder cover projection = %#v error=%v", covers, err)
	}
	var archive []AssetWithVariants
	if err := repository.ReadOwnerAssets(ctx, OwnerTypeCabin, cabinID.String(), warehouseID,
		10, nil, func(records []AssetWithVariants) error {
			archive = records
			return nil
		}); err != nil || len(archive) != 5 {
		t.Fatalf("complete CABIN archive = %#v error=%v", archive, err)
	}
	folderCounts := map[uuid.UUID]int{}
	for _, item := range archive {
		folderCounts[item.Asset.FolderID]++
	}
	if folderCounts[olderFolderID] != 2 || folderCounts[newerFolderID] != 3 {
		t.Fatalf("archive folder counts = %#v", folderCounts)
	}
}

func TestCabinPresentationSnapshotCapsOneActiveFolderAtOneHundredIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	cabinID := uuid.MustParse("51000000-0000-4000-8000-000000000001")
	warehouseID := uuid.MustParse("00000000-0000-0000-0000-000000000001")
	folderID := uuid.New()
	directMediaIDs := make([]uuid.UUID, 0, 100)
	for index := 0; index < 100; index++ {
		command := createCommand(cabinID, warehouseID, media.KindImage, int64(index))
		command.OwnerType = OwnerTypeCabin
		command.FolderID = folderID
		if _, replayed, createErr := repository.CreateUpload(ctx, command); createErr != nil || replayed {
			t.Fatalf("CreateUpload(%d) = replayed:%v error:%v", index, replayed, createErr)
		}
		directMediaIDs = append(directMediaIDs, command.MediaID)
		if _, updateErr := database.Pool.Exec(ctx, `update media_asset set
			processing_status='READY',current_generation=1,next_generation=2,version=2,
			source_version_id='ready-version',source_etag='ready-etag',
			source_checksum_sha256=$2,finalized_content_type='image/jpeg',
			finalized_size_bytes=128,size_bytes=128 where media_id=$1`,
			command.MediaID, command.ChecksumSHA256); updateErr != nil {
			t.Fatalf("mark direct media %d READY: %v", index, updateErr)
		}
		for _, variant := range []media.Variant{media.VariantSmall, media.VariantLarge} {
			if _, variantErr := database.Pool.Exec(ctx, `insert into media_variant (
				media_id,generation,variant,object_key,object_version_id,content_type,
				size_bytes,width,height,checksum_sha256)
			values ($1,1,$2,$3,$4,'image/webp',64,360,240,$5)`, command.MediaID,
				variant, "media/"+command.MediaID.String()+"/1/"+string(variant)+".webp",
				"version-"+string(variant), command.ChecksumSHA256); variantErr != nil {
				t.Fatalf("insert direct media %d %s variant: %v", index, variant, variantErr)
			}
		}
	}
	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo set media_generation=1
		where cabin_id=$1 and gallery_folder_id=$2`, cabinID, folderID); err != nil {
		t.Fatalf("advance direct associations: %v", err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_cabin_photo_library
		set cover_media_id=$2,active_gallery_folder_id=$3,version=1
		where cabin_id=$1`, cabinID, directMediaIDs[0], folderID); err != nil {
		t.Fatalf("select direct active folder: %v", err)
	}

	entryID, workerID, subjectID, evidenceReferenceID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	proof := taskBoardOwnerProofMessage(t, entryID, warehouseID, 0, true,
		[]uuid.UUID{workerID}, nil)
	if _, err := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, proof); err != nil {
		t.Fatalf("apply task owner proof: %v", err)
	}
	evidence := taskBoardWorkerEvidenceCommand(entryID, warehouseID, subjectID,
		workerID, evidenceReferenceID)
	evidence.FolderID = folderID
	if _, replayed, err := repository.CreateUpload(ctx, evidence); err != nil || replayed {
		t.Fatalf("create task evidence = replayed:%v error:%v", replayed, err)
	}
	seedReadyTaskBoardMedia(t, ctx, database, evidence.MediaID, evidence.ChecksumSHA256, 1)
	completedProof := taskBoardOwnerProofMessage(t, entryID, warehouseID, 1, false, nil, nil)
	if _, err := repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, completedProof); err != nil {
		t.Fatalf("apply completed task owner proof: %v", err)
	}
	coverCommand := SetCabinCoverFromTaskEvidenceCommand{
		CabinID: cabinID, TaskBoardEntryID: entryID, EvidenceMediaID: evidence.MediaID,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('8'), CorrelationID: uuid.New(),
	}
	if record, replayed, err := repository.SetCabinCoverFromTaskEvidence(ctx, coverCommand); err != nil || replayed || record.MediaID != evidence.MediaID {
		t.Fatalf("select task evidence cover = %#v replayed:%v error:%v", record, replayed, err)
	}

	var covers []CabinCoverRecord
	if err := repository.ReadCabinCovers(ctx, warehouseID, []uuid.UUID{cabinID},
		func(records []CabinCoverRecord) error {
			covers = records
			return nil
		}); err != nil || len(covers) != 1 || covers[0].PhotoCount != 101 ||
		len(covers[0].Previews) != 100 || covers[0].Previews[0].MediaID != evidence.MediaID {
		t.Fatalf("bounded public active-folder projection = %#v error=%v", covers, err)
	}
	var presentations []CabinPresentationSnapshotRecord
	if err := repository.ReadCabinPresentationSnapshots(ctx, warehouseID, []uuid.UUID{cabinID},
		func(records []CabinPresentationSnapshotRecord) error {
			presentations = records
			return nil
		}); err != nil || len(presentations) != 1 ||
		presentations[0].CoverMediaID == nil ||
		*presentations[0].CoverMediaID != evidence.MediaID ||
		presentations[0].PhotoCount != 101 ||
		len(presentations[0].Photos) != 100 ||
		presentations[0].Photos[0].MediaID != evidence.MediaID ||
		presentations[0].Photos[99].SortOrder != 99 {
		t.Fatalf("bounded private active-folder snapshot = %#v error=%v", presentations, err)
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
