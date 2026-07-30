package persistence

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/assetimport"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func (repository *Repository) CreateAssetImport(ctx context.Context, command assetimport.CreateCommand) (assetimport.Job, bool, error) {
	if command.JobID == uuid.Nil || command.AssetImportID == uuid.Nil || command.WarehouseID == uuid.Nil ||
		command.IdempotencyKey == uuid.Nil || !validSHA256(command.RequestSHA256) || len(command.Sources) < 1 ||
		len(command.Sources) > assetimport.MaxSourcesPerJob {
		return assetimport.Job{}, false, assetimport.ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return assetimport.Job{}, false, err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		"media-asset-import:"+command.AssetImportID.String()); err != nil {
		return assetimport.Job{}, false, err
	}
	var existingID uuid.UUID
	var existingWarehouse uuid.UUID
	var existingSHA string
	err = tx.QueryRow(ctx, `select job_id,warehouse_id,preflight_request_sha256
		from media_asset_import_job where asset_import_id=$1 for update`, command.AssetImportID).
		Scan(&existingID, &existingWarehouse, &existingSHA)
	if err == nil {
		if existingWarehouse != command.WarehouseID || existingSHA != command.RequestSHA256 {
			return assetimport.Job{}, false, assetimport.ErrIdempotencyMismatch
		}
		job, loadErr := loadAssetImport(ctx, tx, existingID)
		if loadErr != nil {
			return assetimport.Job{}, false, loadErr
		}
		if commitErr := tx.Commit(ctx); commitErr != nil {
			return assetimport.Job{}, false, commitErr
		}
		return job, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return assetimport.Job{}, false, err
	}
	now := repository.now().UTC()
	_, err = tx.Exec(ctx, `insert into media_asset_import_job (
		job_id,asset_import_id,warehouse_id,preflight_idempotency_key,preflight_request_sha256,
		job_status,next_attempt_at,created_at,updated_at)
		values ($1,$2,$3,$4,$5,'PREFLIGHT_PENDING',$6,$6,$6)`,
		command.JobID, command.AssetImportID, command.WarehouseID, command.IdempotencyKey, command.RequestSHA256, now)
	if err != nil {
		return assetimport.Job{}, false, translateAssetImportError(err)
	}
	seen := make(map[uuid.UUID]struct{}, len(command.Sources))
	for _, source := range command.Sources {
		if source.SourceRowID == uuid.Nil || len(source.PublicKey) != 14 || !assetimport.ValidYandexPublicKey(source.PublicKey) {
			return assetimport.Job{}, false, assetimport.ErrConflict
		}
		if _, duplicate := seen[source.SourceRowID]; duplicate {
			return assetimport.Job{}, false, assetimport.ErrConflict
		}
		seen[source.SourceRowID] = struct{}{}
		if _, err := tx.Exec(ctx, `insert into media_asset_import_source (
			job_id,source_row_id,yandex_public_key,created_at) values ($1,$2,$3,$4)`,
			command.JobID, source.SourceRowID, source.PublicKey, now); err != nil {
			return assetimport.Job{}, false, translateAssetImportError(err)
		}
	}
	job, err := loadAssetImport(ctx, tx, command.JobID)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return assetimport.Job{}, false, translateAssetImportError(err)
	}
	return job, false, nil
}

func (repository *Repository) GetAssetImport(ctx context.Context, jobID uuid.UUID) (assetimport.Job, error) {
	return loadAssetImport(ctx, repository.pool, jobID)
}

