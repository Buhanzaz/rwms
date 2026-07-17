package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var (
	ErrNotFound            = errors.New("not found")
	ErrConflict            = errors.New("conflict")
	ErrOwnerProofMissing   = errors.New("owner proof is absent, stale, inactive, or mismatched")
	ErrIdempotencyMismatch = errors.New("idempotency key payload mismatch")
	ErrLeaseLost           = errors.New("lease or fencing token was lost")
)

const (
	OwnerTypeInventoryFinding = "INVENTORY_FINDING"
	ViewerContextInspection   = "INSPECTION"
	MediaTopic                = "rwms.media.media.v1"
	ProcessingTopic           = "rwms.media.processing.v1"
	ProcessingDLTTopic        = "rwms.media.processing.v1.media-service-processing-v1.dlt"
)

type Repository struct {
	pool *pgxpool.Pool
	now  func() time.Time
}

func NewRepository(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool, now: time.Now}
}

type AssetRecord struct {
	ID                uuid.UUID
	OwnerType         string
	OwnerID           string
	WarehouseID       uuid.UUID
	Kind              media.Kind
	FileName          string
	ContentType       string
	SourceObjectKey   string
	SourceVersionID   string
	SourceETag        string
	SourceChecksum    string
	Status            media.Status
	Version           int64
	Generation        int
	Rotation          media.Rotation
	SortOrder         int64
	SizeBytes         *int64
	CreatedAt         time.Time
	UploadSessionID   uuid.UUID
	UploadExpiresAt   time.Time
	ExpectedLength    int64
	ExpectedChecksum  string
	UploadCompletedAt *time.Time
}

type VariantRecord struct {
	Variant         media.Variant
	ObjectKey       string
	ObjectVersionID string
	ContentType     string
	SizeBytes       int64
	Width           *int
	Height          *int
	Checksum        string
}

type AssetWithVariants struct {
	Asset    AssetRecord
	Variants []VariantRecord
}

type CreateUploadCommand struct {
	MediaID         uuid.UUID
	UploadSessionID uuid.UUID
	SubjectID       uuid.UUID
	IdempotencyKey  uuid.UUID
	RequestSHA256   string
	OwnerType       string
	OwnerID         string
	WarehouseID     uuid.UUID
	Kind            media.Kind
	FileName        string
	ContentType     string
	ContentLength   int64
	ChecksumSHA256  string
	SortOrder       int64
	SourceObjectKey string
	UploadExpiresAt time.Time
	CorrelationID   uuid.UUID
}

