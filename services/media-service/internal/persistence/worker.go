package persistence

import (
	"context"
	"errors"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

const processingConsumer = "media-processing-v1"

type ProcessingMessage struct {
	EventID                 uuid.UUID
	BodySHA256              string
	Topic                   string
	EventType               string
	AggregateType           string
	AggregateID             uuid.UUID
	AggregateVersion        int64
	RecordKey               uuid.UUID
	CorrelationID           uuid.UUID
	ExpectedMediaID         uuid.UUID
	ExpectedWarehouseID     uuid.UUID
	ExpectedKind            media.Kind
	ExpectedProcessingKind  media.ProcessingKind
	ExpectedGeneration      int
	ExpectedRotation        media.Rotation
	ExpectedSourceVersionID string
}

type WorkerJob struct {
	ProcessingMessage
	JobID           uuid.UUID
	MediaID         uuid.UUID
	WarehouseID     uuid.UUID
	MediaKind       media.Kind
	ProcessingKind  media.ProcessingKind
	Generation      int
	Rotation        media.Rotation
	SourceObjectKey string
	SourceVersionID string
	SourceChecksum  string
	ContentType     string
	Attempt         int
	LeaseToken      uuid.UUID
	LeaseFence      int64
}

type ClaimResult struct {
	Job       WorkerJob
	Duplicate bool
}

type ProcessingConflictDisposition string

const (
	ProcessingConflictRetryAt          ProcessingConflictDisposition = "RETRY_AT"
	ProcessingConflictTerminalConflict ProcessingConflictDisposition = "TERMINAL_CONFLICT"
)

type ProcessingConflictResolution struct {
	Disposition ProcessingConflictDisposition
	RetryAt     time.Time
}

func (repository *Repository) ClaimProcessingJob(ctx context.Context, message ProcessingMessage, owner string, lease time.Duration) (ClaimResult, error) {
	if !validProcessingMessage(message) {
		return ClaimResult{}, ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return ClaimResult{}, err
	}
	defer tx.Rollback(ctx)
	var publicationStatus string
	err = tx.QueryRow(ctx, `select request.event_status
		from media_transport_outbox request
		join media_transport_outbox dependency on dependency.event_id=request.depends_on_event_id
		where request.event_id=$1 and request.event_status in ('PUBLISHING','PUBLISHED')
		  and request.topic=$2 and request.event_type=$3
		  and request.aggregate_type=$4 and request.aggregate_id=$5
		  and request.aggregate_version=$6 and request.record_key=$7
		  and request.envelope_sha256=$8
		  and dependency.event_status='PUBLISHED'
		for update of request`,
		message.EventID, message.Topic, message.EventType, message.AggregateType,
		message.AggregateID, message.AggregateVersion, message.RecordKey,
		message.BodySHA256).Scan(&publicationStatus)
	if errors.Is(err, pgx.ErrNoRows) {
		return ClaimResult{}, ErrConflict
	}
	if err != nil {
		return ClaimResult{}, err
	}
	if publicationStatus == "PUBLISHING" {
		// Receiving the exact service-owned Kafka record proves that the broker
		// accepted it. Reconcile the narrow broker-ACK/DB-mark race in the same
		// transaction; the relay's later fenced mark then harmlessly loses its lease.
		command, updateErr := tx.Exec(ctx, `update media_transport_outbox
			set event_status='PUBLISHED',published_at=clock_timestamp(),
				lease_owner=null,lease_token=null,lease_until=null,last_error_code=null
			where event_id=$1 and event_status='PUBLISHING'`, message.EventID)
		if updateErr != nil {
			return ClaimResult{}, updateErr
		}
		if command.RowsAffected() != 1 {
			return ClaimResult{}, ErrConflict
		}
	}
	var existingHash string
	err = tx.QueryRow(ctx, `select body_sha256 from media_processing_inbox
		where consumer_name=$1 and event_id=$2`, processingConsumer, message.EventID).Scan(&existingHash)
	if err == nil {
		if existingHash != message.BodySHA256 {
			return ClaimResult{}, ErrIdempotencyMismatch
		}
		if err := tx.Commit(ctx); err != nil {
			return ClaimResult{}, err
		}
		return ClaimResult{Duplicate: true}, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return ClaimResult{}, err
	}
	var checkpoint int64
	err = tx.QueryRow(ctx, `select aggregate_version from media_consumer_aggregate_checkpoint
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3 for update`,
		processingConsumer, message.AggregateType, message.AggregateID).Scan(&checkpoint)
	if errors.Is(err, pgx.ErrNoRows) {
		checkpoint = 0
	} else if err != nil {
		return ClaimResult{}, err
	}
	expected := checkpoint + 1
	if message.AggregateVersion != expected {
		if err := quarantineProcessingGap(ctx, tx, message, expected); err != nil {
			return ClaimResult{}, err
		}
		if err := tx.Commit(ctx); err != nil {
			return ClaimResult{}, err
		}
		return ClaimResult{}, ErrVersionGap
	}
	var blocked bool
	if err := tx.QueryRow(ctx, `select exists(select 1 from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3 and reconciled_at is null)`,
		processingConsumer, message.AggregateType, message.AggregateID).Scan(&blocked); err != nil {
		return ClaimResult{}, err
	}
	if blocked {
		return ClaimResult{}, ErrVersionGap
	}
	job := WorkerJob{ProcessingMessage: message, JobID: message.AggregateID, LeaseToken: uuid.New()}
	err = tx.QueryRow(ctx, `
		update media_processing_job job set
			job_status='RUNNING',attempt_count=attempt_count+1,lease_owner=$2,
			lease_token=$3,lease_fence=lease_fence+1,lease_until=clock_timestamp()+$4::interval,
			last_error=null
		from media_asset a
		where job.processing_job_id=$1 and job.media_id=a.media_id
		  and job.generation>0 and job.source_version_id is not null and job.source_checksum_sha256 is not null
		  and (job.job_status='PENDING' or (job.job_status='RUNNING' and job.lease_until<clock_timestamp()))
		  and job.next_attempt_at<=clock_timestamp()
		  and a.processing_status='PROCESSING' and a.pending_generation=job.generation
		  and media_asset_is_available(a.media_id)
		  and not exists (select 1 from media_recovery_quarantine quarantine
		      where quarantine.source_table='media_processing_job' and quarantine.source_id=job.processing_job_id)
		returning job.media_id,a.warehouse_id,a.media_kind,job.processing_kind,
			job.generation,job.requested_rotation_degrees,a.source_object_key,
			job.source_version_id,job.source_checksum_sha256,
			coalesce(a.finalized_content_type,a.original_content_type),
			job.attempt_count,job.lease_fence`,
		job.JobID, owner, job.LeaseToken, lease.String()).Scan(
		&job.MediaID, &job.WarehouseID, &job.MediaKind, &job.ProcessingKind,
		&job.Generation, &job.Rotation, &job.SourceObjectKey, &job.SourceVersionID,
		&job.SourceChecksum, &job.ContentType, &job.Attempt, &job.LeaseFence)
	if errors.Is(err, pgx.ErrNoRows) {
		return ClaimResult{}, ErrConflict
	}
	if err != nil {
		return ClaimResult{}, err
	}
	if job.MediaID != message.ExpectedMediaID || job.WarehouseID != message.ExpectedWarehouseID ||
		job.MediaKind != message.ExpectedKind || job.ProcessingKind != message.ExpectedProcessingKind ||
		job.Generation != message.ExpectedGeneration || job.Rotation != message.ExpectedRotation ||
		job.SourceVersionID != message.ExpectedSourceVersionID {
		return ClaimResult{}, ErrConflict
	}
	if err := tx.Commit(ctx); err != nil {
		return ClaimResult{}, err
	}
	return ClaimResult{Job: job}, nil
}

// ResolveProcessingClaimConflict classifies a valid Kafka record that could
// not be claimed without conflating a durable retry/lease with an orphan from
// an earlier database generation. It never acknowledges mutable or ambiguous
// state. Only a record with no source outbox, job, current generation, inbox,
// retry, checkpoint, or quarantine evidence is terminalized as a sanitized
// DLT in the same transaction as its inbox outcome.
func (repository *Repository) ResolveProcessingClaimConflict(
	ctx context.Context,
	message ProcessingMessage,
) (ProcessingConflictResolution, error) {
	if !validProcessingMessage(message) {
		return ProcessingConflictResolution{}, ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return ProcessingConflictResolution{}, err
	}
	defer tx.Rollback(ctx)

	var databaseNow time.Time
	if err := tx.QueryRow(ctx, `select clock_timestamp()`).Scan(&databaseNow); err != nil {
		return ProcessingConflictResolution{}, err
	}

	var inboxHash, inboxOutcome string
	err = tx.QueryRow(ctx, `select body_sha256,outcome from media_processing_inbox
		where consumer_name=$1 and event_id=$2 for update`, processingConsumer,
		message.EventID).Scan(&inboxHash, &inboxOutcome)
	if err == nil {
		if inboxHash != message.BodySHA256 {
			return ProcessingConflictResolution{}, ErrIdempotencyMismatch
		}
		if inboxOutcome != "APPLIED" && inboxOutcome != "DLT" && inboxOutcome != "QUARANTINED" {
			return ProcessingConflictResolution{}, ErrConflict
		}
		if err := tx.Commit(ctx); err != nil {
			return ProcessingConflictResolution{}, err
		}
		return ProcessingConflictResolution{Disposition: ProcessingConflictTerminalConflict}, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return ProcessingConflictResolution{}, err
	}

	var retryHash string
	var retryAt time.Time
	err = tx.QueryRow(ctx, `select body_sha256,available_at from media_retry_schedule
		where consumer_name=$1 and event_id=$2 for update`, processingConsumer,
		message.EventID).Scan(&retryHash, &retryAt)
	if err == nil {
		if retryHash != message.BodySHA256 {
			return ProcessingConflictResolution{}, ErrIdempotencyMismatch
		}
		return commitProcessingRetryResolution(ctx, tx, retryAt)
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return ProcessingConflictResolution{}, err
	}

	type sourceState struct {
		aggregateType, eventType, topic, bodySHA256, status, dependencyStatus string
		aggregateID, recordKey                                                uuid.UUID
		aggregateVersion                                                      int64
		nextAttemptAt                                                         time.Time
	}
	var source sourceState
	err = tx.QueryRow(ctx, `select source.aggregate_type,source.aggregate_id,
		source.aggregate_version,source.event_type,source.topic,source.record_key,
		source.envelope_sha256,source.event_status,source.next_attempt_at,
		coalesce(dependency.event_status,'')
		from media_transport_outbox source
		left join media_transport_outbox dependency
		  on dependency.event_id=source.depends_on_event_id
		where source.event_id=$1 for update of source`, message.EventID).Scan(
		&source.aggregateType, &source.aggregateID, &source.aggregateVersion,
		&source.eventType, &source.topic, &source.recordKey, &source.bodySHA256,
		&source.status, &source.nextAttemptAt, &source.dependencyStatus)
	sourceFound := err == nil
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return ProcessingConflictResolution{}, err
	}
	if sourceFound && (source.aggregateType != message.AggregateType ||
		source.aggregateID != message.AggregateID ||
		source.aggregateVersion != message.AggregateVersion ||
		source.eventType != message.EventType || source.topic != message.Topic ||
		source.recordKey != message.RecordKey || source.bodySHA256 != message.BodySHA256) {
		return ProcessingConflictResolution{}, ErrIdempotencyMismatch
	}

	var aggregateOutboxExists bool
	if err := tx.QueryRow(ctx, `select exists(select 1 from media_transport_outbox
		where aggregate_type=$1 and aggregate_id=$2 and event_type=$3)`,
		message.AggregateType, message.AggregateID, message.EventType).Scan(
		&aggregateOutboxExists); err != nil {
		return ProcessingConflictResolution{}, err
	}

	var jobStatus string
	var jobNextAttemptAt time.Time
	var leaseUntil *time.Time
	var exactJobState, available bool
	err = tx.QueryRow(ctx, `select job.job_status,job.next_attempt_at,job.lease_until,
		(job.media_id=$2 and asset.warehouse_id=$3 and asset.media_kind=$4
		 and job.processing_kind=$5 and job.generation=$6
		 and job.requested_rotation_degrees=$7 and job.source_version_id=$8
		 and asset.processing_status='PROCESSING' and asset.pending_generation=$6
		 and asset.pending_rotation_degrees=$7 and asset.source_version_id=$8),
		media_asset_is_available(asset.media_id)
		from media_processing_job job
		join media_asset asset on asset.media_id=job.media_id
		where job.processing_job_id=$1 for update of job,asset`, message.AggregateID,
		message.ExpectedMediaID, message.ExpectedWarehouseID, message.ExpectedKind,
		message.ExpectedProcessingKind, message.ExpectedGeneration,
		message.ExpectedRotation, message.ExpectedSourceVersionID).Scan(
		&jobStatus, &jobNextAttemptAt, &leaseUntil, &exactJobState, &available)
	jobFound := err == nil
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return ProcessingConflictResolution{}, err
	}

	var currentGenerationExists bool
	if err := tx.QueryRow(ctx, `select exists(select 1 from media_asset
		where media_id=$1 and warehouse_id=$2 and media_kind=$3
		  and source_version_id=$4
		  and ((processing_status='PROCESSING' and pending_generation=$5
		        and pending_rotation_degrees=$6)
		       or current_generation >= $5))`, message.ExpectedMediaID,
		message.ExpectedWarehouseID, message.ExpectedKind,
		message.ExpectedSourceVersionID, message.ExpectedGeneration,
		message.ExpectedRotation).Scan(&currentGenerationExists); err != nil {
		return ProcessingConflictResolution{}, err
	}

	var checkpointOrQuarantineExists bool
	if err := tx.QueryRow(ctx, `select
		exists(select 1 from media_consumer_aggregate_checkpoint
		  where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3)
		or exists(select 1 from media_quarantined_aggregate
		  where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3
		    and reconciled_at is null)`, processingConsumer, message.AggregateType,
		message.AggregateID).Scan(&checkpointOrQuarantineExists); err != nil {
		return ProcessingConflictResolution{}, err
	}

	if !sourceFound && !aggregateOutboxExists && !jobFound &&
		!currentGenerationExists && !checkpointOrQuarantineExists {
		if err := repository.terminalizeOrphanProcessingMessage(ctx, tx, message); err != nil {
			return ProcessingConflictResolution{}, err
		}
		if err := tx.Commit(ctx); err != nil {
			return ProcessingConflictResolution{}, err
		}
		return ProcessingConflictResolution{Disposition: ProcessingConflictTerminalConflict}, nil
	}

	if jobFound && exactJobState && available {
		switch jobStatus {
		case "PENDING":
			if jobNextAttemptAt.After(databaseNow) {
				return commitProcessingRetryResolution(ctx, tx, jobNextAttemptAt)
			}
		case "RUNNING":
			if leaseUntil != nil && leaseUntil.After(databaseNow) {
				return commitProcessingRetryResolution(ctx, tx, *leaseUntil)
			}
		}
	}
	if sourceFound && (source.status == "PENDING" ||
		(source.status == "PUBLISHING" && source.dependencyStatus != "PUBLISHED")) &&
		source.nextAttemptAt.After(databaseNow) {
		return commitProcessingRetryResolution(ctx, tx, source.nextAttemptAt)
	}
	return commitProcessingRetryResolution(ctx, tx, databaseNow.Add(time.Second))
}