func (repository *Repository) ActivateAssetImport(ctx context.Context, command assetimport.ActivateCommand) (assetimport.Job, bool, error) {
	if command.JobID == uuid.Nil || command.IdempotencyKey == uuid.Nil || !validSHA256(command.RequestSHA256) ||
		len(command.Bindings) < 1 || len(command.Bindings) > assetimport.MaxSourcesPerJob {
		return assetimport.Job{}, false, assetimport.ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return assetimport.Job{}, false, err
	}
	defer tx.Rollback(ctx)
	job, err := loadAssetImportForUpdate(ctx, tx, command.JobID)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	if job.Status != assetimport.StatusPreflightReady {
		if job.Status == assetimport.StatusActivationPending || job.Status == assetimport.StatusActivationRunning || job.Status == assetimport.StatusCompleted {
			if jobActivationSHA(ctx, tx, command.JobID) == command.RequestSHA256 {
				if err := tx.Commit(ctx); err != nil {
					return assetimport.Job{}, false, err
				}
				return job, true, nil
			}
		}
		return assetimport.Job{}, false, assetimport.ErrConflict
	}
	if err := verifyAssetImportBindings(job.Sources, command.Bindings); err != nil {
		return assetimport.Job{}, false, err
	}
	for _, binding := range command.Bindings {
		if err := requireOwnerBinding(ctx, tx, OwnerTypeCabin, binding.CabinID.String(), job.WarehouseID, repository.now()); err != nil {
			return assetimport.Job{}, false, translateOwnerProofError(err)
		}
		if _, err := tx.Exec(ctx, `update media_asset_import_source set cabin_id=$3
			where job_id=$1 and source_row_id=$2`, command.JobID, binding.SourceRowID, binding.CabinID); err != nil {
			return assetimport.Job{}, false, err
		}
	}
	now := repository.now().UTC()
	_, err = tx.Exec(ctx, `update media_asset_import_job set
		activation_idempotency_key=$2,activation_request_sha256=$3,
		job_status='ACTIVATION_PENDING',failed_phase=null,failure_code=null,next_attempt_at=$4,
		lease_owner=null,lease_token=null,lease_until=null,updated_at=$4 where job_id=$1`,
		command.JobID, command.IdempotencyKey, command.RequestSHA256, now)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	job, err = loadAssetImport(ctx, tx, command.JobID)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return assetimport.Job{}, false, err
	}
	return job, false, nil
}

func (repository *Repository) RetryAssetImport(ctx context.Context, command assetimport.RetryCommand) (assetimport.Job, bool, error) {
	if command.JobID == uuid.Nil || command.IdempotencyKey == uuid.Nil || !validSHA256(command.RequestSHA256) {
		return assetimport.Job{}, false, assetimport.ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return assetimport.Job{}, false, err
	}
	defer tx.Rollback(ctx)
	job, err := loadAssetImportForUpdate(ctx, tx, command.JobID)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	var priorSHA string
	err = tx.QueryRow(ctx, `select request_sha256 from media_asset_import_retry_receipt
		where job_id=$1 and idempotency_key=$2 for update`, command.JobID, command.IdempotencyKey).Scan(&priorSHA)
	if err == nil {
		if priorSHA != command.RequestSHA256 {
			return assetimport.Job{}, false, assetimport.ErrIdempotencyMismatch
		}
		if err := tx.Commit(ctx); err != nil {
			return assetimport.Job{}, false, err
		}
		return job, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return assetimport.Job{}, false, err
	}
	if job.Status != assetimport.StatusFailed || (job.FailurePhase != assetimport.PhasePreflight && job.FailurePhase != assetimport.PhaseActivation) {
		return assetimport.Job{}, false, assetimport.ErrConflict
	}
	now := repository.now().UTC()
	status := assetimport.StatusPreflightPending
	attemptReset := "preflight_attempt_count=0"
	if job.FailurePhase == assetimport.PhaseActivation {
		status = assetimport.StatusActivationPending
		attemptReset = "activation_attempt_count=0"
		if _, err := tx.Exec(ctx, `update media_asset_import_entry set entry_status='PREPARED',warning_code=null,updated_at=$2
			where job_id=$1 and entry_status in ('DOWNLOADING','FAILED')`, command.JobID, now); err != nil {
			return assetimport.Job{}, false, err
		}
	}
	_, err = tx.Exec(ctx, `insert into media_asset_import_retry_receipt (job_id,idempotency_key,request_sha256,created_at)
		values ($1,$2,$3,$4)`, command.JobID, command.IdempotencyKey, command.RequestSHA256, now)
	if err != nil {
		return assetimport.Job{}, false, translateAssetImportError(err)
	}
	_, err = tx.Exec(ctx, `update media_asset_import_job set job_status=$2,failed_phase=null,failure_code=null,`+
		attemptReset+`,next_attempt_at=$3,lease_owner=null,lease_token=null,lease_until=null,updated_at=$3 where job_id=$1`,
		command.JobID, status, now)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	job, err = loadAssetImport(ctx, tx, command.JobID)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return assetimport.Job{}, false, err
	}
	return job, false, nil
}

