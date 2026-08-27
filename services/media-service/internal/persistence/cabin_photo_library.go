package persistence

import (
	"context"
	"errors"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// CabinPhotoTopic carries canonical CABIN photo-library change facts.
const CabinPhotoTopic = "rwms.media.cabin-photo.v1"

func associateCabinImageUpload(
	ctx context.Context,
	tx pgx.Tx,
	cabinID, mediaID, warehouseID, galleryFolderID uuid.UUID,
	sortOrder int64,
	attachedAt time.Time,
) error {
	_, err := tx.Exec(ctx, `insert into media_cabin_photo (
		cabin_id,media_id,warehouse_id,media_generation,task_board_entry_id,
		association_source,gallery_folder_id,sort_order,attached_at)
	values ($1,$2,$3,0,null,'DIRECT',$4,$5,$6)`,
		cabinID, mediaID, warehouseID, galleryFolderID, sortOrder, attachedAt)
	if err != nil {
		return translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `insert into media_cabin_photo_library (
		cabin_id,warehouse_id,cover_media_id,version,updated_at)
	values ($1,$2,null,0,$3)
	on conflict (cabin_id) do nothing`, cabinID, warehouseID, attachedAt)
	return translateConstraint(err)
}

// SetCabinCoverFromTaskEvidenceCommand atomically attaches proven task-board
// image evidence to a CABIN and makes it the single canonical cover.
type SetCabinCoverFromTaskEvidenceCommand struct {
	CabinID          uuid.UUID
	TaskBoardEntryID uuid.UUID
	EvidenceMediaID  uuid.UUID
	IdempotencyKey   uuid.UUID
	RequestSHA256    string
	CorrelationID    uuid.UUID
}

// CabinCoverChangeRecord is the immutable result of a CABIN cover transition.
type CabinCoverChangeRecord struct {
	CabinID          uuid.UUID
	WarehouseID      uuid.UUID
	MediaID          uuid.UUID
	Generation       int
	TaskBoardEntryID uuid.UUID
	Version          int64
	ChangedAt        time.Time
}

// SetCabinCoverFromTaskEvidence validates current CABIN and task-board proofs,
// attaches existing READY image evidence, and replaces the cover atomically.
func (repository *Repository) SetCabinCoverFromTaskEvidence(
	ctx context.Context,
	command SetCabinCoverFromTaskEvidenceCommand,
) (CabinCoverChangeRecord, bool, error) {
	if command.CabinID == uuid.Nil || command.TaskBoardEntryID == uuid.Nil ||
		command.EvidenceMediaID == uuid.Nil || command.IdempotencyKey == uuid.Nil ||
		command.CorrelationID == uuid.Nil || !validSHA256(command.RequestSHA256) {
		return CabinCoverChangeRecord{}, false, ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return CabinCoverChangeRecord{}, false, err
	}
	defer tx.Rollback(ctx)
	for _, lock := range []string{
		"cabin-cover:cabin:" + command.CabinID.String(),
		"cabin-cover:idempotency:" + command.IdempotencyKey.String(),
	} {
		if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`, lock); err != nil {
			return CabinCoverChangeRecord{}, false, err
		}
	}

	var replay CabinCoverChangeRecord
	var requestSHA string
	err = tx.QueryRow(ctx, `select request_sha256,cabin_id,evidence_media_id,
		task_board_entry_id,cover_version,created_at
		from media_cabin_cover_command where idempotency_key=$1 for update`,
		command.IdempotencyKey).Scan(&requestSHA, &replay.CabinID, &replay.MediaID,
		&replay.TaskBoardEntryID, &replay.Version, &replay.ChangedAt)
	if err == nil {
		if requestSHA != command.RequestSHA256 || replay.CabinID != command.CabinID ||
			replay.MediaID != command.EvidenceMediaID ||
			replay.TaskBoardEntryID != command.TaskBoardEntryID {
			return CabinCoverChangeRecord{}, false, ErrIdempotencyMismatch
		}
		if err := tx.QueryRow(ctx, `select warehouse_id,media_generation
			from media_cabin_photo where cabin_id=$1 and media_id=$2`,
			replay.CabinID, replay.MediaID).Scan(&replay.WarehouseID, &replay.Generation); err != nil {
			return CabinCoverChangeRecord{}, false, err
		}
		if err := tx.Commit(ctx); err != nil {
			return CabinCoverChangeRecord{}, false, err
		}
		return replay, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return CabinCoverChangeRecord{}, false, err
	}

	warehouseID, generation, folderID, err := validateCabinTaskEvidence(ctx, tx, command)
	if err != nil {
		return CabinCoverChangeRecord{}, false, err
	}
	now := repository.now().UTC().Truncate(time.Microsecond)
	var sortOrder int64
	if err := tx.QueryRow(ctx, `select coalesce(max(sort_order),-1)+1
		from media_cabin_photo where cabin_id=$1`, command.CabinID).Scan(&sortOrder); err != nil {
		return CabinCoverChangeRecord{}, false, err
	}
	_, err = tx.Exec(ctx, `insert into media_cabin_photo (
		cabin_id,media_id,warehouse_id,media_generation,task_board_entry_id,
		association_source,gallery_folder_id,sort_order,attached_at)
	values ($1,$2,$3,$4,$5,'TASK_EVIDENCE',$6,$7,$8)
	on conflict (cabin_id,media_id) do nothing`,
		command.CabinID, command.EvidenceMediaID, warehouseID, generation,
		command.TaskBoardEntryID, folderID, sortOrder, now)
	if err != nil {
		return CabinCoverChangeRecord{}, false, translateConstraint(err)
	}

	var previousMediaID *uuid.UUID
	var currentMediaID *uuid.UUID
	var currentVersion int64
	err = tx.QueryRow(ctx, `select cover_media_id,version
		from media_cabin_photo_library where cabin_id=$1 for update`,
		command.CabinID).Scan(&currentMediaID, &currentVersion)
	if errors.Is(err, pgx.ErrNoRows) {
		currentVersion = 0
		if _, err := tx.Exec(ctx, `insert into media_cabin_photo_library (
			cabin_id,warehouse_id,cover_media_id,version,updated_at,active_gallery_folder_id)
			values ($1,$2,null,0,$3,null)`, command.CabinID, warehouseID, now); err != nil {
			return CabinCoverChangeRecord{}, false, translateConstraint(err)
		}
	} else if err != nil {
		return CabinCoverChangeRecord{}, false, err
	} else if currentMediaID != nil {
		copyOfCurrent := *currentMediaID
		previousMediaID = &copyOfCurrent
	}
	if currentMediaID != nil && *currentMediaID == command.EvidenceMediaID {
		record := CabinCoverChangeRecord{
			CabinID: command.CabinID, WarehouseID: warehouseID,
			MediaID: command.EvidenceMediaID, Generation: generation,
			TaskBoardEntryID: command.TaskBoardEntryID, Version: currentVersion,
			ChangedAt: now,
		}
		if err := insertCabinCoverCommand(ctx, tx, command, record); err != nil {
			return CabinCoverChangeRecord{}, false, err
		}
		if err := tx.Commit(ctx); err != nil {
			return CabinCoverChangeRecord{}, false, translateConstraint(err)
		}
		return record, true, nil
	}

	version := currentVersion + 1
	commandTag, err := tx.Exec(ctx, `update media_cabin_photo_library
	set warehouse_id=$2,cover_media_id=$3,version=$4,updated_at=$5,
		active_gallery_folder_id=$7
		where cabin_id=$1 and version=$6`, command.CabinID, warehouseID,
		command.EvidenceMediaID, version, now, currentVersion, folderID)
	if err != nil {
		return CabinCoverChangeRecord{}, false, translateConstraint(err)
	}
	if commandTag.RowsAffected() != 1 {
		return CabinCoverChangeRecord{}, false, ErrConflict
	}
	_, err = tx.Exec(ctx, `insert into media_cabin_cover_history (
		cabin_id,version,warehouse_id,previous_media_id,cover_media_id,
		task_board_entry_id,idempotency_key,changed_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8)`, command.CabinID, version, warehouseID,
		previousMediaID, command.EvidenceMediaID, command.TaskBoardEntryID,
		command.IdempotencyKey, now)
	if err != nil {
		return CabinCoverChangeRecord{}, false, translateConstraint(err)
	}
	record := CabinCoverChangeRecord{
		CabinID: command.CabinID, WarehouseID: warehouseID,
		MediaID: command.EvidenceMediaID, Generation: generation,
		TaskBoardEntryID: command.TaskBoardEntryID, Version: version, ChangedAt: now,
	}
	if err := insertCabinCoverCommand(ctx, tx, command, record); err != nil {
		return CabinCoverChangeRecord{}, false, err
	}
	if err := insertCabinCoverFact(ctx, tx, record, previousMediaID, command.CorrelationID); err != nil {
		return CabinCoverChangeRecord{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return CabinCoverChangeRecord{}, false, translateConstraint(err)
	}
	return record, false, nil
}

func validateCabinTaskEvidence(
	ctx context.Context,
	tx pgx.Tx,
	command SetCabinCoverFromTaskEvidenceCommand,
) (uuid.UUID, int, uuid.UUID, error) {
	var warehouseID uuid.UUID
	var folderID uuid.UUID
	var generation int
	err := tx.QueryRow(ctx, `select proof.warehouse_id,asset.current_generation,asset.folder_id
		from media_task_board_entry_owner_proof proof
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=$4
		 and checkpoint.aggregate_type=$5
		 and checkpoint.aggregate_id=proof.entry_id
		 and checkpoint.aggregate_version>=proof.aggregate_version
		join media_asset asset
		  on asset.media_id=$3 and asset.owner_type='TASK_BOARD_ENTRY'
		 and asset.owner_id=proof.entry_id::text and asset.warehouse_id=proof.warehouse_id
		 and asset.media_kind='IMAGE' and asset.processing_status='READY'
		 and asset.current_generation>0 and asset.deleted_at is null
		 and media_asset_is_available(asset.media_id)
		join media_owner_binding cabin_binding
		  on cabin_binding.owner_type='CABIN' and cabin_binding.owner_id=$1::text
		 and cabin_binding.warehouse_id=proof.warehouse_id and cabin_binding.active
		join media_consumer_aggregate_checkpoint cabin_checkpoint
		  on cabin_checkpoint.consumer_name=cabin_binding.proof_consumer_name
		 and cabin_checkpoint.aggregate_type=cabin_binding.proof_aggregate_type
		 and cabin_checkpoint.aggregate_id=cabin_binding.proof_aggregate_id
		 and cabin_checkpoint.aggregate_version>=cabin_binding.proof_aggregate_version
		where proof.entry_id=$2 and not proof.quarantined
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=$4 and quarantine.aggregate_type=$5
		      and quarantine.aggregate_id=proof.entry_id and quarantine.reconciled_at is null)
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=cabin_binding.proof_consumer_name
		      and quarantine.aggregate_type=cabin_binding.proof_aggregate_type
		      and quarantine.aggregate_id=cabin_binding.proof_aggregate_id
		      and quarantine.reconciled_at is null)
		for share of proof,cabin_binding`,
		command.CabinID, command.TaskBoardEntryID, command.EvidenceMediaID,
		TaskBoardEntryOwnerProofConsumer, TaskBoardEntryOwnerProofAggregate).
		Scan(&warehouseID, &generation, &folderID)
	if errors.Is(err, pgx.ErrNoRows) {
		return uuid.Nil, 0, uuid.Nil, ErrConflict
	}
	return warehouseID, generation, folderID, err
}

func insertCabinCoverCommand(
	ctx context.Context,
	tx pgx.Tx,
	command SetCabinCoverFromTaskEvidenceCommand,
	record CabinCoverChangeRecord,
) error {
	_, err := tx.Exec(ctx, `insert into media_cabin_cover_command (
		idempotency_key,request_sha256,cabin_id,task_board_entry_id,
		evidence_media_id,cover_version,created_at)
	values ($1,$2,$3,$4,$5,$6,$7)`, command.IdempotencyKey, command.RequestSHA256,
		command.CabinID, command.TaskBoardEntryID, command.EvidenceMediaID,
		record.Version, record.ChangedAt)
	return translateConstraint(err)
}

func insertCabinCoverFact(
	ctx context.Context,
	tx pgx.Tx,
	record CabinCoverChangeRecord,
	previousMediaID *uuid.UUID,
	correlationID uuid.UUID,
) error {
	eventID := uuid.New()
	var taskBoardEntryID any
	if record.TaskBoardEntryID != uuid.Nil {
		taskBoardEntryID = record.TaskBoardEntryID
	}
	payload := map[string]any{
		"cabinId": record.CabinID, "warehouseId": record.WarehouseID,
		"mediaId": record.MediaID, "generation": record.Generation,
		"taskBoardEntryId":     taskBoardEntryID,
		"previousCoverMediaId": previousMediaID, "changedAt": record.ChangedAt,
	}
	body, checksum, err := envelope(eventID, "media.cabin.cover-changed.v1",
		"CABIN_PHOTO_LIBRARY", record.CabinID, record.Version, nil,
		correlationID, record.ChangedAt, payload)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `insert into media_transport_outbox (
		event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,record_key,
		publication_ordinal,envelope_body,wire_body,envelope_sha256,recorded_at)
	values ($1,'CABIN_PHOTO_LIBRARY',$2,$3,'media.cabin.cover-changed.v1',$4,$2,$3,
		$5::jsonb,$6,$7,$8)`, eventID, record.CabinID, record.Version,
		CabinPhotoTopic, string(body), body, checksum, record.ChangedAt)
	return translateConstraint(err)
}

// associateProcessedCabinImage makes direct CABIN uploads/imports participate
// in the canonical gallery. A newer READY direct-upload folder becomes active,
// while a delayed completion from an older folder cannot reclaim the pointer.
// Within the selected folder the earliest READY association is the cover,
// independently of processing completion order. Reprocessing a non-direct
// current cover refreshes its generation fact without changing either pointer.
func (repository *Repository) associateProcessedCabinImage(
	ctx context.Context,
	tx pgx.Tx,
	asset AssetRecord,
	correlationID uuid.UUID,
) error {
	if asset.Kind != media.KindImage || asset.Status != media.StatusReady || asset.Generation <= 0 {
		return nil
	}
	now := repository.now().UTC().Truncate(time.Microsecond)
	var cabinID, associationWarehouseID, galleryFolderID uuid.UUID
	var associatedEntryID *uuid.UUID
	var associationSource string
	var previousAssociationGeneration int
	err := tx.QueryRow(ctx, `select cabin_id,warehouse_id,task_board_entry_id,gallery_folder_id,
		association_source,media_generation
		from media_cabin_photo where media_id=$1 for update`, asset.ID).
		Scan(&cabinID, &associationWarehouseID, &associatedEntryID, &galleryFolderID,
			&associationSource, &previousAssociationGeneration)
	if errors.Is(err, pgx.ErrNoRows) {
		if asset.OwnerType != OwnerTypeCabin {
			return nil
		}
		cabinID, err = uuid.Parse(asset.OwnerID)
		if err != nil || cabinID == uuid.Nil {
			return ErrConflict
		}
		associationWarehouseID = asset.WarehouseID
		galleryFolderID = asset.FolderID
		associationSource = "DIRECT"
		_, err = tx.Exec(ctx, `insert into media_cabin_photo (
			cabin_id,media_id,warehouse_id,media_generation,task_board_entry_id,
			association_source,gallery_folder_id,sort_order,attached_at)
		values ($1,$2,$3,$4,null,'DIRECT',$5,$6,$7)`,
			cabinID, asset.ID, associationWarehouseID, asset.Generation,
			galleryFolderID, asset.SortOrder, asset.CreatedAt)
		if err != nil {
			return translateConstraint(err)
		}
	} else if err != nil {
		return err
	} else {
		if _, err := tx.Exec(ctx, `update media_cabin_photo
			set media_generation=$2 where media_id=$1`, asset.ID, asset.Generation); err != nil {
			return err
		}
	}
	var currentCover *uuid.UUID
	var activeFolder *uuid.UUID
	var libraryWarehouseID uuid.UUID
	var version int64
	err = tx.QueryRow(ctx, `select warehouse_id,cover_media_id,active_gallery_folder_id,version
		from media_cabin_photo_library where cabin_id=$1 for update`,
		cabinID).Scan(&libraryWarehouseID, &currentCover, &activeFolder, &version)
	if errors.Is(err, pgx.ErrNoRows) {
		version = 0
		libraryWarehouseID = associationWarehouseID
		if _, err := tx.Exec(ctx, `insert into media_cabin_photo_library (
			cabin_id,warehouse_id,cover_media_id,version,updated_at,active_gallery_folder_id)
			values ($1,$2,null,0,$3,null)`, cabinID, associationWarehouseID, now); err != nil {
			return translateConstraint(err)
		}
	} else if err != nil {
		return err
	}
	if libraryWarehouseID != associationWarehouseID {
		return ErrConflict
	}
	var desiredCover uuid.UUID
	var desiredGeneration int
	var desiredEntryID *uuid.UUID
	if associationSource != "DIRECT" {
		if currentCover == nil || activeFolder == nil || *currentCover != asset.ID ||
			*activeFolder != galleryFolderID || previousAssociationGeneration == asset.Generation {
			return nil
		}
		desiredCover = asset.ID
		desiredGeneration = asset.Generation
		desiredEntryID = associatedEntryID
	} else {
		var incomingLatest time.Time
		var found bool
		desiredCover, desiredGeneration, desiredEntryID, incomingLatest, found, err =
			selectReadyCabinFolderCover(ctx, tx, cabinID, galleryFolderID)
		if err != nil {
			return err
		}
		if !found {
			return nil
		}
		selectIncomingFolder := activeFolder == nil || *activeFolder == galleryFolderID
		if !selectIncomingFolder {
			activeLatest, activeFound, activeErr :=
				selectCabinFolderLatestAttachedAt(ctx, tx, cabinID, *activeFolder)
			if activeErr != nil {
				return activeErr
			}
			if !activeFound {
				return ErrConflict
			}
			selectIncomingFolder = incomingLatest.After(activeLatest) ||
				(incomingLatest.Equal(activeLatest) && galleryFolderID.String() > activeFolder.String())
		}
		if !selectIncomingFolder {
			return nil
		}
	}
	pointerChanged := currentCover == nil || activeFolder == nil ||
		*currentCover != desiredCover || *activeFolder != galleryFolderID
	coverGenerationChanged := !pointerChanged && desiredCover == asset.ID &&
		previousAssociationGeneration != asset.Generation
	if !pointerChanged && !coverGenerationChanged {
		return nil
	}
	var previousMediaID *uuid.UUID
	if currentCover != nil {
		copyOfCurrent := *currentCover
		previousMediaID = &copyOfCurrent
	}
	newVersion := version + 1
	updated, err := tx.Exec(ctx, `update media_cabin_photo_library
		set warehouse_id=$2,cover_media_id=$3,version=$4,updated_at=$5,
			active_gallery_folder_id=$6
		where cabin_id=$1 and version=$7`, cabinID, associationWarehouseID,
		desiredCover, newVersion, now, galleryFolderID, version)
	if err != nil {
		return translateConstraint(err)
	}
	if updated.RowsAffected() != 1 {
		return ErrConflict
	}
	_, err = tx.Exec(ctx, `insert into media_cabin_cover_history (
		cabin_id,version,warehouse_id,previous_media_id,cover_media_id,
		task_board_entry_id,idempotency_key,changed_at)
	values ($1,$2,$3,$4,$5,$6,null,$7)`, cabinID, newVersion, associationWarehouseID,
		previousMediaID, desiredCover, desiredEntryID, now)
	if err != nil {
		return translateConstraint(err)
	}
	record := CabinCoverChangeRecord{
		CabinID: cabinID, WarehouseID: associationWarehouseID, MediaID: desiredCover,
		Generation: desiredGeneration, Version: newVersion, ChangedAt: now,
	}
	if desiredEntryID != nil {
		record.TaskBoardEntryID = *desiredEntryID
	}
	return insertCabinCoverFact(ctx, tx, record, previousMediaID, correlationID)
}

// selectReadyCabinFolderCover returns one folder's deterministic earliest
// READY, current-generation, non-deleted, available image together with the
// newest retained association timestamp used to order gallery folders. Folder
// recency deliberately includes images that are still processing so completion
// order cannot make an older upload batch appear newer.
func selectReadyCabinFolderCover(
	ctx context.Context,
	tx pgx.Tx,
	cabinID, galleryFolderID uuid.UUID,
) (uuid.UUID, int, *uuid.UUID, time.Time, bool, error) {
	var mediaID uuid.UUID
	var generation int
	var taskBoardEntryID *uuid.UUID
	var latestAttachedAt time.Time
	err := tx.QueryRow(ctx, `select photo.media_id,asset.current_generation,
		photo.task_board_entry_id,(
			select max(folder_photo.attached_at)
			from media_cabin_photo folder_photo
			where folder_photo.cabin_id=photo.cabin_id
			  and folder_photo.gallery_folder_id=photo.gallery_folder_id
		)
		from media_cabin_photo photo
		join media_asset asset on asset.media_id=photo.media_id
		where photo.cabin_id=$1 and photo.gallery_folder_id=$2
		  and photo.media_generation=asset.current_generation
		  and asset.media_kind='IMAGE' and asset.processing_status='READY'
		  and asset.current_generation>0 and asset.deleted_at is null
		  and media_asset_is_available(asset.media_id)
		order by photo.sort_order,photo.attached_at,photo.media_id
		limit 1`, cabinID, galleryFolderID).
		Scan(&mediaID, &generation, &taskBoardEntryID, &latestAttachedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return uuid.Nil, 0, nil, time.Time{}, false, nil
	}
	if err != nil {
		return uuid.Nil, 0, nil, time.Time{}, false, err
	}
	return mediaID, generation, taskBoardEntryID, latestAttachedAt, true, nil
}

// selectCabinFolderLatestAttachedAt returns the immutable recency fence for a
// retained CABIN gallery folder, including associations whose processing has
// not completed yet. A selected library folder without an association is a
// persistence invariant violation and callers fail closed.
func selectCabinFolderLatestAttachedAt(
	ctx context.Context,
	tx pgx.Tx,
	cabinID, galleryFolderID uuid.UUID,
) (time.Time, bool, error) {
	var latestAttachedAt time.Time
	err := tx.QueryRow(ctx, `select max(attached_at)
		from media_cabin_photo
		where cabin_id=$1 and gallery_folder_id=$2
		having count(*)>0`, cabinID, galleryFolderID).Scan(&latestAttachedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, false, nil
	}
	if err != nil {
		return time.Time{}, false, err
	}
	return latestAttachedAt, true, nil
}

func readCabinPhotoAssets(
	ctx context.Context,
	tx pgx.Tx,
	ownerID string,
	warehouseID uuid.UUID,
	limit int,
	after *uuid.UUID,
	now func() time.Time,
) ([]AssetWithVariants, error) {
	cabinID, err := uuid.Parse(ownerID)
	if err != nil || cabinID == uuid.Nil || warehouseID == uuid.Nil || limit < 1 || limit > 100 {
		return nil, ErrConflict
	}
	var cursorSort int64
	var cursorAttached time.Time
	var cursorID uuid.UUID
	if after != nil {
		err := tx.QueryRow(ctx, `select sort_order,attached_at,media_id
			from media_cabin_photo where cabin_id=$1 and warehouse_id=$2 and media_id=$3`,
			cabinID, warehouseID, *after).Scan(&cursorSort, &cursorAttached, &cursorID)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrConflict
		}
		if err != nil {
			return nil, err
		}
	}
	rows, err := tx.Query(ctx, `/* media_public_cabin_gallery */
		with authorized_assets as materialized (
			select asset.media_id,photo.gallery_folder_id,asset.owner_type,asset.owner_id,
				asset.warehouse_id,asset.media_kind,asset.original_file_name,
				asset.original_content_type,asset.source_object_key,
				coalesce(asset.source_version_id,''),coalesce(asset.source_etag,''),
				coalesce(asset.source_checksum_sha256,''),asset.processing_status,
				asset.version,asset.current_generation,asset.rotation_degrees,
				photo.sort_order,asset.size_bytes,photo.attached_at as created_at
			from media_cabin_photo photo
			join media_asset asset on asset.media_id=photo.media_id
			join media_owner_binding binding
			  on binding.owner_type='CABIN' and binding.owner_id=photo.cabin_id::text
			 and binding.warehouse_id=photo.warehouse_id and binding.active
			join media_consumer_aggregate_checkpoint checkpoint
			  on checkpoint.consumer_name=binding.proof_consumer_name
			 and checkpoint.aggregate_type=binding.proof_aggregate_type
			 and checkpoint.aggregate_id=binding.proof_aggregate_id
			 and checkpoint.aggregate_version>=binding.proof_aggregate_version
			where photo.cabin_id=$1 and photo.warehouse_id=$2
			  and asset.media_kind='IMAGE' and asset.deleted_at is null
			  and media_asset_is_available(asset.media_id)
			  and not exists (select 1 from media_quarantined_aggregate quarantine
			    where quarantine.consumer_name=binding.proof_consumer_name
			      and quarantine.aggregate_type=binding.proof_aggregate_type
			      and quarantine.aggregate_id=binding.proof_aggregate_id
			      and quarantine.reconciled_at is null)
			  and ($3::uuid is null or (photo.sort_order,photo.attached_at,photo.media_id)
			    > ($4::bigint,$5::timestamptz,$3::uuid))
			order by photo.sort_order,photo.attached_at,photo.media_id
			limit $6 for share of binding
		)
		select asset.*,(variant.media_id is not null),coalesce(variant.variant,''),
			coalesce(variant.object_key,''),coalesce(variant.object_version_id,''),
			coalesce(variant.content_type,''),coalesce(variant.size_bytes,0),
			variant.width,variant.height,coalesce(variant.checksum_sha256,'')
		from authorized_assets asset
		left join media_variant variant on variant.media_id=asset.media_id
		 and variant.generation=asset.current_generation and variant.variant<>'ORIGINAL'
		 and asset.processing_status='READY'
		order by asset.sort_order,asset.created_at,asset.media_id,variant.variant`,
		cabinID, warehouseID, after, cursorSort, cursorAttached, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var records []AssetWithVariants
	byID := make(map[uuid.UUID]int)
	for rows.Next() {
		var asset AssetRecord
		var hasVariant bool
		var variant VariantRecord
		var variantName string
		if err := rows.Scan(&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID,
			&asset.WarehouseID, &asset.Kind, &asset.FileName, &asset.ContentType,
			&asset.SourceObjectKey, &asset.SourceVersionID, &asset.SourceETag,
			&asset.SourceChecksum, &asset.Status, &asset.Version, &asset.Generation,
			&asset.Rotation, &asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt,
			&hasVariant, &variantName, &variant.ObjectKey, &variant.ObjectVersionID,
			&variant.ContentType, &variant.SizeBytes, &variant.Width, &variant.Height,
			&variant.Checksum); err != nil {
			return nil, err
		}
		index, exists := byID[asset.ID]
		if !exists {
			index = len(records)
			byID[asset.ID] = index
			records = append(records, AssetWithVariants{Asset: asset})
		}
		if hasVariant {
			variant.Variant = media.Variant(variantName)
			records[index].Variants = append(records[index].Variants, variant)
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(records) == 0 {
		if err := requireOwnerBinding(ctx, tx, OwnerTypeCabin, cabinID.String(),
			warehouseID, now()); err != nil {
			return nil, err
		}
	}
	return records, nil
}

// ReadCabinPhotoVariant reads one current, READY CABIN image derivative while
// share-locking owner evidence through consume.
func (repository *Repository) ReadCabinPhotoVariant(
	ctx context.Context,
	cabinID, warehouseID, mediaID uuid.UUID,
	generation int,
	requestedVariant media.Variant,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if cabinID == uuid.Nil || warehouseID == uuid.Nil || mediaID == uuid.Nil ||
		generation <= 0 || consume == nil {
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var asset AssetRecord
	var variant VariantRecord
	var variantName string
	err = tx.QueryRow(ctx, `/* media_cabin_photo_variant */
		select asset.media_id,asset.folder_id,asset.owner_type,asset.owner_id,
			asset.warehouse_id,asset.media_kind,asset.original_file_name,
			asset.original_content_type,asset.source_object_key,
			coalesce(asset.source_version_id,''),coalesce(asset.source_etag,''),
			coalesce(asset.source_checksum_sha256,''),asset.processing_status,
			asset.version,asset.current_generation,asset.rotation_degrees,
			photo.sort_order,asset.size_bytes,photo.attached_at,
			variant.variant,variant.object_key,variant.object_version_id,
			variant.content_type,variant.size_bytes,variant.width,variant.height,
			variant.checksum_sha256
		from media_cabin_photo photo
		join media_asset asset on asset.media_id=photo.media_id
		join media_variant variant on variant.media_id=asset.media_id
		 and variant.generation=$4 and variant.variant=$5
		 and variant.object_version_id<>''
		join media_owner_binding binding
		  on binding.owner_type='CABIN' and binding.owner_id=photo.cabin_id::text
		 and binding.warehouse_id=photo.warehouse_id and binding.active
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where photo.cabin_id=$1 and photo.warehouse_id=$2 and photo.media_id=$3
		  and photo.media_generation=$4 and asset.current_generation=$4
		  and asset.media_kind='IMAGE' and asset.processing_status='READY'
		  and asset.deleted_at is null and media_asset_is_available(asset.media_id)
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=binding.proof_consumer_name
		      and quarantine.aggregate_type=binding.proof_aggregate_type
		      and quarantine.aggregate_id=binding.proof_aggregate_id
		      and quarantine.reconciled_at is null)
		for share of binding`, cabinID, warehouseID, mediaID, generation,
		requestedVariant).Scan(
		&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID,
		&asset.WarehouseID, &asset.Kind, &asset.FileName, &asset.ContentType,
		&asset.SourceObjectKey, &asset.SourceVersionID, &asset.SourceETag,
		&asset.SourceChecksum, &asset.Status, &asset.Version, &asset.Generation,
		&asset.Rotation, &asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt,
		&variantName, &variant.ObjectKey, &variant.ObjectVersionID,
		&variant.ContentType, &variant.SizeBytes, &variant.Width, &variant.Height,
		&variant.Checksum)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	variant.Variant = media.Variant(variantName)
	if err := consume(asset, &variant); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
