package persistence

import (
	"context"
	"errors"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

type DeleteCommand struct {
	MediaID         uuid.UUID
	OwnerType       string
	OwnerID         string
	WarehouseID     uuid.UUID
	SubjectID       uuid.UUID
	IdempotencyKey  uuid.UUID
	RequestSHA256   string
	ExpectedVersion int64
	CorrelationID   uuid.UUID
}

// Delete is an owner-scoped logical transition. It deliberately leaves source
// objects, immutable versions and derived variant rows untouched. The command,
// domain event and one public DELETED fact commit atomically.
func (repository *Repository) Delete(
	ctx context.Context,
	command DeleteCommand,
) (AssetRecord, bool, error) {
	if command.MediaID == uuid.Nil || command.SubjectID == uuid.Nil ||
		command.IdempotencyKey == uuid.Nil || command.WarehouseID == uuid.Nil ||
		!IsPublicOwnerType(command.OwnerType) || command.OwnerID == "" ||
		!validSHA256(command.RequestSHA256) || command.ExpectedVersion <= 0 {
		return AssetRecord{}, false, ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return AssetRecord{}, false, err
	}
	defer tx.Rollback(ctx)
	if err := lockCommand(ctx, tx, command.SubjectID, "DELETE", command.IdempotencyKey); err != nil {
		return AssetRecord{}, false, err
	}

	var persistedSHA string
	var persistedMediaID uuid.UUID
	err = tx.QueryRow(ctx, `select request_sha256,media_id from media_command_idempotency
		where principal_type='USER' and subject_id=$1 and command_type='DELETE' and idempotency_key=$2 for update`,
		command.SubjectID, command.IdempotencyKey).Scan(&persistedSHA, &persistedMediaID)
	if err == nil {
		if persistedSHA != command.RequestSHA256 || persistedMediaID != command.MediaID {
			return AssetRecord{}, false, ErrIdempotencyMismatch
		}
		asset, loadErr := repository.assetForUpdate(ctx, tx, persistedMediaID)
		if loadErr != nil {
			return AssetRecord{}, false, loadErr
		}
		if !assetOwnedBy(asset, command.OwnerType, command.OwnerID, command.WarehouseID) {
			return AssetRecord{}, false, ErrNotFound
		}
		if asset.Status != media.StatusDeleted || asset.Version != command.ExpectedVersion+1 {
			return AssetRecord{}, false, ErrConflict
		}
		if err := requireOwnerBinding(ctx, tx, asset.OwnerType, asset.OwnerID,
			asset.WarehouseID, repository.now()); err != nil {
			return AssetRecord{}, false, err
		}
		if err := tx.Commit(ctx); err != nil {
			return AssetRecord{}, false, err
		}
		return asset, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, err
	}

	asset, err := repository.assetForUpdate(ctx, tx, command.MediaID)
	if err != nil {
		return AssetRecord{}, false, err
	}
	if !assetOwnedBy(asset, command.OwnerType, command.OwnerID, command.WarehouseID) {
		return AssetRecord{}, false, ErrNotFound
	}
	if err := requireOwnerBinding(ctx, tx, asset.OwnerType, asset.OwnerID,
		asset.WarehouseID, repository.now()); err != nil {
		return AssetRecord{}, false, err
	}
	if asset.Status == media.StatusDeleted || asset.Version != command.ExpectedVersion {
		return AssetRecord{}, false, ErrConflict
	}
	now := repository.now().UTC()
	if _, err := tx.Exec(ctx, `update media_processing_job set
		job_status='FAILED',last_error='MEDIA_DELETED',completed_at=$2,
		lease_owner=null,lease_token=null,lease_until=null
		where media_id=$1 and job_status in ('PENDING','RUNNING')`, asset.ID, now); err != nil {
		return AssetRecord{}, false, err
	}
	err = tx.QueryRow(ctx, `update media_asset as asset set
		processing_status='DELETED',deleted_at=$2,pending_generation=null,
		pending_rotation_degrees=null,processing_error=null,version=version+1,updated_at=$2
		where media_id=$1 and owner_type=$3 and owner_id=$4 and warehouse_id=$5
		  and version=$6 and deleted_at is null and media_asset_is_available(media_id)
		returning version`, asset.ID, now, command.OwnerType, command.OwnerID,
		command.WarehouseID, command.ExpectedVersion).Scan(&asset.Version)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, ErrConflict
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	if _, err := tx.Exec(ctx, `update media_cabin_photo_library
		set cover_media_id=null,version=version+1,updated_at=$2
		where cover_media_id=$1`, asset.ID, now); err != nil {
		return AssetRecord{}, false, err
	}
	_, err = tx.Exec(ctx, `insert into media_command_idempotency (
		principal_type,subject_id,command_type,idempotency_key,request_sha256,media_id,created_at,expires_at)
	values ('USER',$1,'DELETE',$2,$3,$4,$5,$6)`, command.SubjectID, command.IdempotencyKey,
		command.RequestSHA256, asset.ID, now, now.Add(24*time.Hour))
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	asset.Status = media.StatusDeleted
	eventID := uuid.New()
	if err := appendEventWithID(ctx, tx, eventID, asset.ID, "media.media.deleted.v1",
		command.SubjectID, command.CorrelationID, now); err != nil {
		return AssetRecord{}, false, err
	}
	if err := insertFactOutbox(ctx, tx, eventID, asset, asset.Version,
		"media.media.deleted.v1", &command.SubjectID, command.CorrelationID, now,
		asset.Generation, asset.Rotation, nil); err != nil {
		return AssetRecord{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	return asset, false, nil
}

func assetOwnedBy(asset AssetRecord, ownerType, ownerID string, warehouseID uuid.UUID) bool {
	return asset.OwnerType == ownerType && asset.OwnerID == ownerID && asset.WarehouseID == warehouseID
}