func (repository *Repository) ReplaceAssetImportSources(ctx context.Context, command assetimport.ReplaceSourcesCommand) (assetimport.Job, bool, error) {
	if command.JobID == uuid.Nil || command.IdempotencyKey == uuid.Nil || !validSHA256(command.RequestSHA256) ||
		len(command.Replacements) < 1 || len(command.Replacements) > assetimport.MaxSourcesPerJob {
		return assetimport.Job{}, false, assetimport.ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return assetimport.Job{}, false, err
	}
	defer tx.Rollback(ctx)
	job, err := loadAssetImportForUpdate(ctx, tx, command.JobID)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	var priorSHA string
	err = tx.QueryRow(ctx, `select request_sha256 from media_asset_import_retry_receipt
		where job_id=$1 and idempotency_key=$2 for update`, command.JobID, command.IdempotencyKey).Scan(&priorSHA)
	if err == nil {
		if priorSHA != command.RequestSHA256 {
			return assetimport.Job{}, false, assetimport.ErrIdempotencyMismatch
		}
		if err := tx.Commit(ctx); err != nil {
			return assetimport.Job{}, false, err
		}
		return job, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return assetimport.Job{}, false, err
	}
	if job.Status != assetimport.StatusFailed || job.FailurePhase != assetimport.PhasePreflight {
		return assetimport.Job{}, false, assetimport.ErrConflict
	}
	knownSources := make(map[uuid.UUID]struct{}, len(job.Sources))
	for _, source := range job.Sources {
		knownSources[source.SourceRowID] = struct{}{}
	}
	seen := make(map[uuid.UUID]struct{}, len(command.Replacements))
	for _, replacement := range command.Replacements {
		if replacement.SourceRowID == uuid.Nil || !assetimport.ValidYandexPublicKey(replacement.PublicKey) {
			return assetimport.Job{}, false, assetimport.ErrConflict
		}
		if _, known := knownSources[replacement.SourceRowID]; !known {
			return assetimport.Job{}, false, assetimport.ErrConflict
		}
		if _, duplicate := seen[replacement.SourceRowID]; duplicate {
			return assetimport.Job{}, false, assetimport.ErrConflict
		}
		seen[replacement.SourceRowID] = struct{}{}
	}
	now := repository.now().UTC()
	for _, replacement := range command.Replacements {
		updated, updateErr := tx.Exec(ctx, `update media_asset_import_source set yandex_public_key=$3
			where job_id=$1 and source_row_id=$2`, command.JobID, replacement.SourceRowID, replacement.PublicKey)
		if updateErr != nil {
			return assetimport.Job{}, false, updateErr
		}
		if updated.RowsAffected() != 1 {
			return assetimport.Job{}, false, assetimport.ErrConflict
		}
	}
	// A failed preflight has not activated any media. Deleting stale discovery
	// rows ensures the fresh keys are the sole source of the next enumeration.
	if _, err := tx.Exec(ctx, `delete from media_asset_import_entry where job_id=$1`, command.JobID); err != nil {
		return assetimport.Job{}, false, err
	}
	if _, err := tx.Exec(ctx, `insert into media_asset_import_retry_receipt (job_id,idempotency_key,request_sha256,created_at)
		values ($1,$2,$3,$4)`, command.JobID, command.IdempotencyKey, command.RequestSHA256, now); err != nil {
		return assetimport.Job{}, false, translateAssetImportError(err)
	}
	if _, err := tx.Exec(ctx, `update media_asset_import_job set job_status='PREFLIGHT_PENDING',failed_phase=null,
		failure_code=null,next_attempt_at=$2,lease_owner=null,lease_token=null,lease_until=null,updated_at=$2 where job_id=$1`,
		command.JobID, now); err != nil {
		return assetimport.Job{}, false, err
	}
	job, err = loadAssetImport(ctx, tx, command.JobID)
	if err != nil {
		return assetimport.Job{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return assetimport.Job{}, false, err
	}
	return job, false, nil
}

func (repository *Repository) ClaimAssetImport(ctx context.Context, owner string, lease time.Duration) (assetimport.Work, bool, error) {
	if strings.TrimSpace(owner) == "" || len(owner) > 128 || lease <= 0 {
		return assetimport.Work{}, false, assetimport.ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return assetimport.Work{}, false, err
	}
	defer tx.Rollback(ctx)
	now := repository.now().UTC()
	var jobID uuid.UUID
	var status assetimport.Status
	err = tx.QueryRow(ctx, `select job_id,job_status from media_asset_import_job
		where (job_status in ('PREFLIGHT_PENDING','ACTIVATION_PENDING') and next_attempt_at <= $1)
		   or (job_status in ('PREFLIGHT_RUNNING','ACTIVATION_RUNNING') and lease_until <= $1)
		order by next_attempt_at,created_at,job_id for update skip locked limit 1`, now).Scan(&jobID, &status)
	if errors.Is(err, pgx.ErrNoRows) {
		if commitErr := tx.Commit(ctx); commitErr != nil {
			return assetimport.Work{}, false, commitErr
		}
		return assetimport.Work{}, false, nil
	}
	if err != nil {
		return assetimport.Work{}, false, err
	}
	running := assetimport.StatusPreflightRunning
	attemptColumn := "preflight_attempt_count"
	if status == assetimport.StatusActivationPending || status == assetimport.StatusActivationRunning {
		running, attemptColumn = assetimport.StatusActivationRunning, "activation_attempt_count"
	}
	token := uuid.New()
	until := now.Add(lease)
	query := fmt.Sprintf(`update media_asset_import_job set job_status=$2,%s=%s+1,
		lease_owner=$3,lease_token=$4,lease_until=$5,updated_at=$1 where job_id=$6`, attemptColumn, attemptColumn)
	if _, err := tx.Exec(ctx, query, now, running, owner, token, until, jobID); err != nil {
		return assetimport.Work{}, false, err
	}
	job, err := loadAssetImport(ctx, tx, jobID)
	if err != nil {
		return assetimport.Work{}, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return assetimport.Work{}, false, err
	}
	return assetimport.Work{Job: job, LeaseToken: token}, true, nil
}

func (repository *Repository) RenewAssetImportLease(ctx context.Context, jobID, token uuid.UUID, owner string, lease time.Duration) error {
	if jobID == uuid.Nil || token == uuid.Nil || strings.TrimSpace(owner) == "" || lease <= 0 {
		return assetimport.ErrLeaseLost
	}
	now := repository.now().UTC()
	command, err := repository.pool.Exec(ctx, `update media_asset_import_job set lease_until=$5,updated_at=$4
		where job_id=$1 and lease_token=$2 and lease_owner=$3
		and job_status in ('PREFLIGHT_RUNNING','ACTIVATION_RUNNING') and lease_until > $4`,
		jobID, token, owner, now, now.Add(lease))
	if err != nil {
		return err
	}
	if command.RowsAffected() != 1 {
		return assetimport.ErrLeaseLost
	}
	return nil
}

func (repository *Repository) CompleteAssetImportPreflight(ctx context.Context, jobID, token uuid.UUID, entries []assetimport.DiscoveredEntry) error {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	job, err := assetImportJobLease(ctx, tx, jobID, token, assetimport.StatusPreflightRunning)
	if err != nil {
		return err
	}
	if len(entries) > assetimport.MaxEntriesPerJob {
		return assetimport.ErrConflict
	}
	if _, err := tx.Exec(ctx, `delete from media_asset_import_entry where job_id=$1`, jobID); err != nil {
		return err
	}
	seen := make(map[string]struct{}, len(entries))
	for _, entry := range entries {
		if !validDiscoveredEntry(job, entry) {
			return assetimport.ErrConflict
		}
		key := entry.SourceRowID.String() + "\x00" + entry.ResourcePath
		if _, duplicate := seen[key]; duplicate {
			return assetimport.ErrConflict
		}
		seen[key] = struct{}{}
		if _, err := tx.Exec(ctx, `insert into media_asset_import_entry (
			entry_id,job_id,source_row_id,resource_path,file_name,content_type,discovered_size_bytes,
			entry_status,warning_code,created_at,updated_at)
			values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$10)`,
			entry.ID, jobID, entry.SourceRowID, entry.ResourcePath, entry.FileName, entry.ContentType,
			entry.SizeBytes, entry.Status, nullableWarning(entry.WarningCode), repository.now().UTC()); err != nil {
			return translateAssetImportError(err)
		}
	}
	now := repository.now().UTC()
	if _, err := tx.Exec(ctx, `update media_asset_import_job set job_status='PREFLIGHT_READY',failure_code=null,
		next_attempt_at=$3,lease_owner=null,lease_token=null,lease_until=null,updated_at=$3
		where job_id=$1 and lease_token=$2`, jobID, token, now); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (repository *Repository) RequeueAssetImport(ctx context.Context, jobID, token uuid.UUID, phase assetimport.Phase, code string, next time.Time) error {
	return repository.finishAssetImportAttempt(ctx, jobID, token, phase, code, next.UTC(), false)
}

func (repository *Repository) FailAssetImport(ctx context.Context, jobID, token uuid.UUID, phase assetimport.Phase, code string) error {
	return repository.finishAssetImportAttempt(ctx, jobID, token, phase, code, repository.now().UTC(), true)
}

func (repository *Repository) finishAssetImportAttempt(ctx context.Context, jobID, token uuid.UUID, phase assetimport.Phase, code string, next time.Time, terminal bool) error {
	if !validAssetImportCode(code) || (phase != assetimport.PhasePreflight && phase != assetimport.PhaseActivation) {
		return assetimport.ErrConflict
	}
	running := assetimport.StatusPreflightRunning
	pending := assetimport.StatusPreflightPending
	if phase == assetimport.PhaseActivation {
		running, pending = assetimport.StatusActivationRunning, assetimport.StatusActivationPending
	}
	status := pending
	failedPhase := any(nil)
	if terminal {
		status, failedPhase = assetimport.StatusFailed, phase
	}
	command, err := repository.pool.Exec(ctx, `update media_asset_import_job set job_status=$3,failed_phase=$4,
		failure_code=$5,next_attempt_at=$6,lease_owner=null,lease_token=null,lease_until=null,updated_at=$6
		where job_id=$1 and lease_token=$2 and job_status=$7`, jobID, token, status, failedPhase, code, next, running)
	if err != nil {
		return err
	}
	if command.RowsAffected() != 1 {
		return assetimport.ErrLeaseLost
	}
	return nil
}

func (repository *Repository) SetAssetImportEntryStatus(ctx context.Context, jobID, token, entryID uuid.UUID, status assetimport.EntryStatus, warningCode string) error {
	if status != assetimport.EntryDownloading && status != assetimport.EntrySkipped && status != assetimport.EntryFailed {
		return assetimport.ErrConflict
	}
	if (status == assetimport.EntrySkipped || status == assetimport.EntryFailed) && !validAssetImportCode(warningCode) {
		return assetimport.ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := assetImportJobLease(ctx, tx, jobID, token, assetimport.StatusActivationRunning); err != nil {
		return err
	}
	command, err := tx.Exec(ctx, `update media_asset_import_entry set entry_status=$3,warning_code=$4,updated_at=$5
		where job_id=$1 and entry_id=$2 and entry_status in ('PREPARED','DOWNLOADING')`,
		jobID, entryID, status, nullableWarning(warningCode), repository.now().UTC())
	if err != nil {
		return err
	}
	if command.RowsAffected() != 1 {
		return assetimport.ErrConflict
	}
	return tx.Commit(ctx)
}

func (repository *Repository) ImportAsset(ctx context.Context, command assetimport.ImportAssetCommand) (assetimport.ImportAssetResult, error) {
	if !validImportAssetCommand(command) {
		return assetimport.ImportAssetResult{}, assetimport.ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return assetimport.ImportAssetResult{}, err
	}
	defer tx.Rollback(ctx)
	job, err := assetImportJobLease(ctx, tx, command.JobID, command.LeaseToken, assetimport.StatusActivationRunning)
	if err != nil {
		return assetimport.ImportAssetResult{}, err
	}
	if job.WarehouseID != command.WarehouseID {
		return assetimport.ImportAssetResult{}, assetimport.ErrConflict
	}
	var entryStatus assetimport.EntryStatus
	var entryMediaID *uuid.UUID
	var sourceCabinID *uuid.UUID
	err = tx.QueryRow(ctx, `select entry.entry_status,entry.media_id,source.cabin_id
		from media_asset_import_entry entry join media_asset_import_source source
		  on source.job_id=entry.job_id and source.source_row_id=entry.source_row_id
		where entry.job_id=$1 and entry.entry_id=$2 for update of entry,source`, command.JobID, command.EntryID).
		Scan(&entryStatus, &entryMediaID, &sourceCabinID)
	if errors.Is(err, pgx.ErrNoRows) {
		return assetimport.ImportAssetResult{}, assetimport.ErrNotFound
	}
	if err != nil {
		return assetimport.ImportAssetResult{}, err
	}
	if sourceCabinID == nil || *sourceCabinID != command.CabinID {
		return assetimport.ImportAssetResult{}, assetimport.ErrConflict
	}
	if entryStatus == assetimport.EntryImported {
		if entryMediaID == nil || *entryMediaID != command.MediaID {
			return assetimport.ImportAssetResult{}, assetimport.ErrConflict
		}
		if err := tx.Commit(ctx); err != nil {
			return assetimport.ImportAssetResult{}, err
		}
		return assetimport.ImportAssetResult{MediaID: command.MediaID}, nil
	}
	if entryStatus != assetimport.EntryDownloading && entryStatus != assetimport.EntryPrepared {
		return assetimport.ImportAssetResult{}, assetimport.ErrConflict
	}
	if err := requireOwnerBinding(ctx, tx, OwnerTypeCabin, command.CabinID.String(), command.WarehouseID, repository.now()); err != nil {
		return assetimport.ImportAssetResult{}, translateOwnerProofError(err)
	}
	var exists bool
	var ownerType, ownerID, contentType, checksum, objectKey string
	err = tx.QueryRow(ctx, `select true,owner_type,owner_id,finalized_content_type,source_checksum_sha256,source_object_key
		from media_asset where media_id=$1 for update`, command.MediaID).
		Scan(&exists, &ownerType, &ownerID, &contentType, &checksum, &objectKey)
	if err == nil {
		if !exists || ownerType != OwnerTypeCabin || ownerID != command.CabinID.String() || contentType != command.ContentType ||
			checksum != command.ChecksumSHA256 || objectKey != command.ObjectKey {
			return assetimport.ImportAssetResult{}, assetimport.ErrConflict
		}
		if _, err := tx.Exec(ctx, `update media_asset_import_entry set entry_status='IMPORTED',media_id=$3,
			warning_code=null,updated_at=$4 where job_id=$1 and entry_id=$2`, command.JobID, command.EntryID, command.MediaID, repository.now().UTC()); err != nil {
			return assetimport.ImportAssetResult{}, err
		}
		if err := tx.Commit(ctx); err != nil {
			return assetimport.ImportAssetResult{}, err
		}
		return assetimport.ImportAssetResult{MediaID: command.MediaID}, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return assetimport.ImportAssetResult{}, err
	}
	if err := enforceOwnerMediaLimit(ctx, tx, OwnerTypeCabin, command.CabinID.String(), command.WarehouseID, 100); err != nil {
		if errors.Is(err, ErrOwnerProofMissing) {
			return assetimport.ImportAssetResult{}, assetimport.ErrOwnerProofMissing
		}
		if errors.Is(err, ErrConflict) {
			return assetimport.ImportAssetResult{}, assetimport.ErrOwnerMediaLimit
		}
		return assetimport.ImportAssetResult{}, err
	}
	now := repository.now().UTC()
	_, err = tx.Exec(ctx, `insert into media_asset (
		media_id,folder_id,client_reference_id,created_by_principal_type,created_by_actor_id,
		owner_type,owner_id,warehouse_id,media_kind,original_file_name,original_content_type,source_object_key,
		source_version_id,source_etag,source_checksum_sha256,finalized_content_type,finalized_size_bytes,
		processing_status,rotation_degrees,current_generation,pending_generation,pending_rotation_degrees,
		sort_order,size_bytes,processing_attempts,processing_error,version,next_generation,created_at,updated_at)
		values ($1,$1,null,null,null,$2,$3,$4,'IMAGE',$5,$6,$7,$8,$9,$10,$6,$11,
		'PROCESSING',0,0,1,0,0,$11,0,null,1,2,$12,$12)`,
		command.MediaID, OwnerTypeCabin, command.CabinID.String(), command.WarehouseID, command.FileName,
		command.ContentType, command.ObjectKey, command.ObjectVersionID, command.ETag, command.ChecksumSHA256,
		command.SizeBytes, now)
	if err != nil {
		return assetimport.ImportAssetResult{}, translateAssetImportError(err)
	}
	jobID := uuid.New()
	_, err = tx.Exec(ctx, `insert into media_processing_job (
		processing_job_id,media_id,generation,processing_kind,requested_rotation_degrees,job_status,
		source_version_id,source_checksum_sha256,next_attempt_at)
		values ($1,$2,1,'INITIAL',0,'PENDING',$3,$4,$5)`,
		jobID, command.MediaID, command.ObjectVersionID, command.ChecksumSHA256, now)
	if err != nil {
		return assetimport.ImportAssetResult{}, translateAssetImportError(err)
	}
	asset := AssetRecord{
		ID: command.MediaID, FolderID: command.MediaID, OwnerType: OwnerTypeCabin, OwnerID: command.CabinID.String(),
		WarehouseID: command.WarehouseID, Kind: media.KindImage, FileName: command.FileName, ContentType: command.ContentType,
		SourceObjectKey: command.ObjectKey, SourceVersionID: command.ObjectVersionID, SourceETag: command.ETag,
		SourceChecksum: command.ChecksumSHA256, Status: media.StatusProcessing, Version: 1, Generation: 0,
		Rotation: media.Rotation0, SortOrder: 0, SizeBytes: &command.SizeBytes, CreatedAt: now,
	}
	uploadedID := uuid.New()
	if err := appendInitialEventWithIDForActor(ctx, tx, uploadedID, command.MediaID, "media.media.uploaded.v1", nil, command.CorrelationID, now); err != nil {
		return assetimport.ImportAssetResult{}, err
	}
	if err := insertFactOutboxForActor(ctx, tx, uploadedID, asset, asset.Version, "media.media.uploaded.v1", nil,
		command.CorrelationID, now, 1, media.Rotation0, nil); err != nil {
		return assetimport.ImportAssetResult{}, err
	}
	if err := insertProcessingRequestOutbox(ctx, tx, jobID, asset, 1, media.ProcessingInitial, media.Rotation0,
		command.CorrelationID, now, &uploadedID); err != nil {
		return assetimport.ImportAssetResult{}, err
	}
	_, err = tx.Exec(ctx, `update media_asset_import_entry set entry_status='IMPORTED',media_id=$3,warning_code=null,
		updated_at=$4 where job_id=$1 and entry_id=$2`, command.JobID, command.EntryID, command.MediaID, now)
	if err != nil {
		return assetimport.ImportAssetResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return assetimport.ImportAssetResult{}, translateAssetImportError(err)
	}
	return assetimport.ImportAssetResult{MediaID: command.MediaID}, nil
}

func (repository *Repository) CompleteAssetImportActivation(ctx context.Context, jobID, token uuid.UUID) error {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := assetImportJobLease(ctx, tx, jobID, token, assetimport.StatusActivationRunning); err != nil {
		return err
	}
	var pending int
	if err := tx.QueryRow(ctx, `select count(*) from media_asset_import_entry
		where job_id=$1 and entry_status in ('PREPARED','DOWNLOADING')`, jobID).Scan(&pending); err != nil {
		return err
	}
	if pending != 0 {
		return assetimport.ErrConflict
	}
	now := repository.now().UTC()
	command, err := tx.Exec(ctx, `update media_asset_import_job set job_status='COMPLETED',failure_code=null,
		lease_owner=null,lease_token=null,lease_until=null,updated_at=$3 where job_id=$1 and lease_token=$2`, jobID, token, now)
	if err != nil {
		return err
	}
	if command.RowsAffected() != 1 {
		return assetimport.ErrLeaseLost
	}
	return tx.Commit(ctx)
}

type assetImportQueryer interface {
	QueryRow(context.Context, string, ...any) pgx.Row
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

func loadAssetImport(ctx context.Context, database assetImportQueryer, jobID uuid.UUID) (assetimport.Job, error) {
	var job assetimport.Job
	var status string
	var failedPhase *string
	var failureCode *string
	err := database.QueryRow(ctx, `select job_id,asset_import_id,warehouse_id,job_status,failed_phase,failure_code,
		preflight_attempt_count,activation_attempt_count,created_at,updated_at
		from media_asset_import_job where job_id=$1`, jobID).Scan(
		&job.ID, &job.AssetImportID, &job.WarehouseID, &status, &failedPhase, &failureCode,
		&job.PreflightAttempts, &job.ActivationAttempts, &job.CreatedAt, &job.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return assetimport.Job{}, assetimport.ErrNotFound
	}
	if err != nil {
		return assetimport.Job{}, err
	}
	job.Status = assetimport.Status(status)
	if failedPhase != nil {
		job.FailurePhase = assetimport.Phase(*failedPhase)
	}
	if failureCode != nil {
		job.FailureCode = *failureCode
	}
	sources, err := database.Query(ctx, `select source_row_id,yandex_public_key,cabin_id
		from media_asset_import_source where job_id=$1 order by source_row_id`, jobID)
	if err != nil {
		return assetimport.Job{}, err
	}
	defer sources.Close()
	for sources.Next() {
		var source assetimport.Source
		if err := sources.Scan(&source.SourceRowID, &source.PublicKey, &source.CabinID); err != nil {
			return assetimport.Job{}, err
		}
		job.Sources = append(job.Sources, source)
	}
	if err := sources.Err(); err != nil {
		return assetimport.Job{}, err
	}
	entries, err := database.Query(ctx, `select entry_id,source_row_id,resource_path,file_name,content_type,discovered_size_bytes,
		entry_status,warning_code,media_id from media_asset_import_entry where job_id=$1 order by source_row_id,entry_id`, jobID)
	if err != nil {
		return assetimport.Job{}, err
	}
	defer entries.Close()
	for entries.Next() {
		var entry assetimport.Entry
		var status string
		var warning *string
		if err := entries.Scan(&entry.ID, &entry.SourceRowID, &entry.ResourcePath, &entry.FileName, &entry.ContentType,
			&entry.SizeBytes, &status, &warning, &entry.MediaID); err != nil {
			return assetimport.Job{}, err
		}
		entry.Status = assetimport.EntryStatus(status)
		if warning != nil {
			entry.WarningCode = *warning
		}
		job.Entries = append(job.Entries, entry)
	}
	if err := entries.Err(); err != nil {
		return assetimport.Job{}, err
	}
	return job, nil
}

func loadAssetImportForUpdate(ctx context.Context, tx pgx.Tx, jobID uuid.UUID) (assetimport.Job, error) {
	// Take the job row lock before loading the associated source/entry state;
	// callers then retain a stable command view through transaction commit.
	var exists uuid.UUID
	if err := tx.QueryRow(ctx, `select job_id from media_asset_import_job where job_id=$1 for update`, jobID).Scan(&exists); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return assetimport.Job{}, assetimport.ErrNotFound
		}
		return assetimport.Job{}, err
	}
	return loadAssetImport(ctx, tx, jobID)
}

func assetImportJobLease(ctx context.Context, tx pgx.Tx, jobID, token uuid.UUID, expected assetimport.Status) (assetimport.Job, error) {
	job, err := loadAssetImportForUpdate(ctx, tx, jobID)
	if err != nil {
		return assetimport.Job{}, err
	}
	var leaseToken *uuid.UUID
	var leaseUntil *time.Time
	err = tx.QueryRow(ctx, `select lease_token,lease_until from media_asset_import_job where job_id=$1`, jobID).Scan(&leaseToken, &leaseUntil)
	if err != nil {
		return assetimport.Job{}, err
	}
	if job.Status != expected || leaseToken == nil || *leaseToken != token || leaseUntil == nil || !leaseUntil.After(time.Now().UTC()) {
		return assetimport.Job{}, assetimport.ErrLeaseLost
	}
	return job, nil
}

func jobActivationSHA(ctx context.Context, tx pgx.Tx, jobID uuid.UUID) string {
	var value *string
	if err := tx.QueryRow(ctx, `select activation_request_sha256 from media_asset_import_job where job_id=$1`, jobID).Scan(&value); err != nil || value == nil {
		return ""
	}
	return *value
}

func verifyAssetImportBindings(sources []assetimport.Source, bindings []assetimport.ActivationBinding) error {
	if len(sources) != len(bindings) || len(sources) == 0 {
		return assetimport.ErrConflict
	}
	known := make(map[uuid.UUID]struct{}, len(sources))
	for _, source := range sources {
		known[source.SourceRowID] = struct{}{}
	}
	for _, binding := range bindings {
		if binding.SourceRowID == uuid.Nil || binding.CabinID == uuid.Nil {
			return assetimport.ErrConflict
		}
		if _, exists := known[binding.SourceRowID]; !exists {
			return assetimport.ErrConflict
		}
		delete(known, binding.SourceRowID)
	}
	if len(known) != 0 {
		return assetimport.ErrConflict
	}
	return nil
}

func validDiscoveredEntry(job assetimport.Job, entry assetimport.DiscoveredEntry) bool {
	if entry.ID == uuid.Nil || entry.SourceRowID == uuid.Nil || entry.ResourcePath == "" || len(entry.ResourcePath) > 4096 ||
		entry.FileName == "" || len(entry.FileName) > 512 || len(entry.ContentType) > 255 ||
		(entry.Status != assetimport.EntryPrepared && entry.Status != assetimport.EntrySkipped) ||
		(entry.SizeBytes != nil && *entry.SizeBytes < 0) {
		return false
	}
	if entry.Status == assetimport.EntrySkipped && !validAssetImportCode(entry.WarningCode) {
		return false
	}
	for _, source := range job.Sources {
		if source.SourceRowID == entry.SourceRowID {
			return true
		}
	}
	return false
}

func validImportAssetCommand(command assetimport.ImportAssetCommand) bool {
	if command.JobID == uuid.Nil || command.LeaseToken == uuid.Nil || command.EntryID == uuid.Nil || command.MediaID == uuid.Nil ||
		command.WarehouseID == uuid.Nil || command.CabinID == uuid.Nil || command.FileName == "" || len(command.FileName) > 512 ||
		(command.ContentType != "image/jpeg" && command.ContentType != "image/png" && command.ContentType != "image/webp") ||
		command.SizeBytes <= 0 || command.ObjectKey == "" || len(command.ObjectKey) > 1024 ||
		command.ObjectVersionID == "" || len(command.ObjectVersionID) > 255 || command.ETag == "" || len(command.ETag) > 255 ||
		!validSHA256(command.ChecksumSHA256) || command.CorrelationID == uuid.Nil {
		return false
	}
	return true
}

func validAssetImportCode(value string) bool {
	if len(value) < 1 || len(value) > 64 {
		return false
	}
	for _, character := range value {
		if !((character >= 'A' && character <= 'Z') || character == '_') {
			return false
		}
	}
	return true
}

func nullableWarning(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return value
}

func translateOwnerProofError(err error) error {
	if errors.Is(err, ErrOwnerProofMissing) {
		return assetimport.ErrOwnerProofMissing
	}
	if errors.Is(err, ErrConflict) {
		return assetimport.ErrConflict
	}
	return err
}

func translateAssetImportError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, ErrConflict) {
		return assetimport.ErrConflict
	}
	return err
}
