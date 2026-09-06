// Package persistence owns media-service's PostgreSQL state, transactional
// event/outbox records, owner projections, and replay verification.
package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var (
	// ErrNotFound indicates that the requested scoped media record is absent.
	ErrNotFound = errors.New("not found")
	// ErrConflict indicates an invalid media transition or optimistic conflict.
	ErrConflict = errors.New("conflict")
	// ErrOwnerProofMissing indicates absent, stale, inactive, or mismatched
	// authoritative ownership evidence.
	ErrOwnerProofMissing = errors.New("owner proof is absent, stale, inactive, or mismatched")
	// ErrIdempotencyMismatch rejects an idempotency key reused with new input.
	ErrIdempotencyMismatch = errors.New("idempotency key payload mismatch")
	// ErrLeaseLost indicates that a fenced worker or relay may no longer mutate
	// the claimed record.
	ErrLeaseLost = errors.New("lease or fencing token was lost")
)

const (
	// OwnerTypeInventoryFinding identifies inventory-finding owned media.
	OwnerTypeInventoryFinding = "INVENTORY_FINDING"
	// OwnerTypeCabin identifies CABIN owned media.
	OwnerTypeCabin = "CABIN"
	// ViewerContextInspection is the public read context for an inventory finding.
	ViewerContextInspection = "INSPECTION"
	// ViewerContextWarehouse is the public read context for a CABIN.
	ViewerContextWarehouse = "WAREHOUSE"
	// MediaTopic carries sanitized media facts to Kafka.
	MediaTopic = "rwms.media.media.v1"
	// ProcessingTopic carries media processing requests to Kafka.
	ProcessingTopic = "rwms.media.processing.v1"
	// ProcessingDLTTopic carries terminal sanitized processing failures.
	ProcessingDLTTopic = "rwms.media.processing.v1.media-service-processing-v1.dlt"
)

// Repository is the media-service PostgreSQL boundary. It implements all
// aggregate transitions, owner checks, inbox/outbox, and read projections.
type Repository struct {
	pool *pgxpool.Pool
	now  func() time.Time
}

// NewRepository binds media persistence operations to the provided PostgreSQL
// connection pool.
func NewRepository(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool, now: time.Now}
}

func normalizeActor(subjectID uuid.UUID, principalType string, actor ActorReference) (ActorReference, error) {
	if subjectID == uuid.Nil {
		return ActorReference{}, ErrConflict
	}
	if principalType == "" {
		principalType = PrincipalTypeUser
	}
	if principalType != PrincipalTypeUser && principalType != PrincipalTypeWorker {
		return ActorReference{}, ErrConflict
	}
	if actor.PrincipalType == "" {
		actor.PrincipalType = principalType
	}
	if actor.SubjectID == uuid.Nil {
		actor.SubjectID = subjectID
	}
	if actor.PrincipalType != principalType || actor.SubjectID == uuid.Nil {
		return ActorReference{}, ErrConflict
	}
	return actor, nil
}

func actorForAsset(asset AssetRecord) *ActorReference {
	if asset.CreatedBy == nil || asset.CreatedBy.SubjectID == uuid.Nil ||
		(asset.CreatedBy.PrincipalType != PrincipalTypeUser && asset.CreatedBy.PrincipalType != PrincipalTypeWorker) {
		return nil
	}
	copyOfActor := *asset.CreatedBy
	return &copyOfActor
}

// AcquireUploadSessionContentLock serializes byte ingress for one upload
// session across every media-service instance. The session-level advisory lock
// is held on a dedicated pooled connection while the bounded object stream is
// written and finalized, preventing concurrent requests from creating two
// accepted immutable versions for the same session.
func (repository *Repository) AcquireUploadSessionContentLock(
	ctx context.Context,
	sessionID uuid.UUID,
) (func() error, error) {
	connection, err := repository.pool.Acquire(ctx)
	if err != nil {
		return nil, err
	}
	lockName := "media-upload-session-content:" + sessionID.String()
	if _, err := connection.Exec(ctx, `select pg_advisory_lock(hashtextextended($1,0))`, lockName); err != nil {
		connection.Release()
		return nil, err
	}
	released := false
	return func() error {
		if released {
			return nil
		}
		released = true
		releaseContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		var unlocked bool
		unlockErr := connection.QueryRow(releaseContext,
			`select pg_advisory_unlock(hashtextextended($1,0))`, lockName).Scan(&unlocked)
		if unlockErr == nil && unlocked {
			connection.Release()
			return nil
		}
		rawConnection := connection.Hijack()
		closeErr := rawConnection.Close(releaseContext)
		if unlockErr != nil {
			return unlockErr
		}
		if closeErr != nil {
			return closeErr
		}
		return fmt.Errorf("upload session advisory lock was not held")
	}, nil
}

// AcquireUploadImageVariantContentLock serializes one immutable variant PUT
// without blocking the other two variants of the same image bundle. The lock
// is database-wide, so retries routed to another media-service instance still
// cannot create two accepted object versions for one logical part.
func (repository *Repository) AcquireUploadImageVariantContentLock(
	ctx context.Context,
	mediaID uuid.UUID,
	variant media.Variant,
) (func() error, error) {
	if mediaID == uuid.Nil || (variant != media.VariantSmall && variant != media.VariantMedium && variant != media.VariantLarge) {
		return nil, ErrConflict
	}
	connection, err := repository.pool.Acquire(ctx)
	if err != nil {
		return nil, err
	}
	lockName := "media-upload-image-variant:" + mediaID.String() + ":" + string(variant)
	if _, err := connection.Exec(ctx, `select pg_advisory_lock(hashtextextended($1,0))`, lockName); err != nil {
		connection.Release()
		return nil, err
	}
	released := false
	return func() error {
		if released {
			return nil
		}
		released = true
		releaseContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		var unlocked bool
		unlockErr := connection.QueryRow(releaseContext,
			`select pg_advisory_unlock(hashtextextended($1,0))`, lockName).Scan(&unlocked)
		if unlockErr == nil && unlocked {
			connection.Release()
			return nil
		}
		rawConnection := connection.Hijack()
		closeErr := rawConnection.Close(releaseContext)
		if unlockErr != nil {
			return unlockErr
		}
		if closeErr != nil {
			return closeErr
		}
		return fmt.Errorf("upload image variant advisory lock was not held")
	}, nil
}

// UploadMode distinguishes the retained single-source compatibility ingress
// from the Android-owned, client-produced still-image bundle.
type UploadMode string

const (
	// UploadModeSource accepts one legacy-compatible source object.
	UploadModeSource UploadMode = "SOURCE"
	// UploadModeImageVariants accepts exactly SMALL, MEDIUM, and LARGE WebP parts.
	UploadModeImageVariants UploadMode = "IMAGE_VARIANTS"
)

// UploadImageVariantExpectation is one authorized WebP part declared when an
// image upload session is created.
type UploadImageVariantExpectation struct {
	Variant        media.Variant
	ContentLength  int64
	ChecksumSHA256 string
	Width          int
	Height         int
	ObjectKey      string
}

// UploadImageVariantPart combines one declared image part with immutable
// object metadata recorded only after a verified streaming PUT succeeds.
type UploadImageVariantPart struct {
	UploadImageVariantExpectation
	UploadIdempotencyKey uuid.UUID
	ObjectVersionID      string
	ETag                 string
	UploadedAt           *time.Time
}

// AssetRecord is the persisted logical media asset, its upload session, and
// the current immutable source-generation metadata.
type AssetRecord struct {
	ID                uuid.UUID
	FolderID          uuid.UUID
	ClientReferenceID *uuid.UUID
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
	UploadMode        UploadMode
	UploadCompletedAt *time.Time
	CreatedBy         *ActorReference
}

const (
	// PrincipalTypeUser identifies a USER actor in media facts.
	PrincipalTypeUser = "USER"
	// PrincipalTypeWorker identifies a task-board worker actor in media facts.
	PrincipalTypeWorker = "WORKER"
)

// ActorReference is the sanitized actor identity retained for media facts.
// It intentionally uses worker_id, not the Android OAuth subject, for WORKER
// facts so task-board can correlate a READY evidence fact without trusting
// client-supplied metadata.
type ActorReference struct {
	SubjectID     uuid.UUID
	PrincipalType string
}

// VariantRecord describes one persisted immutable original or derivative.
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

// AssetWithVariants pairs a scoped logical asset with its safe variants.
type AssetWithVariants struct {
	Asset    AssetRecord
	Variants []VariantRecord
}

// CabinCoverRecord is a bounded CABIN cover projection with preview metadata.
type CabinCoverRecord struct {
	CabinID    uuid.UUID
	PhotoCount int64
	MediaID    uuid.UUID
	Generation int
	Variant    *VariantRecord
	Previews   []CabinPreviewRecord
}

// CabinPreviewRecord names one ordered READY image preview for a CABIN.
type CabinPreviewRecord struct {
	MediaID    uuid.UUID
	Generation int
	Variant    VariantRecord
}

// CreateUploadCommand contains all validated data needed to create or replay a
// constrained upload session for one logical media asset.
type CreateUploadCommand struct {
	MediaID             uuid.UUID
	FolderID            uuid.UUID
	UploadSessionID     uuid.UUID
	SubjectID           uuid.UUID
	PrincipalType       string
	Actor               ActorReference
	WorkerID            *uuid.UUID
	IdempotencyKey      uuid.UUID
	RequestSHA256       string
	OwnerType           string
	OwnerID             string
	WarehouseID         uuid.UUID
	AuthorizedSubjectID *uuid.UUID
	ClientReferenceID   *uuid.UUID
	Kind                media.Kind
	FileName            string
	ContentType         string
	ContentLength       int64
	ChecksumSHA256      string
	UploadMode          UploadMode
	ImageVariants       []UploadImageVariantExpectation
	SortOrder           int64
	SourceObjectKey     string
	UploadExpiresAt     time.Time
	CorrelationID       uuid.UUID
}