func validProcessingMessage(message ProcessingMessage) bool {
	return message.EventID != uuid.Nil && message.AggregateType == "PROCESSING_JOB" &&
		message.AggregateID != uuid.Nil && message.AggregateVersion > 0 &&
		message.RecordKey == message.AggregateID &&
		message.EventType == "media.processing.request.v1" &&
		message.Topic == ProcessingTopic && validSHA256(message.BodySHA256) &&
		message.CorrelationID != uuid.Nil && message.ExpectedMediaID != uuid.Nil &&
		message.ExpectedWarehouseID != uuid.Nil &&
		(message.ExpectedKind == media.KindImage || message.ExpectedKind == media.KindVideo) &&
		(message.ExpectedProcessingKind == media.ProcessingInitial ||
			message.ExpectedProcessingKind == media.ProcessingRotation) &&
		message.ExpectedGeneration > 0 &&
		(message.ExpectedRotation == media.Rotation0 || message.ExpectedRotation == media.Rotation90 ||
			message.ExpectedRotation == media.Rotation180 || message.ExpectedRotation == media.Rotation270) &&
		strings.TrimSpace(message.ExpectedSourceVersionID) != "" &&
		len(message.ExpectedSourceVersionID) <= 255
}

func commitProcessingRetryResolution(
	ctx context.Context,
	tx pgx.Tx,
	retryAt time.Time,
) (ProcessingConflictResolution, error) {
	if err := tx.Commit(ctx); err != nil {
		return ProcessingConflictResolution{}, err
	}
	return ProcessingConflictResolution{
		Disposition: ProcessingConflictRetryAt,
		RetryAt:     retryAt,
	}, nil
}

