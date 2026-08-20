package persistence

import (
	"context"
	"errors"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// InventoryCabinPhotoReference identifies one exact current media generation
// selected by a completed inventory finding.
type InventoryCabinPhotoReference struct {
	MediaID    uuid.UUID
	Generation int
}

// ApplyInventoryCabinPhotosCommand is the immutable inventory outcome used to
// select one current CABIN gallery folder without re-owning media assets.
type ApplyInventoryCabinPhotosCommand struct {
	InventoryID      uuid.UUID
	FindingID        uuid.UUID
	WarehouseID      uuid.UUID
	CabinID          uuid.UUID
	CompletedAt      time.Time
	SourceRevision   int64
	FinalPlanVersion int64
	FinalPlanSHA256  string
	CoverMediaID     uuid.UUID
	MediaReferences  []InventoryCabinPhotoReference
	IdempotencyKey   uuid.UUID
	RequestSHA256    string
	CorrelationID    uuid.UUID
}

// InventoryCabinPhotoResult is the frozen receipt returned for a completed
// inventory gallery selection. Internal presentation fields support safe
// invalidation without expanding the transport response.
type InventoryCabinPhotoResult struct {
	InventoryID     uuid.UUID
	FindingID       uuid.UUID
	CabinID         uuid.UUID
	FolderID        uuid.UUID
	CoverMediaID    uuid.UUID
	PhotoCount      int
	LibraryVersion  int64
	WarehouseID     uuid.UUID
	CoverGeneration int
	ChangedAt       time.Time
}

// ApplyInventoryCabinPhotos validates both active owners and every exact READY
// image in one serializable media-owned transaction. It retains prior folders,
// media rows, variants and MinIO versions while moving only the active folder
// and cover pointers. The booleans report exact-key replay and a new cover
// transition respectively.
func (repository *Repository) ApplyInventoryCabinPhotos(
	ctx context.Context,
	command ApplyInventoryCabinPhotosCommand,
) (InventoryCabinPhotoResult, bool, bool, error) {
	command.CompletedAt = command.CompletedAt.UTC().Truncate(time.Microsecond)
	if err := validateInventoryCabinPhotoCommand(command); err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	folderID := inventoryGalleryFolderID(command.RequestSHA256)
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	defer tx.Rollback(ctx)

	locks := []string{
		"inventory-cabin-photo:cabin:" + command.CabinID.String(),
		"inventory-cabin-photo:idempotency:" + command.IdempotencyKey.String(),
	}
	sort.Strings(locks)
	for _, lock := range locks {
		if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`, lock); err != nil {
			return InventoryCabinPhotoResult{}, false, false, err
		}
	}

	replay, found, err := readInventoryCabinPhotoReceipt(ctx, tx, command)
	if err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	if found {
		if err := tx.Commit(ctx); err != nil {
			return InventoryCabinPhotoResult{}, false, false, err
		}
		return replay, true, false, nil
	}
	if err := lockInventoryCabinPhotoOwners(ctx, tx, command); err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	coverGeneration, err := lockInventoryCabinPhotoReferences(ctx, tx, command)
	if err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	if err := validateInventoryCabinPhotoWatermark(ctx, tx, command); err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	now := repository.now().UTC().Truncate(time.Microsecond)
	if err := associateInventoryCabinPhotos(ctx, tx, command, folderID); err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}

	version, previousMediaID, changed, err := selectInventoryCabinPhotoFolder(
		ctx, tx, command, folderID, now)
	if err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	result := InventoryCabinPhotoResult{
		InventoryID: command.InventoryID, FindingID: command.FindingID,
		CabinID: command.CabinID, FolderID: folderID,
		CoverMediaID: command.CoverMediaID, PhotoCount: len(command.MediaReferences),
		LibraryVersion: version, WarehouseID: command.WarehouseID,
		CoverGeneration: coverGeneration, ChangedAt: now,
	}
	if changed {
		record := CabinCoverChangeRecord{
			CabinID: command.CabinID, WarehouseID: command.WarehouseID,
			MediaID: command.CoverMediaID, Generation: coverGeneration,
			Version: version, ChangedAt: now,
		}
		if err := insertCabinCoverFact(ctx, tx, record, previousMediaID, command.CorrelationID); err != nil {
			return InventoryCabinPhotoResult{}, false, false, err
		}
	}
	if err := writeInventoryCabinPhotoWatermark(ctx, tx, command, folderID, now); err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	if err := insertInventoryCabinPhotoReceipt(ctx, tx, command, result); err != nil {
		return InventoryCabinPhotoResult{}, false, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return InventoryCabinPhotoResult{}, false, false, translateConstraint(err)
	}
	return result, false, changed, nil
}

func validateInventoryCabinPhotoCommand(command ApplyInventoryCabinPhotosCommand) error {
	if command.InventoryID == uuid.Nil || command.FindingID == uuid.Nil ||
		command.WarehouseID == uuid.Nil || command.CabinID == uuid.Nil ||
		command.CompletedAt.IsZero() || command.SourceRevision < 1 ||
		command.FinalPlanVersion < 1 || !validSHA256(command.FinalPlanSHA256) ||
		command.CoverMediaID == uuid.Nil || command.IdempotencyKey == uuid.Nil ||
		command.CorrelationID == uuid.Nil || !validSHA256(command.RequestSHA256) ||
		len(command.MediaReferences) < 1 || len(command.MediaReferences) > 100 {
		return ErrConflict
	}
	seen := make(map[uuid.UUID]struct{}, len(command.MediaReferences))
	coverFound := false
	for _, reference := range command.MediaReferences {
		if reference.MediaID == uuid.Nil || reference.Generation < 1 {
			return ErrConflict
		}
		if _, duplicate := seen[reference.MediaID]; duplicate {
			return ErrConflict
		}
		seen[reference.MediaID] = struct{}{}
		coverFound = coverFound || reference.MediaID == command.CoverMediaID
	}
	if !coverFound {
		return ErrConflict
	}
	return nil
}

func inventoryGalleryFolderID(requestSHA256 string) uuid.UUID {
	return uuid.NewSHA1(uuid.NameSpaceOID,
		[]byte("rwms:media:inventory-cabin-photo-folder:v1:"+requestSHA256))
}

func readInventoryCabinPhotoReceipt(
	ctx context.Context,
	tx pgx.Tx,
	command ApplyInventoryCabinPhotosCommand,
) (InventoryCabinPhotoResult, bool, error) {
	var requestSHA string
	var result InventoryCabinPhotoResult
	err := tx.QueryRow(ctx, `select request_sha256,inventory_id,finding_id,cabin_id,
		gallery_folder_id,cover_media_id,photo_count,library_version,warehouse_id,applied_at
		from media_inventory_cabin_photo_receipt
		where idempotency_key=$1 for update`, command.IdempotencyKey).Scan(
		&requestSHA, &result.InventoryID, &result.FindingID, &result.CabinID,
		&result.FolderID, &result.CoverMediaID, &result.PhotoCount,
		&result.LibraryVersion, &result.WarehouseID, &result.ChangedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return InventoryCabinPhotoResult{}, false, nil
	}
	if err != nil {
		return InventoryCabinPhotoResult{}, false, err
	}
	if requestSHA != command.RequestSHA256 || result.InventoryID != command.InventoryID ||
		result.FindingID != command.FindingID || result.CabinID != command.CabinID {
		return InventoryCabinPhotoResult{}, false, ErrIdempotencyMismatch
	}
	if err := tx.QueryRow(ctx, `select media_generation from media_cabin_photo
		where cabin_id=$1 and media_id=$2 and gallery_folder_id=$3`,
		result.CabinID, result.CoverMediaID, result.FolderID).Scan(&result.CoverGeneration); err != nil {
		return InventoryCabinPhotoResult{}, false, err
	}
	return result, true, nil
}

func lockInventoryCabinPhotoOwners(
	ctx context.Context,
	tx pgx.Tx,
	command ApplyInventoryCabinPhotosCommand,
) error {
	var warehouseID uuid.UUID
	err := tx.QueryRow(ctx, `select cabin.warehouse_id
		from media_owner_binding cabin
		join media_consumer_aggregate_checkpoint cabin_checkpoint
		  on cabin_checkpoint.consumer_name=cabin.proof_consumer_name
		 and cabin_checkpoint.aggregate_type=cabin.proof_aggregate_type
		 and cabin_checkpoint.aggregate_id=cabin.proof_aggregate_id
		 and cabin_checkpoint.aggregate_version>=cabin.proof_aggregate_version
		join media_owner_binding finding
		  on finding.owner_type='INVENTORY_FINDING'
		 and finding.owner_id=$2::text and finding.warehouse_id=cabin.warehouse_id
		 and finding.active
		join media_consumer_aggregate_checkpoint finding_checkpoint
		  on finding_checkpoint.consumer_name=finding.proof_consumer_name
		 and finding_checkpoint.aggregate_type=finding.proof_aggregate_type
		 and finding_checkpoint.aggregate_id=finding.proof_aggregate_id
		 and finding_checkpoint.aggregate_version>=finding.proof_aggregate_version
		where cabin.owner_type='CABIN' and cabin.owner_id=$1::text
		  and cabin.warehouse_id=$3 and cabin.active
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=cabin.proof_consumer_name
		      and quarantine.aggregate_type=cabin.proof_aggregate_type
		      and quarantine.aggregate_id=cabin.proof_aggregate_id
		      and quarantine.reconciled_at is null)
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=finding.proof_consumer_name
		      and quarantine.aggregate_type=finding.proof_aggregate_type
		      and quarantine.aggregate_id=finding.proof_aggregate_id
		      and quarantine.reconciled_at is null)
		for share of cabin,finding`, command.CabinID, command.FindingID,
		command.WarehouseID).Scan(&warehouseID)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrConflict
	}
	return err
}

func lockInventoryCabinPhotoReferences(
	ctx context.Context,
	tx pgx.Tx,
	command ApplyInventoryCabinPhotosCommand,
) (int, error) {
	mediaIDs := make([]uuid.UUID, len(command.MediaReferences))
	generations := make([]int, len(command.MediaReferences))
	for index, reference := range command.MediaReferences {
		mediaIDs[index] = reference.MediaID
		generations[index] = reference.Generation
	}
	rows, err := tx.Query(ctx, `with requested(media_id,generation,position) as (
		select media_id,generation,position
		from unnest($1::uuid[],$2::integer[]) with ordinality
		  as value(media_id,generation,position)
	)
	select requested.media_id,requested.generation
	from requested
	join media_asset asset
	  on asset.media_id=requested.media_id
	 and asset.owner_type='INVENTORY_FINDING' and asset.owner_id=$3::text
	 and asset.warehouse_id=$4 and asset.media_kind='IMAGE'
	 and asset.processing_status='READY'
	 and asset.current_generation=requested.generation
	 and asset.current_generation>0 and asset.deleted_at is null
	 and media_asset_is_available(asset.media_id)
	order by requested.position for share of asset`, mediaIDs, generations,
		command.FindingID, command.WarehouseID)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	matched := 0
	coverGeneration := 0
	for rows.Next() {
		var mediaID uuid.UUID
		var generation int
		if err := rows.Scan(&mediaID, &generation); err != nil {
			return 0, err
		}
		matched++
		if mediaID == command.CoverMediaID {
			coverGeneration = generation
		}
	}
	if err := rows.Err(); err != nil {
		return 0, err
	}
	if matched != len(command.MediaReferences) || coverGeneration < 1 {
		return 0, ErrConflict
	}
	return coverGeneration, nil
}

func validateInventoryCabinPhotoWatermark(
	ctx context.Context,
	tx pgx.Tx,
	command ApplyInventoryCabinPhotosCommand,
) error {
	var completedAt time.Time
	var requestSHA string
	err := tx.QueryRow(ctx, `select completed_at,request_sha256
		from media_inventory_cabin_photo_watermark where cabin_id=$1 for update`,
		command.CabinID).Scan(&completedAt, &requestSHA)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if command.CompletedAt.Before(completedAt) ||
		(command.CompletedAt.Equal(completedAt) && requestSHA != command.RequestSHA256) {
		return ErrConflict
	}
	return nil
}

func associateInventoryCabinPhotos(
	ctx context.Context,
	tx pgx.Tx,
	command ApplyInventoryCabinPhotosCommand,
	folderID uuid.UUID,
) error {
	var firstSortOrder int64
	if err := tx.QueryRow(ctx, `select coalesce(max(sort_order),-1)+1
		from media_cabin_photo where cabin_id=$1`, command.CabinID).Scan(&firstSortOrder); err != nil {
		return err
	}
	for index, reference := range command.MediaReferences {
		_, err := tx.Exec(ctx, `insert into media_cabin_photo (
			cabin_id,media_id,warehouse_id,media_generation,task_board_entry_id,
			association_source,gallery_folder_id,sort_order,attached_at,
			inventory_id,inventory_finding_id,inventory_completed_at,
			inventory_source_revision,inventory_final_plan_version,
			inventory_final_plan_sha256)
		values ($1,$2,$3,$4,null,'INVENTORY',$5,$6,$7,$8,$9,$7,$10,$11,$12)
		on conflict (cabin_id,media_id) do nothing`, command.CabinID,
			reference.MediaID, command.WarehouseID, reference.Generation, folderID,
			firstSortOrder+int64(index), command.CompletedAt, command.InventoryID,
			command.FindingID, command.SourceRevision, command.FinalPlanVersion,
			command.FinalPlanSHA256)
		if err != nil {
			return translateConstraint(err)
		}
		var source, planSHA string
		var actualFolder, inventoryID, findingID uuid.UUID
		var generation int
		var completedAt time.Time
		var sourceRevision, planVersion int64
		err = tx.QueryRow(ctx, `select association_source,gallery_folder_id,
			media_generation,inventory_id,inventory_finding_id,inventory_completed_at,
			inventory_source_revision,inventory_final_plan_version,
			inventory_final_plan_sha256
			from media_cabin_photo where cabin_id=$1 and media_id=$2 for update`,
			command.CabinID, reference.MediaID).Scan(&source, &actualFolder,
			&generation, &inventoryID, &findingID, &completedAt, &sourceRevision,
			&planVersion, &planSHA)
		if err != nil {
			return err
		}
		if source != "INVENTORY" || actualFolder != folderID ||
			generation != reference.Generation || inventoryID != command.InventoryID ||
			findingID != command.FindingID || !completedAt.Equal(command.CompletedAt) ||
			sourceRevision != command.SourceRevision ||
			planVersion != command.FinalPlanVersion || planSHA != command.FinalPlanSHA256 {
			return ErrConflict
		}
	}
	return nil
}

func selectInventoryCabinPhotoFolder(
	ctx context.Context,
	tx pgx.Tx,
	command ApplyInventoryCabinPhotosCommand,
	folderID uuid.UUID,
	now time.Time,
) (int64, *uuid.UUID, bool, error) {
	var currentCover, activeFolder *uuid.UUID
	var version int64
	err := tx.QueryRow(ctx, `select cover_media_id,active_gallery_folder_id,version
		from media_cabin_photo_library where cabin_id=$1 for update`, command.CabinID).
		Scan(&currentCover, &activeFolder, &version)
	if errors.Is(err, pgx.ErrNoRows) {
		if _, err := tx.Exec(ctx, `insert into media_cabin_photo_library (
			cabin_id,warehouse_id,cover_media_id,version,updated_at,active_gallery_folder_id)
		values ($1,$2,null,0,$3,null)`, command.CabinID, command.WarehouseID, now); err != nil {
			return 0, nil, false, translateConstraint(err)
		}
		version = 0
	} else if err != nil {
		return 0, nil, false, err
	}
	var previousMediaID *uuid.UUID
	if currentCover != nil {
		copyOfCurrent := *currentCover
		previousMediaID = &copyOfCurrent
	}
	if currentCover != nil && activeFolder != nil &&
		*currentCover == command.CoverMediaID && *activeFolder == folderID {
		return version, previousMediaID, false, nil
	}
	newVersion := version + 1
	updated, err := tx.Exec(ctx, `update media_cabin_photo_library
		set warehouse_id=$2,cover_media_id=$3,active_gallery_folder_id=$4,
			version=$5,updated_at=$6
		where cabin_id=$1 and version=$7`, command.CabinID, command.WarehouseID,
		command.CoverMediaID, folderID, newVersion, now, version)
	if err != nil {
		return 0, nil, false, translateConstraint(err)
	}
	if updated.RowsAffected() != 1 {
		return 0, nil, false, ErrConflict
	}
	_, err = tx.Exec(ctx, `insert into media_cabin_cover_history (
		cabin_id,version,warehouse_id,previous_media_id,cover_media_id,
		task_board_entry_id,idempotency_key,changed_at)
	values ($1,$2,$3,$4,$5,null,$6,$7)`, command.CabinID, newVersion,
		command.WarehouseID, previousMediaID, command.CoverMediaID,
		command.IdempotencyKey, now)
	if err != nil {
		return 0, nil, false, translateConstraint(err)
	}
	return newVersion, previousMediaID, true, nil
}

func writeInventoryCabinPhotoWatermark(
	ctx context.Context,
	tx pgx.Tx,
	command ApplyInventoryCabinPhotosCommand,
	folderID uuid.UUID,
	now time.Time,
) error {
	_, err := tx.Exec(ctx, `insert into media_inventory_cabin_photo_watermark (
		cabin_id,warehouse_id,completed_at,request_sha256,inventory_id,finding_id,
		source_revision,final_plan_version,final_plan_sha256,gallery_folder_id,
		cover_media_id,applied_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
	on conflict (cabin_id) do update set
		warehouse_id=excluded.warehouse_id,completed_at=excluded.completed_at,
		request_sha256=excluded.request_sha256,inventory_id=excluded.inventory_id,
		finding_id=excluded.finding_id,source_revision=excluded.source_revision,
		final_plan_version=excluded.final_plan_version,
		final_plan_sha256=excluded.final_plan_sha256,
		gallery_folder_id=excluded.gallery_folder_id,
		cover_media_id=excluded.cover_media_id,applied_at=excluded.applied_at`,
		command.CabinID, command.WarehouseID, command.CompletedAt,
		command.RequestSHA256, command.InventoryID, command.FindingID,
		command.SourceRevision, command.FinalPlanVersion, command.FinalPlanSHA256,
		folderID, command.CoverMediaID, now)
	return translateConstraint(err)
}

func insertInventoryCabinPhotoReceipt(
	ctx context.Context,
	tx pgx.Tx,
	command ApplyInventoryCabinPhotosCommand,
	result InventoryCabinPhotoResult,
) error {
	_, err := tx.Exec(ctx, `insert into media_inventory_cabin_photo_receipt (
		idempotency_key,request_sha256,inventory_id,finding_id,cabin_id,warehouse_id,
		completed_at,source_revision,final_plan_version,final_plan_sha256,
		gallery_folder_id,cover_media_id,photo_count,library_version,correlation_id,applied_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)`,
		command.IdempotencyKey, command.RequestSHA256, command.InventoryID,
		command.FindingID, command.CabinID, command.WarehouseID,
		command.CompletedAt, command.SourceRevision, command.FinalPlanVersion,
		command.FinalPlanSHA256, result.FolderID, command.CoverMediaID,
		result.PhotoCount, result.LibraryVersion, command.CorrelationID,
		result.ChangedAt)
	return translateConstraint(err)
}