// CreateUpload creates one upload session or returns a safe exact idempotent
// replay, including a replacement session after pre-content expiry.
func (repository *Repository) CreateUpload(ctx context.Context, command CreateUploadCommand) (AssetRecord, bool, error) {
	if command.UploadMode == "" {
		command.UploadMode = UploadModeSource
	}
	if err := validateUploadMode(command); err != nil {
		return AssetRecord{}, false, err
	}
	actor, err := normalizeActor(command.SubjectID, command.PrincipalType, command.Actor)
	if err != nil {
		return AssetRecord{}, false, err
	}
	command.PrincipalType = actor.PrincipalType
	command.Actor = actor
	if err := validateCreateActor(command); err != nil {
		return AssetRecord{}, false, err
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return AssetRecord{}, false, err
	}
	defer tx.Rollback(ctx)
	if err := lockActorCommand(ctx, tx, command.PrincipalType, command.SubjectID, "CREATE_UPLOAD", command.IdempotencyKey); err != nil {
		return AssetRecord{}, false, err
	}
	if IsWorkerEvidenceOwnerType(command.OwnerType) {
		asset, replayed, err := repository.createWorkerEvidenceUpload(ctx, tx, command)
		if err != nil {
			return AssetRecord{}, false, err
		}
		if err := tx.Commit(ctx); err != nil {
			return AssetRecord{}, false, translateConstraint(err)
		}
		return asset, replayed, nil
	}
	if command.AuthorizedSubjectID != nil && (!IsCustomerSubjectBoundOwnerType(command.OwnerType) ||
		command.PrincipalType != PrincipalTypeUser || *command.AuthorizedSubjectID == uuid.Nil ||
		*command.AuthorizedSubjectID != command.SubjectID) {
		return AssetRecord{}, false, ErrOwnerProofMissing
	}
	if command.AuthorizedSubjectID != nil {
		if err := requireCustomerOwnerBindingLock(ctx, tx, command.OwnerType, command.OwnerID,
			command.WarehouseID, *command.AuthorizedSubjectID); err != nil {
			return AssetRecord{}, false, err
		}
	}

	if replay, found, err := repository.findCreateReplay(ctx, tx, command); err != nil {
		return AssetRecord{}, false, err
	} else if found {
		if err := tx.Commit(ctx); err != nil {
			return AssetRecord{}, false, err
		}
		return replay, true, nil
	}
	if command.AuthorizedSubjectID == nil {
		if err := requireOwnerBinding(ctx, tx, command.OwnerType, command.OwnerID,
			command.WarehouseID, repository.now()); err != nil {
			return AssetRecord{}, false, err
		}
	}
	if err := enforceOwnerMediaLimit(ctx, tx, command.OwnerType, command.OwnerID, command.WarehouseID, 100); err != nil {
		return AssetRecord{}, false, err
	}

	assetID := command.MediaID
	if assetID == uuid.Nil {
		assetID = uuid.New()
	}
	folderID := command.FolderID
	if folderID == uuid.Nil {
		folderID = assetID
	}
	sessionID := command.UploadSessionID
	if sessionID == uuid.Nil {
		sessionID = uuid.New()
	}
	now := repository.now().UTC()
	_, err = tx.Exec(ctx, `
		insert into media_asset (
			media_id, folder_id, client_reference_id, created_by_principal_type, created_by_actor_id,
			owner_type, owner_id, warehouse_id, media_kind,
			original_file_name, original_content_type, source_object_key,
			processing_status, sort_order, version, next_generation, created_at, updated_at)
		values ($1,$2,null,$3,$4,$5,$6,$7,$8,$9,$10,$11,'UPLOADING',$12,1,1,$13,$13)`,
		assetID, folderID, command.PrincipalType, actor.SubjectID, command.OwnerType, command.OwnerID,
		command.WarehouseID, command.Kind, command.FileName, command.ContentType,
		command.SourceObjectKey, command.SortOrder, now)
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	if command.OwnerType == OwnerTypeCabin && command.Kind == media.KindImage {
		cabinID, parseErr := uuid.Parse(command.OwnerID)
		if parseErr != nil || cabinID == uuid.Nil {
			return AssetRecord{}, false, ErrConflict
		}
		if err := associateCabinImageUpload(ctx, tx, cabinID, assetID,
			command.WarehouseID, folderID, command.SortOrder, now); err != nil {
			return AssetRecord{}, false, err
		}
	}
	_, err = tx.Exec(ctx, `
		insert into media_upload_session (
			upload_session_id, media_id, principal_type, subject_id, idempotency_key,
			expected_content_length, expected_content_type, expected_checksum_sha256,
			upload_mode, expires_at, created_at)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`,
		sessionID, assetID, command.PrincipalType, command.SubjectID, command.IdempotencyKey,
		command.ContentLength, command.ContentType, command.ChecksumSHA256,
		command.UploadMode, command.UploadExpiresAt.UTC(), now)
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	if err := insertUploadImageVariantExpectations(ctx, tx, assetID, command.ImageVariants); err != nil {
		return AssetRecord{}, false, err
	}
	_, err = tx.Exec(ctx, `
		insert into media_command_idempotency (
			principal_type, subject_id, command_type, idempotency_key, request_sha256,
			media_id, created_at, expires_at)
		values ($1,$2,'CREATE_UPLOAD',$3,$4,$5,$6,$7)`,
		command.PrincipalType, command.SubjectID, command.IdempotencyKey, command.RequestSHA256,
		assetID, now, now.Add(24*time.Hour))
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	if err := appendInitialEventForActor(ctx, tx, assetID, "media.upload.session.created.v1", actor, command.CorrelationID, now); err != nil {
		return AssetRecord{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	return AssetRecord{
		ID: assetID, FolderID: folderID, OwnerType: command.OwnerType, OwnerID: command.OwnerID,
		WarehouseID: command.WarehouseID, Kind: command.Kind, FileName: command.FileName,
		ContentType: command.ContentType, SourceObjectKey: command.SourceObjectKey,
		Status: media.StatusUploading, Version: 1, SortOrder: command.SortOrder, CreatedAt: now,
		UploadSessionID: sessionID, UploadExpiresAt: command.UploadExpiresAt.UTC(),
		ExpectedLength: command.ContentLength, ExpectedChecksum: command.ChecksumSHA256,
		UploadMode: command.UploadMode, CreatedBy: &actor,
	}, false, nil
}

func validateUploadMode(command CreateUploadCommand) error {
	switch command.UploadMode {
	case UploadModeSource:
		if len(command.ImageVariants) != 0 {
			return ErrConflict
		}
		return nil
	case UploadModeImageVariants:
		if command.Kind != media.KindImage || command.ContentType != "image/webp" ||
			command.ContentLength <= 0 || command.ContentLength > 1<<20 || len(command.ImageVariants) != 3 {
			return ErrConflict
		}
		seen := make(map[media.Variant]struct{}, 3)
		var total int64
		for _, part := range command.ImageVariants {
			if part.Variant != media.VariantSmall && part.Variant != media.VariantMedium && part.Variant != media.VariantLarge {
				return ErrConflict
			}
			if _, duplicate := seen[part.Variant]; duplicate {
				return ErrConflict
			}
			seen[part.Variant] = struct{}{}
			if part.ContentLength <= 0 || part.ContentLength > 1<<20 || part.Width <= 0 || part.Height <= 0 ||
				!validSHA256(part.ChecksumSHA256) || strings.TrimSpace(part.ObjectKey) == "" {
				return ErrConflict
			}
			total += part.ContentLength
			if total > 1<<20 {
				return ErrConflict
			}
			if part.Variant == media.VariantLarge && part.ObjectKey != command.SourceObjectKey {
				return ErrConflict
			}
		}
		if total != command.ContentLength || len(seen) != 3 {
			return ErrConflict
		}
		return nil
	default:
		return ErrConflict
	}
}

func insertUploadImageVariantExpectations(
	ctx context.Context,
	tx pgx.Tx,
	mediaID uuid.UUID,
	parts []UploadImageVariantExpectation,
) error {
	for _, part := range parts {
		_, err := tx.Exec(ctx, `insert into media_upload_image_variant_part (
			media_id,variant,expected_content_length,expected_checksum_sha256,width,height,object_key)
		values ($1,$2,$3,$4,$5,$6,$7)`, mediaID, part.Variant, part.ContentLength,
			part.ChecksumSHA256, part.Width, part.Height, part.ObjectKey)
		if err != nil {
			return translateConstraint(err)
		}
	}
	return nil
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
		where principal_type=$1 and subject_id=$2 and command_type='CREATE_UPLOAD' and idempotency_key=$3
		for update`, command.PrincipalType, command.SubjectID, command.IdempotencyKey).Scan(&requestSHA, &assetID)
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
	if err != nil {
		return asset, true, err
	}
	// Byte ingress keeps this same advisory lock while it streams and finalizes
	// content. Acquire it before locking the row: otherwise a slow, already
	// accepted upload can hold the content lock and wait for this row while a
	// replay waits for the content lock. The re-read below makes the decision
	// from the state after any in-flight upload has finished.
	if err := lockUploadSessionContentTransaction(ctx, tx, asset.UploadSessionID); err != nil {
		return asset, true, err
	}
	asset, err = scanAssetWithSession(tx.QueryRow(ctx, assetWithSessionSQL+`
		where a.media_id=$1 and media_asset_is_available(a.media_id) for update of a,s`, assetID))
	if err != nil {
		return asset, true, err
	}
	now := repository.now().UTC()
	if err = requireOwnerBinding(ctx, tx, asset.OwnerType, asset.OwnerID, asset.WarehouseID, now); err != nil {
		return asset, true, err
	}

	// A CREATE_UPLOAD replay normally returns its existing, still-open session.
	// If that session expired before any bytes were finalized, the same logical
	// asset may safely receive a replacement session. This matters for durable
	// mobile outboxes: an old client retries the exact create request, then uses
	// the session returned by this response for byte ingress. The media ID,
	// source object key, owner and idempotency identity remain immutable.
	if asset.UploadCompletedAt != nil || asset.Status != media.StatusUploading {
		return asset, true, ErrConflict
	}
	if now.Before(asset.UploadExpiresAt) {
		return asset, true, nil
	}
	if !now.Before(command.UploadExpiresAt) {
		return asset, true, ErrConflict
	}

	newSessionID := command.UploadSessionID
	if newSessionID == uuid.Nil {
		newSessionID = uuid.New()
	}
	result, err := tx.Exec(ctx, `
		update media_upload_session
		set upload_session_id=$1, principal_type=$2, subject_id=$3, idempotency_key=$4,
			expected_content_length=$5, expected_content_type=$6, expected_checksum_sha256=$7,
			upload_mode=$8, expires_at=$9, completed_at=null, created_at=$10
		where media_id=$11 and upload_session_id=$12 and principal_type=$2 and subject_id=$3
			and completed_at is null and expires_at <= $10`,
		newSessionID, command.PrincipalType, command.SubjectID, command.IdempotencyKey,
		command.ContentLength, command.ContentType, command.ChecksumSHA256,
		command.UploadMode, command.UploadExpiresAt.UTC(), now, asset.ID, asset.UploadSessionID)
	if err != nil {
		return asset, true, translateConstraint(err)
	}
	if result.RowsAffected() != 1 {
		return asset, true, ErrConflict
	}
	_, err = tx.Exec(ctx, `
		update media_command_idempotency set expires_at=$1
		where principal_type=$2 and subject_id=$3 and command_type='CREATE_UPLOAD' and idempotency_key=$4`,
		now.Add(24*time.Hour), command.PrincipalType, command.SubjectID, command.IdempotencyKey)
	if err != nil {
		return asset, true, translateConstraint(err)
	}
	asset.UploadSessionID = newSessionID
	asset.UploadExpiresAt = command.UploadExpiresAt.UTC()
	asset.ExpectedLength = command.ContentLength
	asset.ExpectedChecksum = command.ChecksumSHA256
	asset.UploadMode = command.UploadMode
	return asset, true, nil
}

func validateCreateActor(command CreateUploadCommand) error {
	if IsWorkerEvidenceOwnerType(command.OwnerType) {
		if command.ClientReferenceID == nil || *command.ClientReferenceID == uuid.Nil || command.Kind != media.KindImage {
			return ErrConflict
		}
		if command.PrincipalType == PrincipalTypeWorker {
			if command.WorkerID == nil || *command.WorkerID == uuid.Nil ||
				command.Actor.PrincipalType != PrincipalTypeWorker || command.IdempotencyKey != *command.ClientReferenceID {
				return ErrConflict
			}
			return nil
		}
		if command.WorkerID != nil {
			return ErrConflict
		}
		return nil
	}
	if IsWorkerProfileOwnerType(command.OwnerType) {
		ownerID, err := uuid.Parse(command.OwnerID)
		if err != nil || ownerID == uuid.Nil || command.Kind != media.KindImage ||
			command.ClientReferenceID != nil || command.WorkerID == nil || *command.WorkerID == uuid.Nil ||
			command.PrincipalType != PrincipalTypeWorker || command.Actor.PrincipalType != PrincipalTypeWorker ||
			command.Actor.SubjectID != *command.WorkerID || ownerID != *command.WorkerID {
			return ErrConflict
		}
		return nil
	}
	if command.ClientReferenceID != nil || command.WorkerID != nil || command.PrincipalType != PrincipalTypeUser {
		return ErrConflict
	}
	return nil
}

func (repository *Repository) createWorkerEvidenceUpload(
	ctx context.Context,
	tx pgx.Tx,
	command CreateUploadCommand,
) (AssetRecord, bool, error) {
	if err := requireOwnerBinding(ctx, tx, command.OwnerType, command.OwnerID, command.WarehouseID, repository.now()); err != nil {
		return AssetRecord{}, false, err
	}
	if command.WorkerID != nil {
		ownerID, err := uuid.Parse(command.OwnerID)
		if err != nil || ownerID == uuid.Nil {
			return AssetRecord{}, false, ErrConflict
		}
		if err := requireWorkerEvidenceUploadAccess(ctx, tx, command.OwnerType, ownerID,
			command.WarehouseID, *command.WorkerID); err != nil {
			return AssetRecord{}, false, err
		}
	}
	if command.ClientReferenceID == nil {
		return AssetRecord{}, false, ErrConflict
	}
	if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		"worker-evidence:"+command.OwnerType+":"+command.OwnerID+":"+command.ClientReferenceID.String()); err != nil {
		return AssetRecord{}, false, err
	}

	var priorRequestSHA string
	var priorAssetID uuid.UUID
	err := tx.QueryRow(ctx, `select request_sha256,media_id from media_command_idempotency
		where principal_type=$1 and subject_id=$2 and command_type='CREATE_UPLOAD' and idempotency_key=$3
		for update`, command.PrincipalType, command.SubjectID, command.IdempotencyKey).
		Scan(&priorRequestSHA, &priorAssetID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, err
	}
	if err == nil && priorRequestSHA != command.RequestSHA256 {
		return AssetRecord{}, false, ErrIdempotencyMismatch
	}

	asset, existing, err := workerEvidenceAssetForUpdate(ctx, tx, command)
	if err != nil {
		return AssetRecord{}, false, err
	}
	now := repository.now().UTC()
	if existing {
		if priorAssetID != uuid.Nil && priorAssetID != asset.ID {
			return AssetRecord{}, false, ErrIdempotencyMismatch
		}
		if asset.CreatedBy == nil || asset.CreatedBy.PrincipalType != command.Actor.PrincipalType ||
			asset.CreatedBy.SubjectID != command.Actor.SubjectID || !sameWorkerEvidenceRequest(asset, command) {
			return AssetRecord{}, false, ErrIdempotencyMismatch
		}
		if asset.Status == media.StatusUploading && asset.UploadCompletedAt == nil && !now.Before(asset.UploadExpiresAt) {
			newSessionID := command.UploadSessionID
			if newSessionID == uuid.Nil {
				newSessionID = uuid.New()
			}
			_, err := tx.Exec(ctx, `update media_upload_session set upload_session_id=$1,
				principal_type=$2,subject_id=$3,idempotency_key=$4,expected_content_length=$5,
				expected_content_type=$6,expected_checksum_sha256=$7,upload_mode=$8,
				expires_at=$9,completed_at=null,created_at=$10
				where media_id=$11 and principal_type=$2 and subject_id=$3`,
				newSessionID, command.PrincipalType, command.SubjectID, command.IdempotencyKey,
				command.ContentLength, command.ContentType, command.ChecksumSHA256,
				command.UploadMode, command.UploadExpiresAt.UTC(), now, asset.ID)
			if err != nil {
				return AssetRecord{}, false, translateConstraint(err)
			}
			asset.UploadSessionID = newSessionID
			asset.UploadExpiresAt = command.UploadExpiresAt.UTC()
			asset.ExpectedLength = command.ContentLength
			asset.ExpectedChecksum = command.ChecksumSHA256
			asset.UploadMode = command.UploadMode
		}
		_, err = tx.Exec(ctx, `update media_command_idempotency set request_sha256=$1,expires_at=$2
			where principal_type=$3 and subject_id=$4 and command_type='CREATE_UPLOAD' and idempotency_key=$5`,
			command.RequestSHA256, now.Add(24*time.Hour), command.PrincipalType, command.SubjectID,
			command.IdempotencyKey)
		if err != nil {
			return AssetRecord{}, false, translateConstraint(err)
		}
		return asset, true, nil
	}
	if err := enforceOwnerMediaLimit(ctx, tx, command.OwnerType, command.OwnerID, command.WarehouseID, 100); err != nil {
		return AssetRecord{}, false, err
	}
	assetID := command.MediaID
	if assetID == uuid.Nil {
		assetID = uuid.New()
	}
	folderID := command.FolderID
	if folderID == uuid.Nil {
		folderID = assetID
	}
	sessionID := command.UploadSessionID
	if sessionID == uuid.Nil {
		sessionID = uuid.New()
	}
	_, err = tx.Exec(ctx, `insert into media_asset (
		media_id,folder_id,client_reference_id,created_by_principal_type,created_by_actor_id,
		owner_type,owner_id,warehouse_id,media_kind,original_file_name,original_content_type,
		source_object_key,processing_status,sort_order,version,next_generation,created_at,updated_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,'UPLOADING',$13,1,1,$14,$14)`,
		assetID, folderID, *command.ClientReferenceID, command.Actor.PrincipalType, command.Actor.SubjectID,
		command.OwnerType, command.OwnerID, command.WarehouseID, command.Kind, command.FileName, command.ContentType,
		command.SourceObjectKey, command.SortOrder, now)
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `insert into media_upload_session (
		upload_session_id,media_id,principal_type,subject_id,idempotency_key,expected_content_length,
		expected_content_type,expected_checksum_sha256,upload_mode,expires_at,created_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`, sessionID, assetID, command.PrincipalType,
		command.SubjectID, command.IdempotencyKey, command.ContentLength, command.ContentType,
		command.ChecksumSHA256, command.UploadMode, command.UploadExpiresAt.UTC(), now)
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	if err := insertUploadImageVariantExpectations(ctx, tx, assetID, command.ImageVariants); err != nil {
		return AssetRecord{}, false, err
	}
	_, err = tx.Exec(ctx, `insert into media_command_idempotency (
		principal_type,subject_id,command_type,idempotency_key,request_sha256,media_id,created_at,expires_at)
	values ($1,$2,'CREATE_UPLOAD',$3,$4,$5,$6,$7)`, command.PrincipalType, command.SubjectID,
		command.IdempotencyKey, command.RequestSHA256, assetID, now, now.Add(24*time.Hour))
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	if err := appendInitialEventForActor(ctx, tx, assetID, "media.upload.session.created.v1", command.Actor,
		command.CorrelationID, now); err != nil {
		return AssetRecord{}, false, err
	}
	clientReferenceID := *command.ClientReferenceID
	return AssetRecord{
		ID: assetID, FolderID: folderID, ClientReferenceID: &clientReferenceID,
		OwnerType: command.OwnerType, OwnerID: command.OwnerID, WarehouseID: command.WarehouseID,
		Kind: command.Kind, FileName: command.FileName, ContentType: command.ContentType,
		SourceObjectKey: command.SourceObjectKey, Status: media.StatusUploading, Version: 1,
		SortOrder: command.SortOrder, CreatedAt: now, UploadSessionID: sessionID,
		UploadExpiresAt: command.UploadExpiresAt.UTC(), ExpectedLength: command.ContentLength,
		ExpectedChecksum: command.ChecksumSHA256, UploadMode: command.UploadMode,
		CreatedBy: &command.Actor,
	}, false, nil
}

func workerEvidenceAssetForUpdate(ctx context.Context, tx pgx.Tx, command CreateUploadCommand) (AssetRecord, bool, error) {
	var mediaID uuid.UUID
	var deletedAt *time.Time
	err := tx.QueryRow(ctx, `select media_id,deleted_at from media_asset
		where owner_type=$1 and owner_id=$2 and client_reference_id=$3 for update`,
		command.OwnerType, command.OwnerID, *command.ClientReferenceID).Scan(&mediaID, &deletedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, false, nil
	}
	if err != nil {
		return AssetRecord{}, false, err
	}
	if deletedAt != nil {
		// Evidence IDs are permanent logical identifiers. A soft-deleted
		// evidence item stays unavailable and cannot be silently replaced by
		// a second asset using the same ID.
		return AssetRecord{}, true, ErrConflict
	}
	asset, err := scanAssetWithSession(tx.QueryRow(ctx, assetWithSessionSQL+`
		where a.media_id=$1 and media_asset_is_available(a.media_id) for update of a,s`, mediaID))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, true, ErrConflict
	}
	if err != nil {
		return AssetRecord{}, true, err
	}
	return asset, true, nil
}

func sameWorkerEvidenceRequest(asset AssetRecord, command CreateUploadCommand) bool {
	return asset.OwnerType == command.OwnerType && IsWorkerEvidenceOwnerType(asset.OwnerType) && asset.OwnerID == command.OwnerID &&
		asset.WarehouseID == command.WarehouseID && asset.Kind == command.Kind &&
		asset.FileName == command.FileName && asset.ContentType == command.ContentType &&
		asset.SortOrder == command.SortOrder && asset.ExpectedLength == command.ContentLength &&
		asset.ExpectedChecksum == command.ChecksumSHA256 && asset.UploadMode == command.UploadMode &&
		asset.ClientReferenceID != nil &&
		command.ClientReferenceID != nil && *asset.ClientReferenceID == *command.ClientReferenceID
}

func requireWorkerEvidenceUploadAccess(ctx context.Context, tx pgx.Tx, ownerType string, ownerID, warehouseID, workerID uuid.UUID) error {
	switch ownerType {
	case OwnerTypeTaskBoardEntry:
		return RequireTaskBoardEntryWorkerAccess(ctx, tx, ownerID, warehouseID, workerID)
	case OwnerTypeDriverShift:
		return RequireDriverShiftWorkerAccess(ctx, tx, ownerID, warehouseID, workerID)
	default:
		return ErrOwnerProofMissing
	}
}

// UploadSessionForSubject returns an upload session only when the USER subject
// that created it still owns the session.
func (repository *Repository) UploadSessionForSubject(
	ctx context.Context,
	sessionID, subjectID uuid.UUID,
) (AssetRecord, error) {
	return repository.UploadSessionForPrincipal(ctx, sessionID, subjectID, PrincipalTypeUser)
}

// UploadSessionForPrincipal returns an upload session only for its original
// principal subject and principal type.
func (repository *Repository) UploadSessionForPrincipal(
	ctx context.Context,
	sessionID, subjectID uuid.UUID,
	principalType string,
) (AssetRecord, error) {
	if subjectID == uuid.Nil || (principalType != PrincipalTypeUser && principalType != PrincipalTypeWorker) {
		return AssetRecord{}, ErrNotFound
	}
	asset, err := scanAssetWithSession(repository.pool.QueryRow(ctx,
		assetWithSessionSQL+` where s.upload_session_id=$1 and s.subject_id=$2 and s.principal_type=$3
			and media_asset_is_available(a.media_id)`, sessionID, subjectID, principalType))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, ErrNotFound
	}
	return asset, err
}

// UploadSessionForCustomer returns a customer-owned upload session only while
// the exact CustomerApp subject remains bound by the current owner proof.
func (repository *Repository) UploadSessionForCustomer(
	ctx context.Context,
	sessionID, subjectID uuid.UUID,
) (AssetRecord, error) {
	if sessionID == uuid.Nil || subjectID == uuid.Nil {
		return AssetRecord{}, ErrNotFound
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return AssetRecord{}, err
	}
	defer tx.Rollback(ctx)
	asset, err := scanAssetWithSession(tx.QueryRow(ctx,
		assetWithSessionSQL+` where s.upload_session_id=$1 and s.subject_id=$2
			and s.principal_type='USER' and media_asset_is_available(a.media_id)`,
		sessionID, subjectID))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, ErrNotFound
	}
	if err != nil {
		return AssetRecord{}, err
	}
	if err := requireCustomerOwnerBindingLock(ctx, tx, asset.OwnerType, asset.OwnerID,
		asset.WarehouseID, subjectID); err != nil {
		return AssetRecord{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return AssetRecord{}, err
	}
	return asset, nil
}

// UploadImageVariantForPrincipal returns one declared WebP part only to the
// principal that owns its still-open upload session.
func (repository *Repository) UploadImageVariantForPrincipal(
	ctx context.Context,
	sessionID, subjectID uuid.UUID,
	principalType string,
	variant media.Variant,
) (AssetRecord, UploadImageVariantPart, error) {
	asset, err := repository.UploadSessionForPrincipal(ctx, sessionID, subjectID, principalType)
	if err != nil {
		return AssetRecord{}, UploadImageVariantPart{}, err
	}
	if asset.UploadMode != UploadModeImageVariants || asset.Kind != media.KindImage {
		return AssetRecord{}, UploadImageVariantPart{}, ErrNotFound
	}
	part, err := scanUploadImageVariantPart(repository.pool.QueryRow(ctx, uploadImageVariantPartSQL+`
		where media_id=$1 and variant=$2`, asset.ID, variant))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, UploadImageVariantPart{}, ErrNotFound
	}
	return asset, part, err
}

// UploadImageVariantForCustomer returns one declared customer image part only
// while the exact CustomerApp subject remains bound to the owner.
func (repository *Repository) UploadImageVariantForCustomer(
	ctx context.Context,
	sessionID, subjectID uuid.UUID,
	variant media.Variant,
) (AssetRecord, UploadImageVariantPart, error) {
	asset, err := repository.UploadSessionForCustomer(ctx, sessionID, subjectID)
	if err != nil {
		return AssetRecord{}, UploadImageVariantPart{}, err
	}
	if asset.UploadMode != UploadModeImageVariants || asset.Kind != media.KindImage {
		return AssetRecord{}, UploadImageVariantPart{}, ErrNotFound
	}
	part, err := scanUploadImageVariantPart(repository.pool.QueryRow(ctx, uploadImageVariantPartSQL+`
		where media_id=$1 and variant=$2`, asset.ID, variant))
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, UploadImageVariantPart{}, ErrNotFound
	}
	return asset, part, err
}

// CompleteUploadImageVariantCommand records immutable metadata for one
// already streamed and verified client-produced WebP object.
type CompleteUploadImageVariantCommand struct {
	SessionID           uuid.UUID
	SubjectID           uuid.UUID
	PrincipalType       string
	AuthorizedSubjectID *uuid.UUID
	MediaID             uuid.UUID
	Variant             media.Variant
	IdempotencyKey      uuid.UUID
	ObjectVersionID     string
	ETag                string
	ChecksumSHA256      string
	SizeBytes           int64
}

// CompleteUploadImageVariant persists one exact variant PUT or returns an
// exact idempotent replay. It never finalizes the parent media asset.
func (repository *Repository) CompleteUploadImageVariant(
	ctx context.Context,
	command CompleteUploadImageVariantCommand,
) (UploadImageVariantPart, bool, error) {
	if command.SessionID == uuid.Nil || command.SubjectID == uuid.Nil || command.MediaID == uuid.Nil ||
		command.IdempotencyKey == uuid.Nil || command.ObjectVersionID == "" || len(command.ObjectVersionID) > 255 ||
		command.ETag == "" || len(command.ETag) > 255 || !validSHA256(command.ChecksumSHA256) || command.SizeBytes <= 0 ||
		(command.PrincipalType != PrincipalTypeUser && command.PrincipalType != PrincipalTypeWorker) {
		return UploadImageVariantPart{}, false, ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return UploadImageVariantPart{}, false, err
	}
	defer tx.Rollback(ctx)
	if command.AuthorizedSubjectID != nil {
		if command.PrincipalType != PrincipalTypeUser || *command.AuthorizedSubjectID == uuid.Nil ||
			*command.AuthorizedSubjectID != command.SubjectID {
			return UploadImageVariantPart{}, false, ErrOwnerProofMissing
		}
		asset, assetErr := repository.assetForUpdate(ctx, tx, command.MediaID)
		if assetErr != nil {
			return UploadImageVariantPart{}, false, assetErr
		}
		if bindingErr := requireCustomerOwnerBindingLock(ctx, tx, asset.OwnerType, asset.OwnerID,
			asset.WarehouseID, *command.AuthorizedSubjectID); bindingErr != nil {
			return UploadImageVariantPart{}, false, bindingErr
		}
	}
	var expiresAt time.Time
	var completedAt *time.Time
	var uploadMode UploadMode
	var assetStatus media.Status
	err = tx.QueryRow(ctx, `select s.expires_at,s.completed_at,s.upload_mode,a.processing_status
		from media_upload_session s join media_asset a on a.media_id=s.media_id
		where s.upload_session_id=$1 and s.media_id=$2 and s.subject_id=$3 and s.principal_type=$4
		  and media_asset_is_available(a.media_id)
		for update of s`, command.SessionID, command.MediaID, command.SubjectID, command.PrincipalType).
		Scan(&expiresAt, &completedAt, &uploadMode, &assetStatus)
	if errors.Is(err, pgx.ErrNoRows) {
		return UploadImageVariantPart{}, false, ErrNotFound
	}
	if err != nil {
		return UploadImageVariantPart{}, false, err
	}
	if uploadMode != UploadModeImageVariants {
		return UploadImageVariantPart{}, false, ErrConflict
	}
	part, err := scanUploadImageVariantPart(tx.QueryRow(ctx, uploadImageVariantPartSQL+`
		where media_id=$1 and variant=$2 for update`, command.MediaID, command.Variant))
	if errors.Is(err, pgx.ErrNoRows) {
		return UploadImageVariantPart{}, false, ErrNotFound
	}
	if err != nil {
		return UploadImageVariantPart{}, false, err
	}
	if part.ContentLength != command.SizeBytes || part.ChecksumSHA256 != command.ChecksumSHA256 {
		return UploadImageVariantPart{}, false, ErrConflict
	}
	if part.UploadedAt != nil {
		if part.UploadIdempotencyKey != command.IdempotencyKey || part.ObjectVersionID != command.ObjectVersionID ||
			part.ETag != command.ETag {
			return UploadImageVariantPart{}, false, ErrIdempotencyMismatch
		}
		if err := tx.Commit(ctx); err != nil {
			return UploadImageVariantPart{}, false, err
		}
		return part, true, nil
	}
	if completedAt != nil || assetStatus != media.StatusUploading || !repository.now().Before(expiresAt) {
		return UploadImageVariantPart{}, false, ErrConflict
	}
	var uploadedAt time.Time
	err = tx.QueryRow(ctx, `update media_upload_image_variant_part
		set upload_idempotency_key=$3,object_version_id=$4,etag=$5,
			uploaded_checksum_sha256=$6,uploaded_at=clock_timestamp()
		where media_id=$1 and variant=$2 and uploaded_at is null
		returning uploaded_at`, command.MediaID, command.Variant, command.IdempotencyKey,
		command.ObjectVersionID, command.ETag, command.ChecksumSHA256).Scan(&uploadedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return UploadImageVariantPart{}, false, ErrConflict
	}
	if err != nil {
		return UploadImageVariantPart{}, false, translateConstraint(err)
	}
	part.UploadIdempotencyKey = command.IdempotencyKey
	part.ObjectVersionID = command.ObjectVersionID
	part.ETag = command.ETag
	part.UploadedAt = &uploadedAt
	if err := tx.Commit(ctx); err != nil {
		return UploadImageVariantPart{}, false, err
	}
	return part, false, nil
}

const uploadImageVariantPartSQL = `select variant,expected_content_length,
	expected_checksum_sha256,width,height,object_key,upload_idempotency_key,
	coalesce(object_version_id,''),coalesce(etag,''),uploaded_at
	from media_upload_image_variant_part `

func scanUploadImageVariantPart(row rowScanner) (UploadImageVariantPart, error) {
	var part UploadImageVariantPart
	var uploadID *uuid.UUID
	err := row.Scan(&part.Variant, &part.ContentLength, &part.ChecksumSHA256, &part.Width,
		&part.Height, &part.ObjectKey, &uploadID, &part.ObjectVersionID, &part.ETag, &part.UploadedAt)
	if err == nil && uploadID != nil {
		part.UploadIdempotencyKey = *uploadID
	}
	return part, err
}

func uploadImageVariantParts(
	ctx context.Context,
	database interface {
		Query(context.Context, string, ...any) (pgx.Rows, error)
	},
	mediaID uuid.UUID,
) ([]UploadImageVariantPart, error) {
	rows, err := database.Query(ctx, uploadImageVariantPartSQL+`
		where media_id=$1 order by variant`, mediaID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	parts := make([]UploadImageVariantPart, 0, 3)
	for rows.Next() {
		part, scanErr := scanUploadImageVariantPart(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		parts = append(parts, part)
	}
	return parts, rows.Err()
}

// FinalizeImageVariant is immutable object metadata echoed by a client after
// every WebP part PUT has succeeded.
type FinalizeImageVariant struct {
	Variant         media.Variant
	ObjectVersionID string
	ETag            string
	ChecksumSHA256  string
}

// FinalizeCommand confirms either one pinned compatibility source or one
// complete client-produced image bundle for an authorized upload session.
type FinalizeCommand struct {
	SessionID           uuid.UUID
	SubjectID           uuid.UUID
	PrincipalType       string
	Actor               ActorReference
	WorkerID            *uuid.UUID
	AuthorizedSubjectID *uuid.UUID
	IdempotencyKey      uuid.UUID
	RequestSHA256       string
	ObjectVersionID     string
	ETag                string
	ChecksumSHA256      string
	ContentType         string
	SizeBytes           int64
	ImageVariants       []FinalizeImageVariant
	CorrelationID       uuid.UUID
}

// FinalizeUpload confirms a version-pinned upload and atomically enqueues its
// media-processing request, or returns an exact idempotent replay. Source mode
// validates immutable object metadata against expectations reloaded from the
// locked upload session rather than trusting caller or asset projection state.
func (repository *Repository) FinalizeUpload(ctx context.Context, command FinalizeCommand) (AssetRecord, bool, error) {
	actor, err := normalizeActor(command.SubjectID, command.PrincipalType, command.Actor)
	if err != nil {
		return AssetRecord{}, false, err
	}
	command.PrincipalType = actor.PrincipalType
	command.Actor = actor
	if command.PrincipalType == PrincipalTypeWorker && (command.WorkerID == nil || *command.WorkerID == uuid.Nil) {
		return AssetRecord{}, false, ErrConflict
	}
	if command.PrincipalType == PrincipalTypeUser && command.WorkerID != nil {
		return AssetRecord{}, false, ErrConflict
	}
	if command.AuthorizedSubjectID != nil && (command.PrincipalType != PrincipalTypeUser ||
		*command.AuthorizedSubjectID == uuid.Nil || *command.AuthorizedSubjectID != command.SubjectID) {
		return AssetRecord{}, false, ErrOwnerProofMissing
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return AssetRecord{}, false, err
	}
	defer tx.Rollback(ctx)
	if err := lockActorCommand(ctx, tx, command.PrincipalType, command.SubjectID, "FINALIZE_UPLOAD", command.IdempotencyKey); err != nil {
		return AssetRecord{}, false, err
	}

	var assetID uuid.UUID
	var existingSHA string
	err = tx.QueryRow(ctx, `
		select request_sha256, media_id from media_command_idempotency
		where principal_type=$1 and subject_id=$2 and command_type='FINALIZE_UPLOAD' and idempotency_key=$3
		for update`, command.PrincipalType, command.SubjectID, command.IdempotencyKey).Scan(&existingSHA, &assetID)
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
			where upload_session_id=$1 and subject_id=$2 and principal_type=$3`, command.SessionID,
			command.SubjectID, command.PrincipalType).Scan(&routeAssetID)
		if routeErr != nil {
			if errors.Is(routeErr, pgx.ErrNoRows) {
				return AssetRecord{}, false, ErrIdempotencyMismatch
			}
			return AssetRecord{}, false, routeErr
		}
		if routeAssetID != assetID {
			return AssetRecord{}, false, ErrIdempotencyMismatch
		}
		if proofErr := requireUploadOwnerAccess(ctx, tx, asset.OwnerType, asset.OwnerID, asset.WarehouseID,
			command.AuthorizedSubjectID, repository.now()); proofErr != nil {
			return AssetRecord{}, false, proofErr
		}
		if err := requireFinalizeWorkerAccess(ctx, tx, asset, command); err != nil {
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

	var expiresAt time.Time
	var completedAt *time.Time
	var uploadMode UploadMode
	var expectedLength int64
	var expectedContentType, expectedChecksum string
	err = tx.QueryRow(ctx, `
		select s.media_id,s.expires_at,s.completed_at,s.upload_mode,
			s.expected_content_length,s.expected_content_type,
			coalesce(s.expected_checksum_sha256,'')
		from media_upload_session s
		join media_asset a on a.media_id=s.media_id
		where s.upload_session_id=$1 and s.subject_id=$2 and s.principal_type=$3
		  and media_asset_is_available(a.media_id) for update of s`,
		command.SessionID, command.SubjectID, command.PrincipalType).
		Scan(&assetID, &expiresAt, &completedAt, &uploadMode, &expectedLength,
			&expectedContentType, &expectedChecksum)
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
	if err := requireUploadOwnerAccess(ctx, tx, asset.OwnerType, asset.OwnerID, asset.WarehouseID,
		command.AuthorizedSubjectID, repository.now()); err != nil {
		return AssetRecord{}, false, err
	}
	if err := requireFinalizeWorkerAccess(ctx, tx, asset, command); err != nil {
		return AssetRecord{}, false, err
	}
	if asset.Status != media.StatusUploading {
		return AssetRecord{}, false, ErrConflict
	}
	if asset.ContentType != expectedContentType {
		return AssetRecord{}, false, ErrConflict
	}
	asset.UploadMode = uploadMode
	asset.ExpectedLength = expectedLength
	asset.ExpectedChecksum = expectedChecksum
	command, err = requireFinalizeUploadParts(ctx, tx, asset, command)
	if err != nil {
		return AssetRecord{}, false, err
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
			principal_type,subject_id,command_type,idempotency_key,request_sha256,media_id,created_at,expires_at)
		values ($1,$2,'FINALIZE_UPLOAD',$3,$4,$5,$6,$7)`,
		command.PrincipalType, command.SubjectID, command.IdempotencyKey, command.RequestSHA256, assetID,
		repository.now().UTC(), repository.now().UTC().Add(24*time.Hour))
	if err != nil {
		return AssetRecord{}, false, translateConstraint(err)
	}
	now := repository.now().UTC()
	asset.Status = media.StatusProcessing
	uploadedID := uuid.New()
	if err := appendEventWithIDForActor(ctx, tx, uploadedID, assetID, "media.media.uploaded.v1", actor, command.CorrelationID, now); err != nil {
		return AssetRecord{}, false, err
	}
	err = insertFactOutboxForActor(ctx, tx, uploadedID, asset, asset.Version, "media.media.uploaded.v1", &actor, command.CorrelationID, now, generation, media.Rotation0, nil)
	if err != nil {
		return AssetRecord{}, false, err
	}
	asset.SourceVersionID = command.ObjectVersionID
	asset.SourceETag = command.ETag
	asset.SourceChecksum = command.ChecksumSHA256
	if err := insertProcessingRequestOutbox(ctx, tx, jobID, asset, generation, command.CorrelationID, now, &uploadedID); err != nil {
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

func requireFinalizeUploadParts(
	ctx context.Context,
	tx pgx.Tx,
	asset AssetRecord,
	command FinalizeCommand,
) (FinalizeCommand, error) {
	switch asset.UploadMode {
	case UploadModeSource:
		if len(command.ImageVariants) != 0 || command.ObjectVersionID == "" || len(command.ObjectVersionID) > 255 ||
			command.ETag == "" || len(command.ETag) > 255 || !validSHA256(command.ChecksumSHA256) ||
			command.ChecksumSHA256 != asset.ExpectedChecksum || command.SizeBytes != asset.ExpectedLength ||
			command.ContentType != asset.ContentType {
			return command, ErrConflict
		}
		return command, nil
	case UploadModeImageVariants:
		if asset.Kind != media.KindImage || len(command.ImageVariants) != 3 {
			return command, ErrConflict
		}
		parts, err := uploadImageVariantParts(ctx, tx, asset.ID)
		if err != nil {
			return command, err
		}
		if len(parts) != 3 {
			return command, ErrConflict
		}
		requested := make(map[media.Variant]FinalizeImageVariant, 3)
		for _, item := range command.ImageVariants {
			if _, duplicate := requested[item.Variant]; duplicate || item.ObjectVersionID == "" ||
				len(item.ObjectVersionID) > 255 || item.ETag == "" || len(item.ETag) > 255 ||
				!validSHA256(item.ChecksumSHA256) {
				return command, ErrConflict
			}
			requested[item.Variant] = item
		}
		var large UploadImageVariantPart
		for _, part := range parts {
			item, found := requested[part.Variant]
			if !found || part.UploadedAt == nil || item.ObjectVersionID != part.ObjectVersionID ||
				item.ETag != part.ETag || item.ChecksumSHA256 != part.ChecksumSHA256 {
				return command, ErrConflict
			}
			if part.Variant == media.VariantLarge {
				large = part
			}
		}
		if large.UploadedAt == nil || large.ObjectKey != asset.SourceObjectKey {
			return command, ErrConflict
		}
		command.ObjectVersionID = large.ObjectVersionID
		command.ETag = large.ETag
		command.ChecksumSHA256 = large.ChecksumSHA256
		command.ContentType = "image/webp"
		command.SizeBytes = large.ContentLength
		return command, nil
	default:
		return command, ErrConflict
	}
}

func requireFinalizeWorkerAccess(ctx context.Context, tx pgx.Tx, asset AssetRecord, command FinalizeCommand) error {
	if command.PrincipalType != PrincipalTypeWorker {
		return nil
	}
	if command.WorkerID == nil || asset.CreatedBy == nil ||
		asset.CreatedBy.PrincipalType != PrincipalTypeWorker || asset.CreatedBy.SubjectID != command.Actor.SubjectID {
		return ErrOwnerProofMissing
	}
	ownerID, err := uuid.Parse(asset.OwnerID)
	if err != nil || ownerID == uuid.Nil {
		return ErrOwnerProofMissing
	}
	if IsWorkerProfileOwnerType(asset.OwnerType) {
		if ownerID != *command.WorkerID || command.Actor.SubjectID != *command.WorkerID {
			return ErrOwnerProofMissing
		}
		return nil
	}
	if !IsWorkerEvidenceOwnerType(asset.OwnerType) {
		return ErrOwnerProofMissing
	}
	return requireWorkerEvidenceUploadAccess(ctx, tx, asset.OwnerType, ownerID, asset.WarehouseID, *command.WorkerID)
}

// ListOwner returns a bounded page of owner-scoped assets in presentation order.
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
// ReadOwnerAssets share-locks current owner proof and streams a bounded scoped
// asset page to consume.
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
	if ownerType == OwnerTypeLogisticsCustomerProfile {
		return ErrOwnerProofMissing
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var records []AssetWithVariants
	if ownerType == OwnerTypeCabin {
		records, err = readCabinPhotoAssets(ctx, tx, ownerID, warehouseID, limit, after,
			repository.now)
	} else if ownerType == OwnerTypeTaskBoardEntry {
		entryID, parseErr := uuid.Parse(ownerID)
		if parseErr != nil || entryID == uuid.Nil {
			return ErrConflict
		}
		if err := RequireTaskBoardEntryUserReadAccess(ctx, tx, entryID, warehouseID); err != nil {
			return err
		}
		records, err = readTaskBoardEntryAssetsForUser(ctx, tx, entryID, warehouseID, limit, after)
	} else {
		records, err = readOwnerAssets(ctx, tx, ownerType, ownerID, warehouseID, limit, after,
			repository.now, true)
	}
	if err != nil {
		return err
	}
	if err := consume(records); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ReadOwnerAssetsForCustomer returns customer media only while the current
// owner proof names the exact CustomerApp subject for the full callback.
func (repository *Repository) ReadOwnerAssetsForCustomer(
	ctx context.Context,
	ownerType, ownerID string,
	warehouseID, subjectID uuid.UUID,
	limit int,
	after *uuid.UUID,
	consume func([]AssetWithVariants) error,
) error {
	if !IsCustomerSubjectBoundOwnerType(ownerType) || subjectID == uuid.Nil || consume == nil ||
		limit < 1 || limit > 100 {
		return ErrOwnerProofMissing
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := requireCustomerOwnerBindingLock(ctx, tx, ownerType, ownerID, warehouseID, subjectID); err != nil {
		return err
	}
	records, err := readOwnerAssets(ctx, tx, ownerType, ownerID, warehouseID, limit, after,
		repository.now, false)
	if err != nil {
		return err
	}
	if err := consume(records); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ReadTaskBoardEntryAssetsForWorker keeps the worker's current task-board
// proof and read-audience row locked through the complete list callback. It must
// be used instead of AuthorizeTaskBoardEntryWorker followed by ReadOwnerAssets:
// a proof update between those transactions could otherwise expose a result
// asset after the worker has been removed or the entry has been revoked.
// ReadTaskBoardEntryAssetsForWorker returns a bounded task-board asset page
// only when the current owner proof permits the specified worker.
func (repository *Repository) ReadTaskBoardEntryAssetsForWorker(
	ctx context.Context,
	entryID, warehouseID, workerID uuid.UUID,
	limit int,
	after *uuid.UUID,
	consume func([]AssetWithVariants) error,
) error {
	if entryID == uuid.Nil || warehouseID == uuid.Nil || workerID == uuid.Nil || consume == nil {
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireTaskBoardEntryWorkerReadAccess(ctx, tx, entryID, warehouseID, workerID); err != nil {
		return err
	}
	records, err := readOwnerAssets(ctx, tx, OwnerTypeTaskBoardEntry, entryID.String(), warehouseID,
		limit, after, repository.now, false)
	if err != nil {
		return err
	}
	if err := consume(records); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ReadDriverShiftAssetsForWorker keeps the current shift proof and reader
// audience locked through the complete metadata callback.
func (repository *Repository) ReadDriverShiftAssetsForWorker(
	ctx context.Context,
	shiftID, warehouseID, workerID uuid.UUID,
	limit int,
	after *uuid.UUID,
	consume func([]AssetWithVariants) error,
) error {
	if shiftID == uuid.Nil || warehouseID == uuid.Nil || workerID == uuid.Nil || consume == nil {
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireDriverShiftWorkerReadAccess(ctx, tx, shiftID, warehouseID, workerID); err != nil {
		return err
	}
	records, err := readOwnerAssets(ctx, tx, OwnerTypeDriverShift, shiftID.String(), warehouseID,
		limit, after, repository.now, false)
	if err != nil {
		return err
	}
	if err := consume(records); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// readTaskBoardEntryAssetsForUser projects the acceptance scope of one
// task-board entry. Result assets belong directly to the entry; source assets
// are admitted only through the exact, immutable generation listed in the
// current task-board proof. The caller must hold
// RequireTaskBoardEntryUserReadAccess for the full callback transaction.
func readTaskBoardEntryAssetsForUser(
	ctx context.Context,
	tx pgx.Tx,
	entryID, warehouseID uuid.UUID,
	limit int,
	after *uuid.UUID,
) ([]AssetWithVariants, error) {
	if limit < 1 || limit > 100 {
		return nil, ErrConflict
	}
	var cursorKind int
	var cursorSort int64
	var cursorCreated time.Time
	var cursorID uuid.UUID
	if after != nil {
		err := tx.QueryRow(ctx, `with candidate_assets as (
			select a.media_id,a.media_kind,a.sort_order,a.created_at,a.current_generation as exposed_generation,
				false as source_reference
			from media_asset a
			where a.owner_type='TASK_BOARD_ENTRY' and a.owner_id=$1 and a.warehouse_id=$2
			  and a.deleted_at is null and media_asset_is_available(a.media_id)
			union all
			select a.media_id,a.media_kind,a.sort_order,a.created_at,source.generation as exposed_generation,
				true as source_reference
			from media_task_board_entry_source_media_reference source
			join media_asset a on a.media_id=source.media_id
			where source.entry_id=$3 and a.warehouse_id=$2
			  and a.deleted_at is null and media_asset_is_available(a.media_id)
		), deduplicated_assets as (
			select distinct on (candidate.media_id) candidate.*
			from candidate_assets candidate
			order by candidate.media_id,candidate.source_reference,candidate.exposed_generation
		)
		select case when media_kind='IMAGE' then 0 else 1 end,sort_order,created_at,media_id
		from deduplicated_assets where media_id=$4`, entryID.String(), warehouseID, entryID, *after).
			Scan(&cursorKind, &cursorSort, &cursorCreated, &cursorID)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrConflict
		}
		if err != nil {
			return nil, err
		}
	}

	rows, err := tx.Query(ctx, `/* media_task_board_user_acceptance_read */
		with candidate_assets as (
			select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
				a.original_file_name,a.original_content_type,a.source_object_key,
				coalesce(a.source_version_id,'') as source_version_id,
				coalesce(a.source_etag,'') as source_etag,
				coalesce(a.source_checksum_sha256,'') as source_checksum_sha256,
				a.processing_status,a.version,a.current_generation as exposed_generation,a.rotation_degrees,
				a.sort_order,a.size_bytes,a.created_at,false as source_reference,false as pinned_ready
			from media_asset a
			where a.owner_type='TASK_BOARD_ENTRY' and a.owner_id=$1 and a.warehouse_id=$2
			  and a.deleted_at is null and media_asset_is_available(a.media_id)
			union all
			select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
				a.original_file_name,a.original_content_type,a.source_object_key,
				coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
				a.processing_status,a.version,source.generation as exposed_generation,a.rotation_degrees,
				a.sort_order,a.size_bytes,a.created_at,true as source_reference,
				exists(select 1 from media_variant pinned
					where pinned.media_id=a.media_id and pinned.generation=source.generation) as pinned_ready
			from media_task_board_entry_source_media_reference source
			join media_asset a on a.media_id=source.media_id
			where source.entry_id=$3 and a.warehouse_id=$2
			  and a.deleted_at is null and media_asset_is_available(a.media_id)
		), deduplicated_assets as (
			select distinct on (candidate.media_id) candidate.*
			from candidate_assets candidate
			order by candidate.media_id,candidate.source_reference,candidate.exposed_generation
		), authorized_assets as materialized (
			select * from deduplicated_assets a
			where ($4::uuid is null or (
				case when a.media_kind='IMAGE' then 0 else 1 end,
				a.sort_order,a.created_at,a.media_id
			) > ($5::integer,$6::bigint,$7::timestamptz,$4::uuid))
			order by case when a.media_kind='IMAGE' then 0 else 1 end,a.sort_order,a.created_at,a.media_id
			limit $8
		)
		select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
			a.original_file_name,a.original_content_type,a.source_object_key,
			a.source_version_id,a.source_etag,a.source_checksum_sha256,
			a.processing_status,a.version,a.exposed_generation,a.rotation_degrees,
			a.sort_order,a.size_bytes,a.created_at,a.source_reference,a.pinned_ready,
			(variant.media_id is not null),coalesce(variant.variant,''),
			coalesce(variant.object_key,''),coalesce(variant.object_version_id,''),
			coalesce(variant.content_type,''),coalesce(variant.size_bytes,0),
			variant.width,variant.height,coalesce(variant.checksum_sha256,'')
		from authorized_assets a
		left join media_variant variant on variant.media_id=a.media_id
		 and variant.generation=a.exposed_generation and variant.variant<>'ORIGINAL'
		 and (a.source_reference or a.processing_status='READY')
		order by case when a.media_kind='IMAGE' then 0 else 1 end,a.sort_order,a.created_at,
			a.media_id,variant.variant`, entryID.String(), warehouseID, entryID, after,
		cursorKind, cursorSort, cursorCreated, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var records []AssetWithVariants
	byID := make(map[uuid.UUID]int)
	for rows.Next() {
		var asset AssetRecord
		var sourceReference bool
		var pinnedReady bool
		var hasVariant bool
		var variant VariantRecord
		var variantName string
		if err := rows.Scan(&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID,
			&asset.Kind, &asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
			&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum, &asset.Status,
			&asset.Version, &asset.Generation, &asset.Rotation, &asset.SortOrder,
			&asset.SizeBytes, &asset.CreatedAt, &sourceReference, &pinnedReady,
			&hasVariant, &variantName, &variant.ObjectKey, &variant.ObjectVersionID,
			&variant.ContentType, &variant.SizeBytes, &variant.Width, &variant.Height,
			&variant.Checksum); err != nil {
			return nil, err
		}
		if sourceReference {
			if pinnedReady {
				asset.Status = media.StatusReady
			} else {
				asset.Status = media.StatusProcessing
			}
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
	return records, nil
}

func readOwnerAssets(
	ctx context.Context,
	tx pgx.Tx,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	limit int,
	after *uuid.UUID,
	now func() time.Time,
	requireActiveBinding bool,
) ([]AssetWithVariants, error) {
	if limit < 1 || limit > 100 {
		return nil, ErrConflict
	}
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
			return nil, ErrConflict
		}
		if err != nil {
			return nil, err
		}
	}
	rows, err := tx.Query(ctx, `/* media_public_owner_read */
		with authorized_assets as materialized (
		select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
			a.original_file_name,a.original_content_type,a.source_object_key,
			coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
			a.processing_status,a.version,a.current_generation,a.rotation_degrees,
			a.sort_order,a.size_bytes,a.created_at
		from media_asset a
		join media_owner_binding binding
		  on binding.owner_type=a.owner_type and binding.owner_id=a.owner_id
		 and binding.warehouse_id=a.warehouse_id and (not $9::boolean or binding.active)
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
		cursorKind, cursorSort, cursorCreated, limit, requireActiveBinding)
	if err != nil {
		return nil, err
	}
	var records []AssetWithVariants
	byID := make(map[uuid.UUID]int)
	for rows.Next() {
		var asset AssetRecord
		var hasVariant bool
		var variant VariantRecord
		var variantName string
		if err := rows.Scan(&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID,
			&asset.Kind, &asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
			&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum, &asset.Status,
			&asset.Version, &asset.Generation, &asset.Rotation, &asset.SortOrder,
			&asset.SizeBytes, &asset.CreatedAt, &hasVariant, &variantName, &variant.ObjectKey,
			&variant.ObjectVersionID, &variant.ContentType, &variant.SizeBytes, &variant.Width,
			&variant.Height, &variant.Checksum); err != nil {
			rows.Close()
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
		rows.Close()
		return nil, err
	}
	rows.Close()
	if len(records) == 0 && requireActiveBinding {
		if err := requireOwnerBinding(ctx, tx, ownerType, ownerID, warehouseID,
			now()); err != nil {
			return nil, err
		}
	}
	return records, nil
}

// ReadCabinCovers returns one warehouse-scoped cover projection per requested
// cabin. The count covers retained logical images only in the canonical active
// gallery folder, never derived variants or older archive folders. Previews
// contain at most one exact SMALL variant per READY active-folder image, put
// the explicit canonical cover first, and are bounded to 100 images. Cabin
// bindings are share-locked for the complete projection callback so a
// concurrent owner revocation cannot race the read. The complete retained
// folder archive remains available through the CABIN asset list.
func (repository *Repository) ReadCabinCovers(
	ctx context.Context,
	warehouseID uuid.UUID,
	cabinIDs []uuid.UUID,
	consume func([]CabinCoverRecord) error,
) error {
	return repository.readCabinCovers(ctx, warehouseID, cabinIDs, consume)
}

func (repository *Repository) readCabinCovers(
	ctx context.Context,
	warehouseID uuid.UUID,
	cabinIDs []uuid.UUID,
	consume func([]CabinCoverRecord) error,
) error {
	if len(cabinIDs) < 1 || len(cabinIDs) > 200 || consume == nil {
		return ErrConflict
	}
	requested := make([]string, len(cabinIDs))
	for index, cabinID := range cabinIDs {
		if cabinID == uuid.Nil {
			return ErrConflict
		}
		requested[index] = cabinID.String()
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	rows, err := tx.Query(ctx, `/* media_public_cabin_cover_owner_proof */
		select binding.owner_id
		from media_owner_binding binding
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where binding.owner_type='CABIN' and binding.warehouse_id=$1
		  and binding.owner_id=any($2::text[]) and binding.active
		  and not exists (select 1 from media_quarantined_aggregate quarantine
			where quarantine.consumer_name=binding.proof_consumer_name
			  and quarantine.aggregate_type=binding.proof_aggregate_type
			  and quarantine.aggregate_id=binding.proof_aggregate_id
			  and quarantine.reconciled_at is null)
		order by binding.owner_id
		for share of binding`, warehouseID, requested)
	if err != nil {
		return err
	}
	authorized := make([]string, 0, len(cabinIDs))
	for rows.Next() {
		var ownerID string
		if err := rows.Scan(&ownerID); err != nil {
			rows.Close()
			return err
		}
		authorized = append(authorized, ownerID)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	if len(authorized) == 0 {
		if err := consume([]CabinCoverRecord{}); err != nil {
			return err
		}
		return tx.Commit(ctx)
	}

	rows, err = tx.Query(ctx, `/* media_public_cabin_covers */
		with image_assets as materialized (
			select asset.media_id,photo.cabin_id::text as owner_id,
				asset.processing_status,asset.current_generation,
				photo.sort_order,photo.attached_at as created_at,
				(asset.media_id=library.cover_media_id) as is_cover
			from media_cabin_photo photo
			join media_cabin_photo_library library
			  on library.cabin_id=photo.cabin_id
			join media_asset asset on asset.media_id=photo.media_id
			where photo.warehouse_id=$1 and photo.cabin_id::text=any($2::text[])
			  and photo.gallery_folder_id=library.active_gallery_folder_id
			  and asset.media_kind='IMAGE' and asset.deleted_at is null
			  and media_asset_is_available(asset.media_id)
		), counts as (
			select owner_id,count(*)::bigint as photo_count
			from image_assets group by owner_id
		), previews as (
			select a.owner_id,a.media_id,a.current_generation,v.variant,
				v.object_version_id,v.content_type,v.size_bytes,v.width,v.height,
				v.checksum_sha256,a.is_cover,row_number() over (
					partition by a.owner_id
					order by a.is_cover desc,a.sort_order,a.created_at,a.media_id
				) as preview_rank
			from image_assets a
			join media_variant v on v.media_id=a.media_id
			 and v.generation=a.current_generation and v.variant='SMALL'
			where a.processing_status='READY' and a.current_generation>0
			  and v.object_version_id<>''
		)
		select counts.owner_id,counts.photo_count,preview.media_id,preview.current_generation,
			preview.variant,preview.object_version_id,preview.content_type,preview.size_bytes,
			preview.width,preview.height,preview.checksum_sha256,preview.is_cover
		from counts
		left join previews preview on preview.owner_id=counts.owner_id
		 and preview.preview_rank<=100
		order by counts.owner_id,preview.preview_rank nulls last`, warehouseID, authorized)
	if err != nil {
		return err
	}
	records := make([]CabinCoverRecord, 0, len(authorized))
	byOwner := make(map[string]int, len(authorized))
	for rows.Next() {
		var ownerID string
		var photoCount int64
		var mediaID *uuid.UUID
		var generation *int
		var variantName, objectVersionID, contentType, checksum *string
		var sizeBytes *int64
		var width, height *int
		var isCover *bool
		if err := rows.Scan(&ownerID, &photoCount, &mediaID, &generation, &variantName,
			&objectVersionID, &contentType, &sizeBytes, &width, &height, &checksum,
			&isCover); err != nil {
			rows.Close()
			return err
		}
		index, exists := byOwner[ownerID]
		if !exists {
			index = len(records)
			byOwner[ownerID] = index
			records = append(records, CabinCoverRecord{
				CabinID: uuid.MustParse(ownerID), PhotoCount: photoCount,
				Previews: make([]CabinPreviewRecord, 0),
			})
		}
		if mediaID != nil && generation != nil && variantName != nil && objectVersionID != nil &&
			contentType != nil && sizeBytes != nil && checksum != nil {
			variant := VariantRecord{
				Variant:         media.Variant(*variantName),
				ObjectVersionID: *objectVersionID,
				ContentType:     *contentType,
				SizeBytes:       *sizeBytes,
				Width:           width,
				Height:          height,
				Checksum:        *checksum,
			}
			records[index].Previews = append(records[index].Previews, CabinPreviewRecord{
				MediaID: *mediaID, Generation: *generation, Variant: variant,
			})
			if isCover != nil && *isCover {
				records[index].MediaID = *mediaID
				records[index].Generation = *generation
				records[index].Variant = &records[index].Previews[len(records[index].Previews)-1].Variant
			}
		}
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	if err := consume(records); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// GetAsset reads one logical asset by ID without authorizing a public scope.
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

// GetAssetScoped reads an asset only when its owner tuple exactly matches.
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

// ReadOriginal fetches asset metadata and the exact current-generation
// original with one authorization-bearing SQL statement. TASK_BOARD_ENTRY
// source reads additionally require the proof-pinned generation supplied by
// the public handler.
// ReadOriginal authorizes and provides one immutable original to consume while
// holding the owner binding share lock.
func (repository *Repository) ReadOriginal(
	ctx context.Context,
	mediaID uuid.UUID,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	generation *int,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if consume == nil {
		return ErrConflict
	}
	if ownerType == OwnerTypeLogisticsCustomerProfile {
		return ErrOwnerProofMissing
	}
	if ownerType == OwnerTypeTaskBoardEntry {
		entryID, err := uuid.Parse(ownerID)
		if err != nil || entryID == uuid.Nil || (generation != nil && *generation <= 0) {
			return ErrConflict
		}
		return repository.readTaskBoardEntryOriginalForUser(ctx, entryID, warehouseID, mediaID, generation, consume)
	}
	return repository.readOriginal(ctx, mediaID, ownerType, ownerID, warehouseID, generation, nil, consume)
}

// ReadOriginalForCustomer returns one customer original only while the current
// owner proof names the exact CustomerApp subject for the full callback.
func (repository *Repository) ReadOriginalForCustomer(
	ctx context.Context,
	mediaID uuid.UUID,
	ownerType, ownerID string,
	warehouseID, subjectID uuid.UUID,
	generation *int,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if !IsCustomerSubjectBoundOwnerType(ownerType) || subjectID == uuid.Nil || consume == nil {
		return ErrOwnerProofMissing
	}
	return repository.readOriginal(ctx, mediaID, ownerType, ownerID, warehouseID, generation, &subjectID, consume)
}

func (repository *Repository) readOriginal(
	ctx context.Context,
	mediaID uuid.UUID,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	generation *int,
	authorizedSubjectID *uuid.UUID,
	consume func(AssetRecord, *VariantRecord) error,
) error {
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
		select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
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
		 and ($5::uuid is null or binding.authorized_subject_id=$5)
		 and not exists (select 1 from media_quarantined_aggregate quarantine
			where quarantine.consumer_name=binding.proof_consumer_name
			  and quarantine.aggregate_type=binding.proof_aggregate_type
			  and quarantine.aggregate_id=binding.proof_aggregate_id
			  and quarantine.reconciled_at is null)
		for share of binding`, mediaID, ownerType, ownerID, warehouseID,
		nullableUUIDPointer(authorizedSubjectID)).Scan(
		&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
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

// readTaskBoardEntryOriginalForUser admits a warehouse reader to one direct
// result asset or one exact source generation retained by the entry proof. A
// source never falls back to its own current generation.
func (repository *Repository) readTaskBoardEntryOriginalForUser(
	ctx context.Context,
	entryID, warehouseID, mediaID uuid.UUID,
	generation *int,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireTaskBoardEntryUserReadAccess(ctx, tx, entryID, warehouseID); err != nil {
		return err
	}
	asset, original, found, err := readTaskBoardResultOriginal(ctx, tx, entryID, warehouseID, mediaID, generation)
	if err != nil {
		return err
	}
	if !found && generation != nil {
		asset, original, found, err = readTaskBoardSourceOriginal(ctx, tx, entryID, warehouseID, mediaID, *generation)
		if err != nil {
			return err
		}
	}
	if !found {
		return ErrNotFound
	}
	if err := consume(asset, original); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ReadCurrentVariant selects one exact derived variant only when the requested
// generation is still current, except for a TASK_BOARD_ENTRY source generation
// pinned by its authoritative proof. Owner mismatch, revoked proof and stale
// generation remain indistinguishable from an absent media resource.
// ReadCurrentVariant authorizes and provides one requested current derivative
// while holding the owner binding share lock.
func (repository *Repository) ReadCurrentVariant(
	ctx context.Context,
	mediaID uuid.UUID,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	generation int,
	requestedVariant media.Variant,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if consume == nil || generation <= 0 {
		return ErrConflict
	}
	if ownerType == OwnerTypeLogisticsCustomerProfile {
		return ErrOwnerProofMissing
	}
	switch requestedVariant {
	case media.VariantSmall, media.VariantMedium, media.VariantLarge:
	default:
		return ErrConflict
	}
	if ownerType == OwnerTypeCabin {
		cabinID, err := uuid.Parse(ownerID)
		if err != nil || cabinID == uuid.Nil {
			return ErrConflict
		}
		return repository.ReadCabinPhotoVariant(ctx, cabinID, warehouseID, mediaID,
			generation, requestedVariant, consume)
	}
	if ownerType == OwnerTypeTaskBoardEntry {
		entryID, err := uuid.Parse(ownerID)
		if err != nil || entryID == uuid.Nil {
			return ErrConflict
		}
		return repository.readTaskBoardEntryVariantForUser(ctx, entryID, warehouseID, mediaID,
			generation, requestedVariant, consume)
	}
	return repository.readCurrentVariant(ctx, mediaID, ownerType, ownerID, warehouseID,
		generation, requestedVariant, nil, consume)
}

// ReadCurrentVariantForCustomer returns one customer derivative only while
// the current owner proof names the exact CustomerApp subject for the callback.
func (repository *Repository) ReadCurrentVariantForCustomer(
	ctx context.Context,
	mediaID uuid.UUID,
	ownerType, ownerID string,
	warehouseID, subjectID uuid.UUID,
	generation int,
	requestedVariant media.Variant,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if !IsCustomerSubjectBoundOwnerType(ownerType) || subjectID == uuid.Nil || consume == nil || generation <= 0 {
		return ErrOwnerProofMissing
	}
	switch requestedVariant {
	case media.VariantSmall, media.VariantMedium, media.VariantLarge:
	default:
		return ErrOwnerProofMissing
	}
	return repository.readCurrentVariant(ctx, mediaID, ownerType, ownerID, warehouseID,
		generation, requestedVariant, &subjectID, consume)
}

func (repository *Repository) readCurrentVariant(
	ctx context.Context,
	mediaID uuid.UUID,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	generation int,
	requestedVariant media.Variant,
	authorizedSubjectID *uuid.UUID,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var asset AssetRecord
	var hasVariant bool
	var variant VariantRecord
	var variantName string
	err = tx.QueryRow(ctx, `/* media_public_current_variant_read */
		select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
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
		 and variant.generation=$5 and variant.variant=$6
		where a.media_id=$1 and a.owner_type=$2 and a.owner_id=$3 and a.warehouse_id=$4
		 and a.current_generation=$5 and a.deleted_at is null and media_asset_is_available(a.media_id)
		 and ($7::uuid is null or binding.authorized_subject_id=$7)
		 and not exists (select 1 from media_quarantined_aggregate quarantine
			where quarantine.consumer_name=binding.proof_consumer_name
			  and quarantine.aggregate_type=binding.proof_aggregate_type
			  and quarantine.aggregate_id=binding.proof_aggregate_id
			  and quarantine.reconciled_at is null)
		for share of binding`, mediaID, ownerType, ownerID, warehouseID, generation,
		requestedVariant, nullableUUIDPointer(authorizedSubjectID)).Scan(
		&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
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
	var selected *VariantRecord
	if hasVariant {
		variant.Variant = media.Variant(variantName)
		selected = &variant
	}
	if err := consume(asset, selected); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// readTaskBoardEntryVariantForUser keeps the completed entry proof locked
// while it selects either a current result variant or the exact historical
// source generation cited by that proof.
func (repository *Repository) readTaskBoardEntryVariantForUser(
	ctx context.Context,
	entryID, warehouseID, mediaID uuid.UUID,
	generation int,
	requestedVariant media.Variant,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireTaskBoardEntryUserReadAccess(ctx, tx, entryID, warehouseID); err != nil {
		return err
	}
	asset, variant, found, err := readTaskBoardResultVariant(ctx, tx, entryID, warehouseID, mediaID,
		generation, requestedVariant)
	if err != nil {
		return err
	}
	if !found {
		asset, variant, found, err = readTaskBoardSourceVariant(ctx, tx, entryID, warehouseID, mediaID,
			generation, requestedVariant)
		if err != nil {
			return err
		}
	}
	if !found {
		return ErrNotFound
	}
	if err := consume(asset, variant); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ReadTaskBoardEntryOriginalForWorker admits a worker either to a result asset
// owned directly by the entry or to one exact source media generation named in
// the current entry proof. The latter intentionally does not grant a general
// read capability for the source asset's own owner scope.
// ReadTaskBoardEntryOriginalForWorker provides a task-board original only when
// the current proof explicitly authorizes the worker and requested generation.
func (repository *Repository) ReadTaskBoardEntryOriginalForWorker(
	ctx context.Context,
	entryID, warehouseID, workerID, mediaID uuid.UUID,
	generation *int,
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
	if err := RequireTaskBoardEntryWorkerReadAccess(ctx, tx, entryID, warehouseID, workerID); err != nil {
		return err
	}
	asset, original, found, err := readTaskBoardResultOriginal(ctx, tx, entryID, warehouseID, mediaID, generation)
	if err != nil {
		return err
	}
	if !found && generation != nil {
		asset, original, found, err = readTaskBoardSourceOriginal(ctx, tx, entryID, warehouseID, mediaID, *generation)
		if err != nil {
			return err
		}
	}
	if !found {
		return ErrNotFound
	}
	if err := consume(asset, original); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ReadDriverShiftOriginalForWorker provides a direct shift-owned original only
// while the active proof names the authenticated driver as a reader.
func (repository *Repository) ReadDriverShiftOriginalForWorker(
	ctx context.Context,
	shiftID, warehouseID, workerID, mediaID uuid.UUID,
	generation *int,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if consume == nil || (generation != nil && *generation <= 0) {
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireDriverShiftWorkerReadAccess(ctx, tx, shiftID, warehouseID, workerID); err != nil {
		return err
	}
	asset, original, found, err := readWorkerEvidenceOriginal(ctx, tx, OwnerTypeDriverShift,
		shiftID, warehouseID, mediaID, generation)
	if err != nil {
		return err
	}
	if !found {
		return ErrNotFound
	}
	if err := consume(asset, original); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ReadTaskBoardEntryVariantForWorker provides a task-board derivative only
// when the current proof explicitly authorizes the worker and generation.
func (repository *Repository) ReadTaskBoardEntryVariantForWorker(
	ctx context.Context,
	entryID, warehouseID, workerID, mediaID uuid.UUID,
	generation int,
	requestedVariant media.Variant,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if consume == nil || generation <= 0 {
		return ErrConflict
	}
	switch requestedVariant {
	case media.VariantSmall, media.VariantMedium, media.VariantLarge:
	default:
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireTaskBoardEntryWorkerReadAccess(ctx, tx, entryID, warehouseID, workerID); err != nil {
		return err
	}
	asset, variant, found, err := readTaskBoardResultVariant(ctx, tx, entryID, warehouseID, mediaID, generation, requestedVariant)
	if err != nil {
		return err
	}
	if !found {
		asset, variant, found, err = readTaskBoardSourceVariant(ctx, tx, entryID, warehouseID, mediaID, generation, requestedVariant)
		if err != nil {
			return err
		}
	}
	if !found {
		return ErrNotFound
	}
	if err := consume(asset, variant); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ReadDriverShiftVariantForWorker provides a current shift-owned derivative
// only while the active proof names the authenticated driver as a reader.
func (repository *Repository) ReadDriverShiftVariantForWorker(
	ctx context.Context,
	shiftID, warehouseID, workerID, mediaID uuid.UUID,
	generation int,
	requestedVariant media.Variant,
	consume func(AssetRecord, *VariantRecord) error,
) error {
	if consume == nil || generation <= 0 {
		return ErrConflict
	}
	switch requestedVariant {
	case media.VariantSmall, media.VariantMedium, media.VariantLarge:
	default:
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireDriverShiftWorkerReadAccess(ctx, tx, shiftID, warehouseID, workerID); err != nil {
		return err
	}
	asset, variant, found, err := readWorkerEvidenceVariant(ctx, tx, OwnerTypeDriverShift,
		shiftID, warehouseID, mediaID, generation, requestedVariant)
	if err != nil {
		return err
	}
	if !found {
		return ErrNotFound
	}
	if err := consume(asset, variant); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func readWorkerEvidenceOriginal(ctx context.Context, tx pgx.Tx, ownerType string, ownerID, warehouseID, mediaID uuid.UUID, generation *int) (AssetRecord, *VariantRecord, bool, error) {
	var asset AssetRecord
	var hasVariant bool
	var variant VariantRecord
	var variantName string
	query := `select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
		a.original_file_name,a.original_content_type,a.source_object_key,
		coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
		a.processing_status,a.version,a.current_generation,a.rotation_degrees,a.sort_order,a.size_bytes,a.created_at,
		(variant.media_id is not null),coalesce(variant.variant,''),coalesce(variant.object_key,''),
		coalesce(variant.object_version_id,''),coalesce(variant.content_type,''),coalesce(variant.size_bytes,0),
		variant.width,variant.height,coalesce(variant.checksum_sha256,'')
		from media_asset a left join media_variant variant on variant.media_id=a.media_id
		and variant.generation=a.current_generation and variant.variant='ORIGINAL'
		where a.media_id=$1 and a.owner_type=$2 and a.owner_id=$3 and a.warehouse_id=$4
		and a.deleted_at is null and media_asset_is_available(a.media_id)`
	args := []any{mediaID, ownerType, ownerID.String(), warehouseID}
	if generation != nil {
		query += ` and a.current_generation=$5`
		args = append(args, *generation)
	}
	err := tx.QueryRow(ctx, query, args...).Scan(&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID,
		&asset.WarehouseID, &asset.Kind, &asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
		&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum, &asset.Status, &asset.Version,
		&asset.Generation, &asset.Rotation, &asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt, &hasVariant,
		&variantName, &variant.ObjectKey, &variant.ObjectVersionID, &variant.ContentType, &variant.SizeBytes,
		&variant.Width, &variant.Height, &variant.Checksum)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, nil, false, nil
	}
	if err != nil {
		return AssetRecord{}, nil, false, err
	}
	if hasVariant {
		variant.Variant = media.Variant(variantName)
		return asset, &variant, true, nil
	}
	return asset, nil, true, nil
}

func readWorkerEvidenceVariant(ctx context.Context, tx pgx.Tx, ownerType string, ownerID, warehouseID, mediaID uuid.UUID, generation int, requestedVariant media.Variant) (AssetRecord, *VariantRecord, bool, error) {
	return readTaskBoardWorkerVariant(ctx, tx, `from media_asset a
		left join media_variant variant on variant.media_id=a.media_id and variant.generation=$4 and variant.variant=$5
		where a.media_id=$1 and a.owner_type=$6 and a.owner_id=$2 and a.warehouse_id=$3
		and a.current_generation=$4 and a.deleted_at is null and media_asset_is_available(a.media_id)`,
		mediaID, ownerID.String(), warehouseID, generation, requestedVariant, ownerType)
}

func readTaskBoardResultOriginal(ctx context.Context, tx pgx.Tx, entryID, warehouseID, mediaID uuid.UUID, generation *int) (AssetRecord, *VariantRecord, bool, error) {
	var asset AssetRecord
	var hasVariant bool
	var variant VariantRecord
	var variantName string
	query := `select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
		a.original_file_name,a.original_content_type,a.source_object_key,
		coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
		a.processing_status,a.version,a.current_generation,a.rotation_degrees,a.sort_order,a.size_bytes,a.created_at,
		(variant.media_id is not null),coalesce(variant.variant,''),coalesce(variant.object_key,''),
		coalesce(variant.object_version_id,''),coalesce(variant.content_type,''),coalesce(variant.size_bytes,0),
		variant.width,variant.height,coalesce(variant.checksum_sha256,'')
		from media_asset a left join media_variant variant on variant.media_id=a.media_id
		and variant.generation=a.current_generation and variant.variant='ORIGINAL'
		where a.media_id=$1 and a.owner_type='TASK_BOARD_ENTRY' and a.owner_id=$2 and a.warehouse_id=$3
		and a.deleted_at is null and media_asset_is_available(a.media_id)`
	args := []any{mediaID, entryID.String(), warehouseID}
	if generation != nil {
		query += ` and a.current_generation=$4`
		args = append(args, *generation)
	}
	err := tx.QueryRow(ctx, query, args...).Scan(&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID,
		&asset.WarehouseID, &asset.Kind, &asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
		&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum, &asset.Status, &asset.Version,
		&asset.Generation, &asset.Rotation, &asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt, &hasVariant,
		&variantName, &variant.ObjectKey, &variant.ObjectVersionID, &variant.ContentType, &variant.SizeBytes,
		&variant.Width, &variant.Height, &variant.Checksum)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, nil, false, nil
	}
	if err != nil {
		return AssetRecord{}, nil, false, err
	}
	if hasVariant {
		variant.Variant = media.Variant(variantName)
		return asset, &variant, true, nil
	}
	return asset, nil, true, nil
}

func readTaskBoardSourceOriginal(ctx context.Context, tx pgx.Tx, entryID, warehouseID, mediaID uuid.UUID, generation int) (AssetRecord, *VariantRecord, bool, error) {
	asset, variant, found, err := readTaskBoardSourceVariant(ctx, tx, entryID, warehouseID, mediaID, generation, media.VariantOriginal)
	return asset, variant, found, err
}

func readTaskBoardResultVariant(ctx context.Context, tx pgx.Tx, entryID, warehouseID, mediaID uuid.UUID, generation int, requestedVariant media.Variant) (AssetRecord, *VariantRecord, bool, error) {
	return readTaskBoardWorkerVariant(ctx, tx, `from media_asset a
		left join media_variant variant on variant.media_id=a.media_id and variant.generation=$4 and variant.variant=$5
		where a.media_id=$1 and a.owner_type='TASK_BOARD_ENTRY' and a.owner_id=$2 and a.warehouse_id=$3
		and a.current_generation=$4 and a.deleted_at is null and media_asset_is_available(a.media_id)`,
		mediaID, entryID.String(), warehouseID, generation, requestedVariant)
}

func readTaskBoardSourceVariant(ctx context.Context, tx pgx.Tx, entryID, warehouseID, mediaID uuid.UUID, generation int, requestedVariant media.Variant) (AssetRecord, *VariantRecord, bool, error) {
	asset, variant, found, err := readTaskBoardWorkerVariant(ctx, tx, `from media_task_board_entry_source_media_reference source
		join media_asset a on a.media_id=source.media_id
		left join media_variant variant on variant.media_id=a.media_id and variant.generation=source.generation and variant.variant=$5
		where source.entry_id=$1 and source.media_id=$2 and source.generation=$4 and a.warehouse_id=$3
		and a.deleted_at is null and media_asset_is_available(a.media_id)`,
		entryID, mediaID, warehouseID, generation, requestedVariant)
	if found {
		// A source reference pins an immutable historical generation. Surface
		// that exact generation to the worker-only callback rather than the
		// source asset's possibly newer current generation. The immutable
		// referenced variant can remain ready while a newer processing attempt is
		// pending, so status follows the pinned variant too.
		asset.Generation = generation
		if variant != nil {
			asset.Status = media.StatusReady
		}
	}
	return asset, variant, found, err
}

func readTaskBoardWorkerVariant(ctx context.Context, tx pgx.Tx, fromAndWhere string, arguments ...any) (AssetRecord, *VariantRecord, bool, error) {
	var asset AssetRecord
	var hasVariant bool
	var variant VariantRecord
	var variantName string
	query := `select a.media_id,a.folder_id,a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
		a.original_file_name,a.original_content_type,a.source_object_key,
		coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
		a.processing_status,a.version,a.current_generation,a.rotation_degrees,a.sort_order,a.size_bytes,a.created_at,
		(variant.media_id is not null),coalesce(variant.variant,''),coalesce(variant.object_key,''),
		coalesce(variant.object_version_id,''),coalesce(variant.content_type,''),coalesce(variant.size_bytes,0),
		variant.width,variant.height,coalesce(variant.checksum_sha256,'') ` + fromAndWhere
	err := tx.QueryRow(ctx, query, arguments...).Scan(
		&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
		&asset.FileName, &asset.ContentType, &asset.SourceObjectKey, &asset.SourceVersionID, &asset.SourceETag,
		&asset.SourceChecksum, &asset.Status, &asset.Version, &asset.Generation, &asset.Rotation, &asset.SortOrder,
		&asset.SizeBytes, &asset.CreatedAt, &hasVariant, &variantName, &variant.ObjectKey, &variant.ObjectVersionID,
		&variant.ContentType, &variant.SizeBytes, &variant.Width, &variant.Height, &variant.Checksum)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, nil, false, nil
	}
	if err != nil {
		return AssetRecord{}, nil, false, err
	}
	if hasVariant {
		variant.Variant = media.Variant(variantName)
		return asset, &variant, true, nil
	}
	return asset, nil, true, nil
}

// Variants returns persisted variants for one exact media generation, optionally
// including the canonical original.
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

const assetSQL = `select a.media_id,a.folder_id,a.client_reference_id,a.created_by_principal_type,a.created_by_actor_id,
	a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
	a.original_file_name,a.original_content_type,a.source_object_key,
	coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
	a.processing_status,a.version,a.current_generation,a.rotation_degrees,
	a.sort_order,a.size_bytes,a.created_at from media_asset a`

const assetWithSessionSQL = `select a.media_id,a.folder_id,a.client_reference_id,a.created_by_principal_type,a.created_by_actor_id,
	a.owner_type,a.owner_id,a.warehouse_id,a.media_kind,
	a.original_file_name,a.original_content_type,a.source_object_key,
	coalesce(a.source_version_id,''),coalesce(a.source_etag,''),coalesce(a.source_checksum_sha256,''),
	a.processing_status,a.version,a.current_generation,a.rotation_degrees,
	a.sort_order,a.size_bytes,a.created_at,s.upload_session_id,s.expires_at,
		s.expected_content_length,coalesce(s.expected_checksum_sha256,''),s.upload_mode,s.completed_at
	from media_asset a join media_upload_session s on s.media_id=a.media_id`

type rowScanner interface {
	Scan(...any) error
}

func scanAsset(row rowScanner) (AssetRecord, error) {
	var asset AssetRecord
	var actorType *string
	var actorID *uuid.UUID
	err := row.Scan(&asset.ID, &asset.FolderID, &asset.ClientReferenceID, &actorType, &actorID,
		&asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
		&asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
		&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum,
		&asset.Status, &asset.Version, &asset.Generation, &asset.Rotation,
		&asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt)
	if err == nil && actorType != nil && actorID != nil {
		asset.CreatedBy = &ActorReference{SubjectID: *actorID, PrincipalType: *actorType}
	}
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
	var actorType *string
	var actorID *uuid.UUID
	err := row.Scan(&asset.ID, &asset.FolderID, &asset.ClientReferenceID, &actorType, &actorID,
		&asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
		&asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
		&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum,
		&asset.Status, &asset.Version, &asset.Generation, &asset.Rotation,
		&asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt, &asset.UploadSessionID,
		&asset.UploadExpiresAt, &asset.ExpectedLength, &asset.ExpectedChecksum,
		&asset.UploadMode, &asset.UploadCompletedAt)
	if err == nil && actorType != nil && actorID != nil {
		asset.CreatedBy = &ActorReference{SubjectID: *actorID, PrincipalType: *actorType}
	}
	return asset, err
}

type queryer interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}

func requireOwnerBinding(ctx context.Context, database queryer, ownerType, ownerID string, warehouseID uuid.UUID, now time.Time) error {
	if !IsPublicOwnerType(ownerType) || ownerType == OwnerTypeLogisticsCustomerProfile || ownerID == "" {
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

func requireCustomerOwnerBindingLock(
	ctx context.Context,
	tx pgx.Tx,
	ownerType, ownerID string,
	warehouseID, subjectID uuid.UUID,
) error {
	if !IsCustomerSubjectBoundOwnerType(ownerType) || subjectID == uuid.Nil {
		return ErrOwnerProofMissing
	}
	var lockedSubject uuid.UUID
	err := tx.QueryRow(ctx, `select binding.authorized_subject_id
		from media_owner_binding binding
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where binding.owner_type=$1 and binding.owner_id=$2 and binding.warehouse_id=$3
		  and binding.active and binding.authorized_subject_id=$4
		  and not exists (select 1 from media_quarantined_aggregate quarantine
			where quarantine.consumer_name=binding.proof_consumer_name
			  and quarantine.aggregate_type=binding.proof_aggregate_type
			  and quarantine.aggregate_id=binding.proof_aggregate_id
			  and quarantine.reconciled_at is null)
		for share of binding`, ownerType, ownerID, warehouseID, subjectID).Scan(&lockedSubject)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrOwnerProofMissing
	}
	return err
}

func requireUploadOwnerAccess(
	ctx context.Context,
	tx pgx.Tx,
	ownerType, ownerID string,
	warehouseID uuid.UUID,
	authorizedSubjectID *uuid.UUID,
	now time.Time,
) error {
	if authorizedSubjectID != nil {
		return requireCustomerOwnerBindingLock(ctx, tx, ownerType, ownerID, warehouseID,
			*authorizedSubjectID)
	}
	return requireOwnerBinding(ctx, tx, ownerType, ownerID, warehouseID, now)
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
	return lockActorCommand(ctx, tx, PrincipalTypeUser, subjectID, commandType, idempotencyKey)
}

func lockActorCommand(ctx context.Context, tx pgx.Tx, principalType string, subjectID uuid.UUID, commandType string, idempotencyKey uuid.UUID) error {
	if subjectID == uuid.Nil || (principalType != PrincipalTypeUser && principalType != PrincipalTypeWorker) {
		return ErrConflict
	}
	_, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		principalType+":"+subjectID.String()+":"+commandType+":"+idempotencyKey.String())
	return err
}

// lockUploadSessionContentTransaction uses the same lock identity as
// AcquireUploadSessionContentLock. It lets recovery wait for an in-flight
// content request before replacing an expired session, without holding a row
// lock that the finalization path also needs.
func lockUploadSessionContentTransaction(ctx context.Context, tx pgx.Tx, sessionID uuid.UUID) error {
	if sessionID == uuid.Nil {
		return ErrConflict
	}
	_, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		"media-upload-session-content:"+sessionID.String())
	return err
}

func appendInitialEventForActor(ctx context.Context, tx pgx.Tx, aggregateID uuid.UUID, eventType string, actor ActorReference, correlationID uuid.UUID, recordedAt time.Time) error {
	return appendInitialEventWithIDForActor(ctx, tx, uuid.New(), aggregateID, eventType, &actor, correlationID, recordedAt)
}

// appendInitialEventWithIDForActor supports trusted server-side ingestion
// paths which have no user/worker actor (for example, a service-owned remote
// import). The event still enters the normal immutable stream and snapshot;
// it simply records a nil actorRef rather than inventing a user identity.
func appendInitialEventWithIDForActor(ctx context.Context, tx pgx.Tx, eventID, aggregateID uuid.UUID, eventType string, actor *ActorReference, correlationID uuid.UUID, recordedAt time.Time) error {
	_, err := tx.Exec(ctx, `insert into media_event_stream_head (aggregate_type,aggregate_id,stream_version,updated_at)
		values ('MEDIA',$1,1,$2)`, aggregateID, recordedAt)
	if err != nil {
		return translateConstraint(err)
	}
	return insertDomainEventForActor(ctx, tx, eventID, aggregateID, 1, eventType, actor, correlationID, recordedAt)
}

func appendEventWithID(ctx context.Context, tx pgx.Tx, eventID, aggregateID uuid.UUID, eventType string, subjectID, correlationID uuid.UUID, recordedAt time.Time) error {
	return appendEventWithIDForActor(ctx, tx, eventID, aggregateID, eventType,
		ActorReference{SubjectID: subjectID, PrincipalType: PrincipalTypeUser}, correlationID, recordedAt)
}

func appendEventWithIDForActor(ctx context.Context, tx pgx.Tx, eventID, aggregateID uuid.UUID, eventType string, actor ActorReference, correlationID uuid.UUID, recordedAt time.Time) error {
	version, err := advanceDomainStream(ctx, tx, aggregateID, recordedAt)
	if err != nil {
		return err
	}
	return insertDomainEventForActor(ctx, tx, eventID, aggregateID, version, eventType, &actor, correlationID, recordedAt)
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

func insertDomainEventForActor(ctx context.Context, tx pgx.Tx, eventID, aggregateID uuid.UUID, version int64, eventType string, actorReference *ActorReference, correlationID uuid.UUID, recordedAt time.Time) error {
	body, checksum, err := fullAggregateState(ctx, tx, aggregateID)
	if err != nil {
		return err
	}
	var actor any
	if actorReference != nil {
		actorBody, _, actorErr := canonicalJSON(map[string]any{"subjectId": actorReference.SubjectID, "principalType": actorReference.PrincipalType, "profileRevision": nil})
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
	payload := map[string]any{
		"mediaId": asset.ID, "folderId": asset.FolderID, "ownerType": asset.OwnerType, "ownerId": asset.OwnerID,
		"warehouseId": asset.WarehouseID, "kind": asset.Kind, "status": status,
		"generation": generation, "rotationDegrees": rotation,
	}
	// The v1 media fact is consumed by services with an exact legacy payload
	// allowlist. Preserve its byte shape for all existing owners; the stable
	// evidence reference belongs only to worker-evidence owner scopes.
	if IsWorkerEvidenceOwnerType(asset.OwnerType) && asset.ClientReferenceID != nil {
		payload["clientReferenceId"] = *asset.ClientReferenceID
	}
	return payload
}

func envelope(eventID uuid.UUID, eventType, aggregateType string, aggregateID uuid.UUID, aggregateVersion int64, subjectID *uuid.UUID, correlationID uuid.UUID, recordedAt time.Time, payload any) (json.RawMessage, string, error) {
	var actor *ActorReference
	if subjectID != nil {
		actor = &ActorReference{SubjectID: *subjectID, PrincipalType: PrincipalTypeUser}
	}
	return envelopeForActor(eventID, eventType, aggregateType, aggregateID, aggregateVersion, actor, correlationID, recordedAt, payload)
}

func envelopeForActor(eventID uuid.UUID, eventType, aggregateType string, aggregateID uuid.UUID, aggregateVersion int64, actorReference *ActorReference, correlationID uuid.UUID, recordedAt time.Time, payload any) (json.RawMessage, string, error) {
	var actor any
	if actorReference != nil {
		actor = map[string]any{"subjectId": actorReference.SubjectID, "principalType": actorReference.PrincipalType, "profileRevision": nil}
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
	var actor *ActorReference
	if subjectID != nil {
		actor = &ActorReference{SubjectID: *subjectID, PrincipalType: PrincipalTypeUser}
	}
	return insertFactOutboxForActor(ctx, tx, eventID, asset, version, eventType, actor, correlationID, recordedAt, generation, rotation, dependency)
}

func insertFactOutboxForActor(ctx context.Context, tx pgx.Tx, eventID uuid.UUID, asset AssetRecord, version int64, eventType string, actor *ActorReference, correlationID uuid.UUID, recordedAt time.Time, generation int, rotation media.Rotation, dependency *uuid.UUID) error {
	body, checksum, err := envelopeForActor(eventID, eventType, "MEDIA", asset.ID, version, actor, correlationID, recordedAt, factPayload(asset, asset.Status, generation, rotation))
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

func insertProcessingRequestOutbox(ctx context.Context, tx pgx.Tx, jobID uuid.UUID, asset AssetRecord, generation int, correlationID uuid.UUID, recordedAt time.Time, dependency *uuid.UUID) error {
	eventID := uuid.New()
	payload := map[string]any{
		"processingJobId": jobID, "mediaId": asset.ID, "warehouseId": asset.WarehouseID,
		"kind": asset.Kind, "processingKind": media.ProcessingInitial, "generation": generation,
		"rotationDegrees": media.Rotation0, "sourceVersionId": asset.SourceVersionID,
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