func (repository *Repository) terminalizeOrphanProcessingMessage(
	ctx context.Context,
	tx pgx.Tx,
	message ProcessingMessage,
) error {
	const failureCode = "VALIDATION_FAILED"
	if err := finishProcessingMessage(ctx, tx, message, "DLT"); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,aggregate_type,aggregate_id,aggregate_version,
		body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,$4,$5,$6,$7,1)`, processingConsumer, message.EventID,
		message.AggregateType, message.AggregateID, message.AggregateVersion,
		message.BodySHA256, failureCode)
	if err != nil {
		return err
	}
	return repository.insertProcessingDLTOutbox(ctx, tx, message, failureCode)
}

func (repository *Repository) CompleteProcessingJob(ctx context.Context, job WorkerJob, variants []media.ProcessedVariant) error {
	if err := validateProcessedVariants(job, variants); err != nil {
		return err
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	asset, err := lockWorkerState(ctx, tx, job)
	if err != nil {
		return err
	}
	for _, variant := range variants {
		var width, height any
		if variant.Width > 0 && variant.Height > 0 {
			width, height = variant.Width, variant.Height
		}
		command, insertErr := tx.Exec(ctx, `insert into media_variant (
			media_id,generation,variant,object_key,object_version_id,content_type,
			size_bytes,width,height,checksum_sha256)
		select $1,$2,$3,$4,$5,$6,$7,$8,$9,$10
		where media_asset_is_available($1)`,
			job.MediaID, job.Generation, variant.Variant, variant.ObjectKey,
			variant.ObjectVersionID, variant.ContentType, variant.SizeBytes, width, height,
			variant.ChecksumSHA256)
		if insertErr != nil {
			return translateConstraint(insertErr)
		}
		if command.RowsAffected() != 1 {
			return ErrConflict
		}
	}
	command, err := tx.Exec(ctx, `update media_processing_job set job_status='COMPLETED',
		completed_at=clock_timestamp(),lease_owner=null,lease_token=null,lease_until=null,last_error=null
		where processing_job_id=$1 and job_status='RUNNING' and lease_token=$2 and lease_fence=$3`,
		job.JobID, job.LeaseToken, job.LeaseFence)
	if err != nil || command.RowsAffected() != 1 {
		return ErrLeaseLost
	}
	var version int64
	err = tx.QueryRow(ctx, `update media_asset as a set processing_status='READY',
		current_generation=$2,rotation_degrees=$3,pending_generation=null,pending_rotation_degrees=null,
		processing_error=null,version=version+1,updated_at=clock_timestamp()
		where a.media_id=$1 and version=$4 and processing_status='PROCESSING' and pending_generation=$2
		  and media_asset_is_available(a.media_id)
		returning version`, job.MediaID, job.Generation, job.Rotation, asset.Version).Scan(&version)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrLeaseLost
	}
	if err != nil {
		return err
	}
	asset.Version = version
	asset.Status = media.StatusReady
	asset.Generation = job.Generation
	asset.Rotation = job.Rotation
	eventType := "media.media.ready.v1"
	if job.ProcessingKind == media.ProcessingRotation {
		eventType = "media.media.rotated.v1"
	}
	if err := repository.appendSystemFact(ctx, tx, job, asset, eventType); err != nil {
		return err
	}
	if err := finishProcessingMessage(ctx, tx, job.ProcessingMessage, "APPLIED"); err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `delete from media_retry_schedule where consumer_name=$1 and event_id=$2`, processingConsumer, job.EventID)
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func validateProcessedVariants(job WorkerJob, variants []media.ProcessedVariant) error {
	wanted := map[media.Variant]string{}
	switch job.MediaKind {
	case media.KindImage:
		wanted = map[media.Variant]string{
			media.VariantOriginal: media.OriginalObjectKey(job.MediaID.String(), job.Generation, ".jpg"),
			media.VariantSmall:    media.ImageVariantObjectKey(job.MediaID.String(), job.Generation, media.VariantSmall),
			media.VariantMedium:   media.ImageVariantObjectKey(job.MediaID.String(), job.Generation, media.VariantMedium),
			media.VariantLarge:    media.ImageVariantObjectKey(job.MediaID.String(), job.Generation, media.VariantLarge),
		}
	case media.KindVideo:
		extension := map[string]string{"video/mp4": ".mp4", "video/webm": ".webm"}[job.ContentType]
		if extension == "" {
			return ErrConflict
		}
		wanted[media.VariantOriginal] = media.OriginalObjectKey(job.MediaID.String(), job.Generation, extension)
	default:
		return ErrConflict
	}
	if len(variants) != len(wanted) {
		return ErrConflict
	}
	seen := make(map[media.Variant]struct{}, len(variants))
	for _, variant := range variants {
		expectedKey, expected := wanted[variant.Variant]
		if !expected || variant.ObjectKey != expectedKey || variant.ObjectVersionID == "" ||
			len(variant.ObjectVersionID) > 255 || variant.SizeBytes <= 0 || !validSHA256(variant.ChecksumSHA256) {
			return ErrConflict
		}
		if _, duplicate := seen[variant.Variant]; duplicate {
			return ErrConflict
		}
		seen[variant.Variant] = struct{}{}
		if job.MediaKind == media.KindImage {
			expectedContentType := "image/webp"
			if variant.Variant == media.VariantOriginal {
				expectedContentType = "image/jpeg"
			}
			if variant.ContentType != expectedContentType || variant.Width <= 0 || variant.Height <= 0 {
				return ErrConflict
			}
		} else if variant.ContentType != job.ContentType || variant.Width != 0 || variant.Height != 0 {
			return ErrConflict
		}
	}
	return nil
}

// RecordProcessingFailure releases a fenced attempt. Transient failures use
// exactly 1s/2s/4s before the fourth delivery becomes terminal. A validation
// failure is terminal immediately and retains its actual delivery-attempt count.
func (repository *Repository) RecordProcessingFailure(ctx context.Context, job WorkerJob, failureCode string) (time.Duration, bool, error) {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return 0, false, err
	}
	defer tx.Rollback(ctx)
	asset, err := lockWorkerState(ctx, tx, job)
	if err != nil {
		return 0, false, err
	}
	if failureCode != "VALIDATION_FAILED" && job.Attempt <= 3 {
		delay := time.Second * time.Duration(1<<(job.Attempt-1))
		command, err := tx.Exec(ctx, `update media_processing_job set job_status='PENDING',
			next_attempt_at=clock_timestamp()+$4::interval,lease_owner=null,lease_token=null,
			lease_until=null,last_error=$5
			where processing_job_id=$1 and job_status='RUNNING' and lease_token=$2 and lease_fence=$3`,
			job.JobID, job.LeaseToken, job.LeaseFence, delay.String(), failureCode)
		if err != nil || command.RowsAffected() != 1 {
			return 0, false, ErrLeaseLost
		}
		_, err = tx.Exec(ctx, `insert into media_retry_schedule (
			consumer_name,event_id,body_sha256,attempt,available_at,last_error_code)
		values ($1,$2,$3,$4,clock_timestamp()+$5::interval,$6)
		on conflict (consumer_name,event_id) do update set attempt=excluded.attempt,
			available_at=excluded.available_at,last_error_code=excluded.last_error_code`,
			processingConsumer, job.EventID, job.BodySHA256, job.Attempt, delay.String(), failureCode)
		if err != nil {
			return 0, false, err
		}
		return delay, false, tx.Commit(ctx)
	}
	command, err := tx.Exec(ctx, `update media_processing_job set job_status='FAILED',
		completed_at=clock_timestamp(),lease_owner=null,lease_token=null,lease_until=null,last_error=$4
		where processing_job_id=$1 and job_status='RUNNING' and lease_token=$2 and lease_fence=$3`,
		job.JobID, job.LeaseToken, job.LeaseFence, failureCode)
	if err != nil || command.RowsAffected() != 1 {
		return 0, false, ErrLeaseLost
	}
	status := media.StatusFailed
	if asset.Generation > 0 {
		status = media.StatusReady
	}
	var version int64
	err = tx.QueryRow(ctx, `update media_asset as a set processing_status=$2,
		pending_generation=null,pending_rotation_degrees=null,processing_error=$3,
		version=version+1,updated_at=clock_timestamp()
		where a.media_id=$1 and version=$4 and processing_status='PROCESSING' and pending_generation=$5
		  and media_asset_is_available(a.media_id)
		returning version`, job.MediaID, status, failureCode, asset.Version, job.Generation).Scan(&version)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, ErrLeaseLost
	}
	if err != nil {
		return 0, false, err
	}
	asset.Version, asset.Status = version, status
	if err := repository.appendSystemFact(ctx, tx, job, asset, "media.media.failed.v1"); err != nil {
		return 0, false, err
	}
	if err := finishProcessingMessage(ctx, tx, job.ProcessingMessage, "DLT"); err != nil {
		return 0, false, err
	}
	_, err = tx.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,aggregate_type,aggregate_id,aggregate_version,
		body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,$4,$5,$6,$7,$8)
	on conflict (consumer_name,event_id) do nothing`, processingConsumer, job.EventID,
		job.AggregateType, job.AggregateID, job.AggregateVersion, job.BodySHA256, failureCode,
		min(max(job.Attempt, 1), 4))
	if err != nil {
		return 0, false, err
	}
	if err := repository.insertProcessingDLTOutbox(ctx, tx, job.ProcessingMessage, failureCode); err != nil {
		return 0, false, err
	}
	_, err = tx.Exec(ctx, `delete from media_retry_schedule where consumer_name=$1 and event_id=$2`, processingConsumer, job.EventID)
	if err != nil {
		return 0, false, err
	}
	return 0, true, tx.Commit(ctx)
}