func (repository *Repository) CreateUpload(ctx context.Context, command CreateUploadCommand) (AssetRecord, bool, error) {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return AssetRecord{}, false, err
	}
	defer tx.Rollback(ctx)
	if err := lockCommand(ctx, tx, command.SubjectID, "CREATE_UPLOAD", command.IdempotencyKey); err != nil {
		return AssetRecord{}, false, err
	}

	if replay, found, err := repository.findCreateReplay(ctx, tx, command); err != nil {
		return AssetRecord{}, false, err
	} else if found {
		if err := tx.Commit(ctx); err != nil {
			return AssetRecord{}, false, err
		}
		return replay, true, nil
	}
	if err := requireOwnerBinding(ctx, tx, command.OwnerType, command.OwnerID, command.WarehouseID, repository.now()); err != nil {
		return AssetRecord{}, false, err
	}
	if err := enforceOwnerMediaLimit(ctx, tx, command.OwnerType, command.OwnerID, command.WarehouseID, 100); err != nil {
		return AssetRecord{}, false, err
	}

	assetID := command.MediaID
	if assetID == uuid.Nil {
		assetID = uuid.New()
	}
	sessionID := command.UploadSessionID
	if sessionID == uuid.Nil {
		sessionID = uuid.New()
	}
	now := repository.now().UTC()
	_, err = tx.Exec(ctx, `
		insert into media_asset (
			media_id, owner_type, owner_id, warehouse_id, media_kind,
			original_file_name, original_content_type, source_object_key,
			processing_status, sort_order, version, next_generation, created_at, updated_at)
		values ($1,$2,$3,$4,$5,$6,$7,$8,'UPLOADING',$9,1,1,$10,$10)`,
		assetID, command.OwnerType, command.OwnerID, command.WarehouseID, command.Kind,
		command.FileName, command.ContentType, command.SourceObjectKey, command.SortOrder, now)
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `
		insert into media_upload_session (
			upload_session_id, media_id, subject_id, idempotency_key,
			expected_content_length, expected_content_type, expected_checksum_sha256,
			expires_at, created_at)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
		sessionID, assetID, command.SubjectID, command.IdempotencyKey,
		command.ContentLength, command.ContentType, command.ChecksumSHA256,
		command.UploadExpiresAt.UTC(), now)
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `
		insert into media_command_idempotency (
			subject_id, command_type, idempotency_key, request_sha256,
			media_id, created_at, expires_at)
		values ($1,'CREATE_UPLOAD',$2,$3,$4,$5,$6)`,
		command.SubjectID, command.IdempotencyKey, command.RequestSHA256, assetID, now, now.Add(24*time.Hour))
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	if err := appendInitialEvent(ctx, tx, assetID, "media.upload.session.created.v1", command.SubjectID, command.CorrelationID, now); err != nil {
		return AssetRecord{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	return AssetRecord{
		ID: assetID, OwnerType: command.OwnerType, OwnerID: command.OwnerID,
		WarehouseID: command.WarehouseID, Kind: command.Kind, FileName: command.FileName,
		ContentType: command.ContentType, SourceObjectKey: command.SourceObjectKey,
		Status: media.StatusUploading, Version: 1, SortOrder: command.SortOrder, CreatedAt: now,
		UploadSessionID: sessionID, UploadExpiresAt: command.UploadExpiresAt.UTC(),
		ExpectedLength: command.ContentLength, ExpectedChecksum: command.ChecksumSHA256,
	}, false, nil
}

func (repository *Repository) findCreateReplay(
	ctx context.Context,
	tx pgx.Tx,
	command CreateUploadCommand,
) (AssetRecord, bool, error) {
	var requestSHA string
	var assetID uuid.UUID
	err := tx.QueryRow(ctx, `
		select request_sha256, media_id
		from media_command_idempotency
		where subject_id=$1 and command_type='CREATE_UPLOAD' and idempotency_key=$2
		for update`, command.SubjectID, command.IdempotencyKey).Scan(&requestSHA, &assetID)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, nil
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	if requestSHA != command.RequestSHA256 {
		return AssetRecord{}, false, ErrIdempotencyMismatch
	}
	asset, err := scanAssetWithSession(tx.QueryRow(ctx, assetWithSessionSQL+`
		where a.media_id=$1 and media_asset_is_available(a.media_id)`, assetID))
	if err == nil {
		if err = requireOwnerBinding(ctx, tx, asset.OwnerType, asset.OwnerID, asset.WarehouseID, repository.now()); err == nil {
			if asset.UploadCompletedAt != nil || !repository.now().Before(asset.UploadExpiresAt) {
				err = ErrConflict
			}
		}
	}
	return asset, true, err
}

func (repository *Repository) UploadSessionForSubject(
	ctx context.Context,
	sessionID, subjectID uuid.UUID,
) (AssetRecord, error) {
	asset, err := scanAssetWithSession(repository.pool.QueryRow(ctx,
		assetWithSessionSQL+` where s.upload_session_id=$1 and s.subject_id=$2
			and media_asset_is_available(a.media_id)`, sessionID, subjectID))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, ErrNotFound
	}
	return asset, err
}

type FinalizeCommand struct {
	SessionID       uuid.UUID
	SubjectID       uuid.UUID
	IdempotencyKey  uuid.UUID
	RequestSHA256   string
	ObjectVersionID string
	ETag            string
	ChecksumSHA256  string
	ContentType     string
	SizeBytes       int64
	CorrelationID   uuid.UUID
}

func (repository *Repository) FinalizeUpload(ctx context.Context, command FinalizeCommand) (AssetRecord, bool, error) {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return AssetRecord{}, false, err
	}
	defer tx.Rollback(ctx)
	if err := lockCommand(ctx, tx, command.SubjectID, "FINALIZE_UPLOAD", command.IdempotencyKey); err != nil {
		return AssetRecord{}, false, err
	}

	var assetID uuid.UUID
	var existingSHA string
	err = tx.QueryRow(ctx, `
		select request_sha256, media_id from media_command_idempotency
		where subject_id=$1 and command_type='FINALIZE_UPLOAD' and idempotency_key=$2
		for update`, command.SubjectID, command.IdempotencyKey).Scan(&existingSHA, &assetID)
	if err == nil {
		if existingSHA != command.RequestSHA256 {
			return AssetRecord{}, false, ErrIdempotencyMismatch
		}
		asset, loadErr := repository.assetForUpdate(ctx, tx, assetID)
		if loadErr != nil {
			return AssetRecord{}, false, loadErr
		}
		var routeAssetID uuid.UUID
		routeErr := tx.QueryRow(ctx, `select media_id from media_upload_session
			where upload_session_id=$1 and subject_id=$2`, command.SessionID, command.SubjectID).Scan(&routeAssetID)
		if routeErr != nil {
			if errors.Is(routeErr, pgx.ErrNoRows) {
				return AssetRecord{}, false, ErrIdempotencyMismatch
			}
			return AssetRecord{}, false, routeErr
		}
		if routeAssetID != assetID {
			return AssetRecord{}, false, ErrIdempotencyMismatch
		}
		if proofErr := requireOwnerBinding(ctx, tx, asset.OwnerType, asset.OwnerID, asset.WarehouseID, repository.now()); proofErr != nil {
			return AssetRecord{}, false, proofErr
		}
		if err := tx.Commit(ctx); err != nil {
			return AssetRecord{}, false, err
		}
		return asset, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, err
	}

	var expiresAt time.Time
	var completedAt *time.Time
	err = tx.QueryRow(ctx, `
		select s.media_id, s.expires_at, s.completed_at from media_upload_session s
		join media_asset a on a.media_id=s.media_id
		where s.upload_session_id=$1 and s.subject_id=$2
		  and media_asset_is_available(a.media_id) for update of s`,
		command.SessionID, command.SubjectID).Scan(&assetID, &expiresAt, &completedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, ErrNotFound
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	if completedAt != nil || !repository.now().Before(expiresAt) {
		return AssetRecord{}, false, ErrConflict
	}
	asset, err := repository.assetForUpdate(ctx, tx, assetID)
	if err != nil {
		return AssetRecord{}, false, err
	}
	if err := requireOwnerBinding(ctx, tx, asset.OwnerType, asset.OwnerID, asset.WarehouseID, repository.now()); err != nil {
		return AssetRecord{}, false, err
	}
	if asset.Status != media.StatusUploading {
		return AssetRecord{}, false, ErrConflict
	}
	jobID := uuid.New()
	generation := max(asset.Generation+1, 1)
	var nextGeneration int
	err = tx.QueryRow(ctx, `
		update media_asset as a
		set processing_status='PROCESSING', pending_generation=next_generation,
			pending_rotation_degrees=0, next_generation=next_generation+1,
			source_version_id=$2, source_etag=$3, source_checksum_sha256=$4,
			finalized_content_type=$5, finalized_size_bytes=$6, size_bytes=$6,
			version=version+1, updated_at=$7
		where a.media_id=$1 and version=$8 and processing_status='UPLOADING'
		  and media_asset_is_available(a.media_id)
		returning pending_generation, next_generation, version`,
		assetID, command.ObjectVersionID, command.ETag, command.ChecksumSHA256,
		command.ContentType, command.SizeBytes, repository.now().UTC(), asset.Version).
		Scan(&generation, &nextGeneration, &asset.Version)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, ErrConflict
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	_, err = tx.Exec(ctx, `
		insert into media_processing_job (
			processing_job_id, media_id, generation, processing_kind,
			requested_rotation_degrees, job_status, source_version_id,
			source_checksum_sha256, next_attempt_at)
		values ($1,$2,$3,'INITIAL',0,'PENDING',$4,$5,$6)`,
		jobID, assetID, generation, command.ObjectVersionID, command.ChecksumSHA256, repository.now().UTC())
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `update media_upload_session set completed_at=$2 where upload_session_id=$1`, command.SessionID, repository.now().UTC())
	if err != nil {
		return AssetRecord{}, false, err
	}
	_, err = tx.Exec(ctx, `
		insert into media_command_idempotency (
			subject_id, command_type, idempotency_key, request_sha256, media_id, created_at, expires_at)
		values ($1,'FINALIZE_UPLOAD',$2,$3,$4,$5,$6)`,
		command.SubjectID, command.IdempotencyKey, command.RequestSHA256, assetID,
		repository.now().UTC(), repository.now().UTC().Add(24*time.Hour))
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	now := repository.now().UTC()
	asset.Status = media.StatusProcessing
	uploadedID := uuid.New()
	if err := appendEventWithID(ctx, tx, uploadedID, assetID, "media.media.uploaded.v1", command.SubjectID, command.CorrelationID, now); err != nil {
		return AssetRecord{}, false, err
	}
	err = insertFactOutbox(ctx, tx, uploadedID, asset, asset.Version, "media.media.uploaded.v1", &command.SubjectID, command.CorrelationID, now, generation, media.Rotation0, nil)
	if err != nil {
		return AssetRecord{}, false, err
	}
	asset.SourceVersionID = command.ObjectVersionID
	asset.SourceETag = command.ETag
	asset.SourceChecksum = command.ChecksumSHA256
	if err := insertProcessingRequestOutbox(ctx, tx, jobID, asset, generation, media.ProcessingInitial, media.Rotation0, command.CorrelationID, now, &uploadedID); err != nil {
		return AssetRecord{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	asset.Status = media.StatusProcessing
	asset.Generation = 0
	asset.SizeBytes = &command.SizeBytes
	return asset, false, nil
}

type RotateCommand struct {
	MediaID         uuid.UUID
	SubjectID       uuid.UUID
	IdempotencyKey  uuid.UUID
	RequestSHA256   string
	ExpectedVersion int64
	Rotation        media.Rotation
	CorrelationID   uuid.UUID
}

func (repository *Repository) Rotate(ctx context.Context, command RotateCommand) (AssetRecord, bool, error) {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return AssetRecord{}, false, err
	}
	defer tx.Rollback(ctx)
	if err := lockCommand(ctx, tx, command.SubjectID, "ROTATE", command.IdempotencyKey); err != nil {
		return AssetRecord{}, false, err
	}
	var existingSHA string
	var replayID uuid.UUID
	err = tx.QueryRow(ctx, `select request_sha256, media_id from media_command_idempotency
		where subject_id=$1 and command_type='ROTATE' and idempotency_key=$2 for update`,
		command.SubjectID, command.IdempotencyKey).Scan(&existingSHA, &replayID)
	if err == nil {
		if existingSHA != command.RequestSHA256 || replayID != command.MediaID {
			return AssetRecord{}, false, ErrIdempotencyMismatch
		}
		asset, loadErr := repository.assetForUpdate(ctx, tx, replayID)
		if loadErr != nil {
			return AssetRecord{}, false, loadErr
		}
		if proofErr := requireOwnerBinding(ctx, tx, asset.OwnerType, asset.OwnerID, asset.WarehouseID, repository.now()); proofErr != nil {
			return AssetRecord{}, false, proofErr
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
	if err := requireOwnerBinding(ctx, tx, asset.OwnerType, asset.OwnerID, asset.WarehouseID, repository.now()); err != nil {
		return AssetRecord{}, false, err
	}
	if asset.Status != media.StatusReady || asset.Version != command.ExpectedVersion || asset.Generation <= 0 || asset.Rotation == command.Rotation {
		return AssetRecord{}, false, ErrConflict
	}
	jobID := uuid.New()
	var generation int
	err = tx.QueryRow(ctx, `
		update media_asset as a set processing_status='PROCESSING',
			pending_generation=next_generation, pending_rotation_degrees=$2,
			next_generation=next_generation+1, version=version+1, updated_at=$3
		where a.media_id=$1 and version=$4 and processing_status='READY'
		  and media_asset_is_available(a.media_id)
		returning pending_generation, version`,
		asset.ID, command.Rotation, repository.now().UTC(), command.ExpectedVersion).
		Scan(&generation, &asset.Version)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, ErrConflict
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	_, err = tx.Exec(ctx, `insert into media_processing_job (
		processing_job_id, media_id, generation, processing_kind,
		requested_rotation_degrees, job_status, source_version_id,
		source_checksum_sha256, next_attempt_at)
		values ($1,$2,$3,'ROTATION',$4,'PENDING',$5,$6,$7)`,
		jobID, asset.ID, generation, command.Rotation, asset.SourceVersionID,
		asset.SourceChecksum, repository.now().UTC())
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `insert into media_command_idempotency (
		subject_id, command_type, idempotency_key, request_sha256, media_id, created_at, expires_at)
		values ($1,'ROTATE',$2,$3,$4,$5,$6)`, command.SubjectID, command.IdempotencyKey,
		command.RequestSHA256, asset.ID, repository.now().UTC(), repository.now().UTC().Add(24*time.Hour))
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	if err := appendEvent(ctx, tx, asset.ID, "media.rotation.requested.v1", command.SubjectID, command.CorrelationID, repository.now().UTC()); err != nil {
		return AssetRecord{}, false, err
	}
	var dependency uuid.UUID
	if err := tx.QueryRow(ctx, `select event_id from media_transport_outbox
		where aggregate_type='MEDIA' and aggregate_id=$1 and aggregate_version=$2
		order by recorded_at desc,event_id desc limit 1`, asset.ID, asset.Version-1).Scan(&dependency); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return AssetRecord{}, false, ErrConflict
		}
		return AssetRecord{}, false, err
	}
	if err := insertProcessingRequestOutbox(ctx, tx, jobID, asset, generation, media.ProcessingRotation, command.Rotation, command.CorrelationID, repository.now().UTC(), &dependency); err != nil {
		return AssetRecord{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	asset.Status = media.StatusProcessing
	return asset, false, nil
}

func (repository *Repository) ListOwner(
	ctx context.Context,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	limit int,
	after *uuid.UUID,
) ([]AssetRecord, error) {
	var assets []AssetRecord
	err := repository.ReadOwnerAssets(ctx, ownerType, ownerID, warehouseID, limit, after,
		func(records []AssetWithVariants) error {
			assets = make([]AssetRecord, len(records))
			for index := range records {
				assets[index] = records[index].Asset
			}
			return nil
		})
	return assets, err
}

// ReadOwnerAssets keeps the exact owner-proof row share-locked while the
// callback prepares every public metadata and signed-variant response. A
// concurrent revocation either waits for this read to finish or wins the row
// lock first, in which case this statement returns no authorization inputs.
func (repository *Repository) ReadOwnerAssets(
	ctx context.Context,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	limit int,
	after *uuid.UUID,
	consume func([]AssetWithVariants) error,
) error {
	if limit < 1 || limit > 100 {
		return ErrConflict
	}
	if consume == nil {
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var cursorKind int
	var cursorSort int64
	var cursorCreated time.Time
	var cursorID uuid.UUID
	if after != nil {
		err := tx.QueryRow(ctx, `select case when a.media_kind='IMAGE' then 0 else 1 end,
			a.sort_order,a.created_at,a.media_id from media_asset a
			where a.media_id=$1 and a.owner_type=$2 and a.owner_id=$3 and a.warehouse_id=$4
			  and a.deleted_at is null and media_asset_is_available(a.media_id)`,
			*after, ownerType, ownerID, warehouseID).Scan(&cursorKind, &cursorSort, &cursorCreated, &cursorID)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrConflict
		}
		if err != nil {
			return err
		}
	}
	rows, err := tx.Query(ctx, `/* media_public_owner_read */
		with authorized_assets as materialized (
		select a.media_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
			a.original_file_name,a.original_content_type,a.source_object_key,
			coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
			a.processing_status,a.version,a.current_generation,a.rotation_degrees,
			a.sort_order,a.size_bytes,a.created_at
		from media_asset a
		join media_owner_binding binding
		  on binding.owner_type=a.owner_type and binding.owner_id=a.owner_id
		 and binding.warehouse_id=a.warehouse_id and binding.active
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where a.owner_type=$1 and a.owner_id=$2 and a.warehouse_id=$3 and a.deleted_at is null
		and media_asset_is_available(a.media_id)
		and not exists (select 1 from media_quarantined_aggregate quarantine
			where quarantine.consumer_name=binding.proof_consumer_name
			  and quarantine.aggregate_type=binding.proof_aggregate_type
			  and quarantine.aggregate_id=binding.proof_aggregate_id
			  and quarantine.reconciled_at is null)
		and ($4::uuid is null or (
			case when a.media_kind='IMAGE' then 0 else 1 end,
			a.sort_order,a.created_at,a.media_id
		) > ($5::integer,$6::bigint,$7::timestamptz,$4::uuid))
		order by case when a.media_kind='IMAGE' then 0 else 1 end, a.sort_order, a.created_at, a.media_id
		limit $8 for share of binding
		)
		select a.*,(variant.media_id is not null),coalesce(variant.variant,''),
			coalesce(variant.object_key,''),coalesce(variant.object_version_id,''),
			coalesce(variant.content_type,''),coalesce(variant.size_bytes,0),
			variant.width,variant.height,coalesce(variant.checksum_sha256,'')
		from authorized_assets a
		left join media_variant variant on variant.media_id=a.media_id
		 and variant.generation=a.current_generation and variant.variant<>'ORIGINAL'
		 and a.processing_status='READY'
		order by case when a.media_kind='IMAGE' then 0 else 1 end,a.sort_order,a.created_at,
			a.media_id,variant.variant`, ownerType, ownerID, warehouseID, after,
		cursorKind, cursorSort, cursorCreated, limit)
	if err != nil {
		return err
	}
	var records []AssetWithVariants
	byID := make(map[uuid.UUID]int)
	for rows.Next() {
		var asset AssetRecord
		var hasVariant bool
		var variant VariantRecord
		var variantName string
		if err := rows.Scan(&asset.ID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID,
			&asset.Kind, &asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
			&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum, &asset.Status,
			&asset.Version, &asset.Generation, &asset.Rotation, &asset.SortOrder,
			&asset.SizeBytes, &asset.CreatedAt, &hasVariant, &variantName, &variant.ObjectKey,
			&variant.ObjectVersionID, &variant.ContentType, &variant.SizeBytes, &variant.Width,
			&variant.Height, &variant.Checksum); err != nil {
			rows.Close()
			return err
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
		rows.Close()
		return err
	}
	rows.Close()
	if len(records) == 0 {
		if err := requireOwnerBinding(ctx, tx, ownerType, ownerID, warehouseID,
			repository.now()); err != nil {
			return err
		}
	}
	if err := consume(records); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (repository *Repository) GetAsset(ctx context.Context, mediaID uuid.UUID) (AssetRecord, error) {
	asset, err := scanAsset(repository.pool.QueryRow(ctx, assetSQL+`
		join media_owner_binding binding on binding.owner_type=a.owner_type
		 and binding.owner_id=a.owner_id and binding.warehouse_id=a.warehouse_id and binding.active
		join media_consumer_aggregate_checkpoint checkpoint
		 on checkpoint.consumer_name=binding.proof_consumer_name
		and checkpoint.aggregate_type=binding.proof_aggregate_type
		and checkpoint.aggregate_id=binding.proof_aggregate_id
		and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where a.media_id=$1 and a.deleted_at is null and media_asset_is_available(a.media_id)
		and not exists (select 1 from media_quarantined_aggregate quarantine
		 where quarantine.consumer_name=binding.proof_consumer_name
		 and quarantine.aggregate_type=binding.proof_aggregate_type
		 and quarantine.aggregate_id=binding.proof_aggregate_id and quarantine.reconciled_at is null)`, mediaID))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, ErrNotFound
	}
	if err != nil {
		return AssetRecord{}, err
	}
	return asset, nil
}

func (repository *Repository) GetAssetScoped(ctx context.Context, mediaID uuid.UUID, ownerType, ownerID string, warehouseID uuid.UUID) (AssetRecord, error) {
	asset, err := scanAsset(repository.pool.QueryRow(ctx, assetSQL+`
		join media_owner_binding binding on binding.owner_type=a.owner_type
		 and binding.owner_id=a.owner_id and binding.warehouse_id=a.warehouse_id and binding.active
		join media_consumer_aggregate_checkpoint checkpoint
		 on checkpoint.consumer_name=binding.proof_consumer_name
		and checkpoint.aggregate_type=binding.proof_aggregate_type
		and checkpoint.aggregate_id=binding.proof_aggregate_id
		and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where a.media_id=$1 and a.owner_type=$2 and a.owner_id=$3
		  and a.warehouse_id=$4 and a.deleted_at is null
		  and media_asset_is_available(a.media_id)
		and not exists (select 1 from media_quarantined_aggregate quarantine
		 where quarantine.consumer_name=binding.proof_consumer_name
		 and quarantine.aggregate_type=binding.proof_aggregate_type
		 and quarantine.aggregate_id=binding.proof_aggregate_id and quarantine.reconciled_at is null)`,
		mediaID, ownerType, ownerID, warehouseID))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, ErrNotFound
	}
	if err != nil {
		return AssetRecord{}, err
	}
	return asset, nil
}

// ReadOriginal keeps the owner binding locked through signed-URL preparation.
// Asset metadata and the exact current-generation original are fetched by one
// authorization-bearing SQL statement.
func (repository *Repository) ReadOriginal(
	ctx context.Context,
	mediaID uuid.UUID,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if consume == nil {
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var asset AssetRecord
	var hasVariant bool
	var variant VariantRecord
	var variantName string
	err = tx.QueryRow(ctx, `/* media_public_original_read */
		select a.media_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
			a.original_file_name,a.original_content_type,a.source_object_key,
			coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
			a.processing_status,a.version,a.current_generation,a.rotation_degrees,
			a.sort_order,a.size_bytes,a.created_at,
			(variant.media_id is not null),coalesce(variant.variant,''),
			coalesce(variant.object_key,''),coalesce(variant.object_version_id,''),
			coalesce(variant.content_type,''),coalesce(variant.size_bytes,0),
			variant.width,variant.height,coalesce(variant.checksum_sha256,'')
		from media_asset a
		join media_owner_binding binding on binding.owner_type=a.owner_type
		 and binding.owner_id=a.owner_id and binding.warehouse_id=a.warehouse_id and binding.active
		join media_consumer_aggregate_checkpoint checkpoint
		 on checkpoint.consumer_name=binding.proof_consumer_name
		and checkpoint.aggregate_type=binding.proof_aggregate_type
		and checkpoint.aggregate_id=binding.proof_aggregate_id
		and checkpoint.aggregate_version>=binding.proof_aggregate_version
		left join media_variant variant on variant.media_id=a.media_id
		 and variant.generation=a.current_generation and variant.variant='ORIGINAL'
		where a.media_id=$1 and a.owner_type=$2 and a.owner_id=$3 and a.warehouse_id=$4
		 and a.deleted_at is null and media_asset_is_available(a.media_id)
		 and not exists (select 1 from media_quarantined_aggregate quarantine
			where quarantine.consumer_name=binding.proof_consumer_name
			  and quarantine.aggregate_type=binding.proof_aggregate_type
			  and quarantine.aggregate_id=binding.proof_aggregate_id
			  and quarantine.reconciled_at is null)
		for share of binding`, mediaID, ownerType, ownerID, warehouseID).Scan(
		&asset.ID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
		&asset.FileName, &asset.ContentType, &asset.SourceObjectKey, &asset.SourceVersionID,
		&asset.SourceETag, &asset.SourceChecksum, &asset.Status, &asset.Version,
		&asset.Generation, &asset.Rotation, &asset.SortOrder, &asset.SizeBytes,
		&asset.CreatedAt, &hasVariant, &variantName, &variant.ObjectKey,
		&variant.ObjectVersionID, &variant.ContentType, &variant.SizeBytes, &variant.Width,
		&variant.Height, &variant.Checksum)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	var original *VariantRecord
	if hasVariant {
		variant.Variant = media.Variant(variantName)
		original = &variant
	}
	if err := consume(asset, original); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (repository *Repository) Variants(ctx context.Context, mediaID uuid.UUID, generation int, includeOriginal bool) ([]VariantRecord, error) {
	rows, err := repository.pool.Query(ctx, `select (variant.media_id is not null),
		coalesce(variant.variant,''),coalesce(variant.object_key,''),
		coalesce(variant.object_version_id,''),coalesce(variant.content_type,''),
		coalesce(variant.size_bytes,0),variant.width,variant.height,
		coalesce(variant.checksum_sha256,'')
		from media_asset a
		join media_owner_binding binding on binding.owner_type=a.owner_type
		 and binding.owner_id=a.owner_id and binding.warehouse_id=a.warehouse_id and binding.active
		join media_consumer_aggregate_checkpoint checkpoint
		 on checkpoint.consumer_name=binding.proof_consumer_name
		and checkpoint.aggregate_type=binding.proof_aggregate_type
		and checkpoint.aggregate_id=binding.proof_aggregate_id
		and checkpoint.aggregate_version>=binding.proof_aggregate_version
		left join media_variant variant on variant.media_id=a.media_id
		 and variant.generation=$2 and ($3 or variant.variant<>'ORIGINAL')
		where a.media_id=$1 and a.deleted_at is null and media_asset_is_available(a.media_id)
		and not exists (select 1 from media_quarantined_aggregate quarantine
		 where quarantine.consumer_name=binding.proof_consumer_name
		 and quarantine.aggregate_type=binding.proof_aggregate_type
		 and quarantine.aggregate_id=binding.proof_aggregate_id and quarantine.reconciled_at is null)
		order by variant.variant`,
		mediaID, generation, includeOriginal)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var variants []VariantRecord
	foundAsset := false
	for rows.Next() {
		foundAsset = true
		var hasVariant bool
		var variant VariantRecord
		var variantName string
		if err := rows.Scan(&hasVariant, &variantName, &variant.ObjectKey, &variant.ObjectVersionID,
			&variant.ContentType, &variant.SizeBytes, &variant.Width, &variant.Height, &variant.Checksum); err != nil {
			return nil, err
		}
		if hasVariant {
			variant.Variant = media.Variant(variantName)
			variants = append(variants, variant)
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if !foundAsset {
		return nil, ErrNotFound
	}
	return variants, nil
}

func (repository *Repository) assetForUpdate(ctx context.Context, tx pgx.Tx, mediaID uuid.UUID) (AssetRecord, error) {
	asset, err := scanAsset(tx.QueryRow(ctx, assetSQL+`
		where a.media_id=$1 and media_asset_is_available(a.media_id) for update`, mediaID))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, ErrNotFound
	}
	return asset, err
}

const assetSQL = `select a.media_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
	a.original_file_name,a.original_content_type,a.source_object_key,
	coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
	a.processing_status,a.version,a.current_generation,a.rotation_degrees,
	a.sort_order,a.size_bytes,a.created_at from media_asset a`

const assetWithSessionSQL = `select a.media_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
	a.original_file_name,a.original_content_type,a.source_object_key,
	coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
	a.processing_status,a.version,a.current_generation,a.rotation_degrees,
	a.sort_order,a.size_bytes,a.created_at,s.upload_session_id,s.expires_at,
		s.expected_content_length,coalesce(s.expected_checksum_sha256,''),s.completed_at
	from media_asset a join media_upload_session s on s.media_id=a.media_id`

type rowScanner interface {
	Scan(...any) error
}

func scanAsset(row rowScanner) (AssetRecord, error) {
	var asset AssetRecord
	err := row.Scan(&asset.ID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
		&asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
		&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum,
		&asset.Status, &asset.Version, &asset.Generation, &asset.Rotation,
		&asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt)
	return asset, err
}

func validSHA256(value string) bool {
	if len(value) != sha256.Size*2 {
		return false
	}
	_, err := hex.DecodeString(value)
	if err != nil {
		return false
	}
	for _, character := range value {
		if character >= 'A' && character <= 'F' {
			return false
		}
	}
	return true
}

func scanAssetWithSession(row rowScanner) (AssetRecord, error) {
	var asset AssetRecord
	err := row.Scan(&asset.ID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
		&asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
		&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum,
		&asset.Status, &asset.Version, &asset.Generation, &asset.Rotation,
		&asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt, &asset.UploadSessionID,
		&asset.UploadExpiresAt, &asset.ExpectedLength, &asset.ExpectedChecksum,
		&asset.UploadCompletedAt)
	return asset, err
}

type queryer interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}

func requireOwnerBinding(ctx context.Context, database queryer, ownerType, ownerID string, warehouseID uuid.UUID, now time.Time) error {
	if ownerType != OwnerTypeInventoryFinding || ownerID == "" {
		return ErrOwnerProofMissing
	}
	var exists bool
	err := database.QueryRow(ctx, `select exists(
		select 1 from media_owner_binding binding
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where binding.owner_type=$1 and binding.owner_id=$2 and binding.warehouse_id=$3 and binding.active
		  and not exists (
			select 1 from media_quarantined_aggregate quarantine
			where quarantine.consumer_name=binding.proof_consumer_name
			  and quarantine.aggregate_type=binding.proof_aggregate_type
			  and quarantine.aggregate_id=binding.proof_aggregate_id
			  and quarantine.reconciled_at is null))`,
		ownerType, ownerID, warehouseID).Scan(&exists)
	if err != nil {
		return err
	}
	if !exists {
		return ErrOwnerProofMissing
	}
	return nil
}

func enforceOwnerMediaLimit(ctx context.Context, tx pgx.Tx, ownerType, ownerID string, warehouseID uuid.UUID, maximum int) error {
	var revision int64
	if err := tx.QueryRow(ctx, `select owner_revision from media_owner_binding
		where owner_type=$1 and owner_id=$2 and warehouse_id=$3 and active for update`,
		ownerType, ownerID, warehouseID).Scan(&revision); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrOwnerProofMissing
		}
		return err
	}
	var count int
	if err := tx.QueryRow(ctx, `select count(*) from media_asset a
		where a.owner_type=$1 and a.owner_id=$2 and a.warehouse_id=$3 and a.deleted_at is null
		  and media_asset_is_available(a.media_id)`,
		ownerType, ownerID, warehouseID).Scan(&count); err != nil {
		return err
	}
	if count >= maximum {
		return ErrConflict
	}
	return nil
}

func lockCommand(ctx context.Context, tx pgx.Tx, subjectID uuid.UUID, commandType string, idempotencyKey uuid.UUID) error {
	_, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		subjectID.String()+":"+commandType+":"+idempotencyKey.String())
	return err
}

func appendInitialEvent(ctx context.Context, tx pgx.Tx, aggregateID uuid.UUID, eventType string, subjectID, correlationID uuid.UUID, recordedAt time.Time) error {
	_, err := tx.Exec(ctx, `insert into media_event_stream_head (aggregate_type,aggregate_id,stream_version,updated_at)
		values ('MEDIA',$1,1,$2)`, aggregateID, recordedAt)
	if err != nil {
		return translateConstraint(err)
	}
	return insertDomainEvent(ctx, tx, uuid.New(), aggregateID, 1, eventType, &subjectID, correlationID, recordedAt)
}

func appendEvent(ctx context.Context, tx pgx.Tx, aggregateID uuid.UUID, eventType string, subjectID, correlationID uuid.UUID, recordedAt time.Time) error {
	return appendEventWithID(ctx, tx, uuid.New(), aggregateID, eventType, subjectID, correlationID, recordedAt)
}

func appendEventWithID(ctx context.Context, tx pgx.Tx, eventID, aggregateID uuid.UUID, eventType string, subjectID, correlationID uuid.UUID, recordedAt time.Time) error {
	version, err := advanceDomainStream(ctx, tx, aggregateID, recordedAt)
	if err != nil {
		return err
	}
	return insertDomainEvent(ctx, tx, eventID, aggregateID, version, eventType, &subjectID, correlationID, recordedAt)
}

func advanceDomainStream(ctx context.Context, tx pgx.Tx, aggregateID uuid.UUID, recordedAt time.Time) (int64, error) {
	var version int64
	err := tx.QueryRow(ctx, `update media_event_stream_head
		set stream_version=stream_version+1,updated_at=$2
		where aggregate_type='MEDIA' and aggregate_id=$1
		returning stream_version`, aggregateID, recordedAt).Scan(&version)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, ErrConflict
	}
	return version, err
}

func fullAggregateState(ctx context.Context, database queryer, aggregateID uuid.UUID) ([]byte, string, error) {
	var state string
	if err := database.QueryRow(ctx, `select media_full_state($1)::text`, aggregateID).Scan(&state); err != nil {
		return nil, "", err
	}
	body := []byte(state)
	sum := sha256.Sum256(body)
	return body, hex.EncodeToString(sum[:]), nil
}

func insertDomainEvent(ctx context.Context, tx pgx.Tx, eventID, aggregateID uuid.UUID, version int64, eventType string, subjectID *uuid.UUID, correlationID uuid.UUID, recordedAt time.Time) error {
	body, checksum, err := fullAggregateState(ctx, tx, aggregateID)
	if err != nil {
		return err
	}
	var actor any
	if subjectID != nil {
		actorBody, _, actorErr := canonicalJSON(map[string]any{"subjectId": *subjectID, "principalType": "USER", "profileRevision": nil})
		if actorErr != nil {
			return actorErr
		}
		actor = string(actorBody)
	}
	_, err = tx.Exec(ctx, `insert into media_domain_event (
		event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
		payload,payload_wire,payload_sha256,actor_ref,correlation_id,recorded_at)
		values ($1,'MEDIA',$2,$3,$4,1,$5::jsonb,$6,$7,$8::jsonb,$9,$10)`,
		eventID, aggregateID, version, eventType, string(body), body, checksum, actor, correlationID, recordedAt)
	if err != nil {
		return translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `insert into media_event_snapshot (
		aggregate_type,aggregate_id,aggregate_version,snapshot,snapshot_wire,snapshot_sha256,created_at)
	values ('MEDIA',$1,$2,$3::jsonb,$4,$5,$6)
	on conflict (aggregate_type,aggregate_id) do update set
		aggregate_version=excluded.aggregate_version,snapshot=excluded.snapshot,
		snapshot_wire=excluded.snapshot_wire,snapshot_sha256=excluded.snapshot_sha256,
		created_at=excluded.created_at`, aggregateID, version, string(body), body, checksum, recordedAt)
	return translateConstraint(err)
}

func canonicalJSON(value any) ([]byte, string, error) {
	body, err := json.Marshal(value)
	if err != nil {
		return nil, "", err
	}
	sum := sha256.Sum256(body)
	return body, hex.EncodeToString(sum[:]), nil
}

func factPayload(asset AssetRecord, status media.Status, generation int, rotation media.Rotation) map[string]any {
	return map[string]any{
		"mediaId": asset.ID, "ownerType": asset.OwnerType, "ownerId": asset.OwnerID,
		"warehouseId": asset.WarehouseID, "kind": asset.Kind, "status": status,
		"generation": generation, "rotationDegrees": rotation,
	}
}

func envelope(eventID uuid.UUID, eventType, aggregateType string, aggregateID uuid.UUID, aggregateVersion int64, subjectID *uuid.UUID, correlationID uuid.UUID, recordedAt time.Time, payload any) (json.RawMessage, string, error) {
	var actor any
	if subjectID != nil {
		actor = map[string]any{"subjectId": *subjectID, "principalType": "USER", "profileRevision": nil}
	}
	value := map[string]any{
		"envelopeVersion": 2, "eventId": eventID, "eventType": eventType, "eventVersion": 1,
		"occurredAt": nil, "recordedAt": recordedAt, "producer": "media-service",
		"aggregateType": aggregateType, "aggregateId": aggregateID, "aggregateVersion": aggregateVersion,
		"correlation": map[string]any{"correlationId": correlationID, "causationId": nil},
		"actorRef":    actor, "payload": payload,
	}
	body, checksum, err := canonicalJSON(value)
	return body, checksum, err
}

func insertFactOutbox(ctx context.Context, tx pgx.Tx, eventID uuid.UUID, asset AssetRecord, version int64, eventType string, subjectID *uuid.UUID, correlationID uuid.UUID, recordedAt time.Time, generation int, rotation media.Rotation, dependency *uuid.UUID) error {
	body, checksum, err := envelope(eventID, eventType, "MEDIA", asset.ID, version, subjectID, correlationID, recordedAt, factPayload(asset, asset.Status, generation, rotation))
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `insert into media_transport_outbox (
		event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,record_key,
		publication_ordinal,depends_on_event_id,envelope_body,wire_body,envelope_sha256,recorded_at)
		values ($1,'MEDIA',$2,$3,$4,$5,$2,$3,$6,$7::jsonb,$8,$9,$10)`,
		eventID, asset.ID, version, eventType, MediaTopic, dependency, string(body), body, checksum, recordedAt)
	return translateConstraint(err)
}

func insertProcessingRequestOutbox(ctx context.Context, tx pgx.Tx, jobID uuid.UUID, asset AssetRecord, generation int, kind media.ProcessingKind, rotation media.Rotation, correlationID uuid.UUID, recordedAt time.Time, dependency *uuid.UUID) error {
	eventID := uuid.New()
	payload := map[string]any{
		"processingJobId": jobID, "mediaId": asset.ID, "warehouseId": asset.WarehouseID,
		"kind": asset.Kind, "processingKind": kind, "generation": generation,
		"rotationDegrees": rotation, "sourceVersionId": asset.SourceVersionID,
	}
	body, checksum, err := envelope(eventID, "media.processing.request.v1", "PROCESSING_JOB", jobID, 1, nil, correlationID, recordedAt, payload)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `insert into media_transport_outbox (
		event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,record_key,
		publication_ordinal,depends_on_event_id,envelope_body,wire_body,envelope_sha256,recorded_at)
		values ($1,'PROCESSING_JOB',$2,1,'media.processing.request.v1',$3,$2,1,$4,$5::jsonb,$6,$7,$8)`,
		eventID, jobID, ProcessingTopic, dependency, string(body), body, checksum, recordedAt)
	return translateConstraint(err)
}

func translateConstraint(err error) error {
	if err == nil {
		return nil
	}
	var postgres interface{ SQLState() string }
	if errors.As(err, &postgres) {
		switch postgres.SQLState() {
		case "23505", "23514", "40001":
			return fmt.Errorf("%w: %v", ErrConflict, err)
		}
	}
	return err
}