func (repository *Repository) RecordInvalidProcessingMessage(ctx context.Context, message ProcessingMessage, failureCode string) error {
	tx, err := repository.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	_, err = tx.Exec(ctx, `insert into media_processing_inbox (
		consumer_name,event_id,topic,event_type,aggregate_type,aggregate_id,
		aggregate_version,record_key,body_sha256,outcome)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,'DLT')
	on conflict (consumer_name,event_id) do nothing`, processingConsumer, message.EventID,
		message.Topic, message.EventType, message.AggregateType, message.AggregateID,
		message.AggregateVersion, message.RecordKey, message.BodySHA256)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,aggregate_type,aggregate_id,aggregate_version,
		body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,$4,$5,$6,$7,0)
	on conflict (consumer_name,event_id) do nothing`, processingConsumer, message.EventID,
		message.AggregateType, message.AggregateID, message.AggregateVersion,
		message.BodySHA256, failureCode)
	if err != nil {
		return err
	}
	if err := repository.insertProcessingDLTOutbox(ctx, tx, message, failureCode); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (repository *Repository) insertProcessingDLTOutbox(
	ctx context.Context,
	tx pgx.Tx,
	message ProcessingMessage,
	failureCode string,
) error {
	recordedAt := repository.now().UTC()
	dltID := uuid.NewSHA1(uuid.NameSpaceOID, []byte("media-processing-dlt:"+message.EventID.String()+":"+failureCode))
	body, checksum, err := canonicalJSON(map[string]any{
		"failureCode": failureCode, "messageSha256": message.BodySHA256,
		"recordedAt": recordedAt,
	})
	if err != nil {
		return err
	}
	var dependency *uuid.UUID
	var sourceEventID uuid.UUID
	if err := tx.QueryRow(ctx, `select event_id from media_transport_outbox
		where event_id=$1 and event_status='PUBLISHED'`, message.EventID).Scan(&sourceEventID); err == nil {
		dependency = &sourceEventID
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	_, err = tx.Exec(ctx, `insert into media_transport_outbox (
		event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,record_key,
		publication_ordinal,depends_on_event_id,envelope_body,wire_body,envelope_sha256,recorded_at)
	values ($1,'PROCESSING_DLT',$2,1,'media.processing.dlt.v1',$3,$2,1,$4,$5::jsonb,$6,$7,$8)
	on conflict (event_id) do nothing`, dltID, message.AggregateID, ProcessingDLTTopic,
		dependency, string(body), body, checksum, recordedAt)
	return translateConstraint(err)
}

func (repository *Repository) ReleaseProcessingLeases(ctx context.Context, owner string) error {
	_, err := repository.pool.Exec(ctx, `update media_processing_job set job_status='PENDING',
		lease_owner=null,lease_token=null,lease_until=null
		where job_status='RUNNING' and lease_owner=$1
		  and exists (select 1 from media_asset a where a.media_id=media_processing_job.media_id
		      and media_asset_is_available(a.media_id))`, owner)
	return err
}

func lockWorkerState(ctx context.Context, tx pgx.Tx, job WorkerJob) (AssetRecord, error) {
	var asset AssetRecord
	err := tx.QueryRow(ctx, assetSQL+` join media_processing_job job on job.media_id=a.media_id
		where job.processing_job_id=$1 and job.job_status='RUNNING' and job.lease_token=$2
		  and job.lease_fence=$3 and a.processing_status='PROCESSING'
		  and a.pending_generation=job.generation and media_asset_is_available(a.media_id)
		for update of a,job`,
		job.JobID, job.LeaseToken, job.LeaseFence).Scan(
		&asset.ID, &asset.FolderID, &asset.OwnerType, &asset.OwnerID, &asset.WarehouseID, &asset.Kind,
		&asset.FileName, &asset.ContentType, &asset.SourceObjectKey,
		&asset.SourceVersionID, &asset.SourceETag, &asset.SourceChecksum,
		&asset.Status, &asset.Version, &asset.Generation, &asset.Rotation,
		&asset.SortOrder, &asset.SizeBytes, &asset.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return AssetRecord{}, ErrLeaseLost
	}
	return asset, err
}

func (repository *Repository) appendSystemFact(ctx context.Context, tx pgx.Tx, job WorkerJob, asset AssetRecord, eventType string) error {
	eventID := uuid.New()
	recordedAt := repository.now().UTC()
	streamVersion, err := advanceDomainStream(ctx, tx, asset.ID, recordedAt)
	if err != nil {
		return err
	}
	if err := insertDomainEvent(ctx, tx, eventID, asset.ID, streamVersion, eventType, nil,
		job.CorrelationID, recordedAt); err != nil {
		return err
	}
	return insertFactOutbox(ctx, tx, eventID, asset, asset.Version, eventType, nil,
		job.CorrelationID, recordedAt, job.Generation, job.Rotation, nil)
}

func finishProcessingMessage(ctx context.Context, tx pgx.Tx, message ProcessingMessage, outcome string) error {
	_, err := tx.Exec(ctx, `insert into media_processing_inbox (
		consumer_name,event_id,topic,event_type,aggregate_type,aggregate_id,
		aggregate_version,record_key,body_sha256,outcome)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, processingConsumer, message.EventID,
		message.Topic, message.EventType, message.AggregateType, message.AggregateID,
		message.AggregateVersion, message.RecordKey, message.BodySHA256, outcome)
	if err != nil {
		return translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `insert into media_consumer_aggregate_checkpoint (
		consumer_name,aggregate_type,aggregate_id,aggregate_version)
	values ($1,$2,$3,$4)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update
	set aggregate_version=excluded.aggregate_version,updated_at=clock_timestamp()`,
		processingConsumer, message.AggregateType, message.AggregateID, message.AggregateVersion)
	return err
}

func quarantineProcessingGap(ctx context.Context, tx pgx.Tx, message ProcessingMessage, expected int64) error {
	_, err := tx.Exec(ctx, `insert into media_quarantined_aggregate (
		consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,
		reason_code,first_event_id)
	values ($1,$2,$3,$4,$5,'VERSION_GAP',$6)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update
	set expected_version=excluded.expected_version,observed_version=excluded.observed_version,
		reason_code=excluded.reason_code,reconciled_at=null`, processingConsumer,
		message.AggregateType, message.AggregateID, expected, message.AggregateVersion, message.EventID)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `insert into media_processing_inbox (
		consumer_name,event_id,topic,event_type,aggregate_type,aggregate_id,
		aggregate_version,record_key,body_sha256,outcome)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,'QUARANTINED')
	on conflict (consumer_name,event_id) do nothing`, processingConsumer, message.EventID,
		message.Topic, message.EventType, message.AggregateType, message.AggregateID,
		message.AggregateVersion, message.RecordKey, message.BodySHA256)
	return err
}
