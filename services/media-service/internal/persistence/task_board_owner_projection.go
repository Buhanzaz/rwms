package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// TaskBoardEntryOwnerProofMessage is the validated, canonical form of the
// task-board entry-owner-proof Kafka record. Worker IDs and source references
// are copied only after strict envelope parsing by worker/task_board_owner_consumer.
type TaskBoardEntryOwnerProofMessage struct {
	EventID          uuid.UUID
	BodySHA256       string
	WireBody         []byte
	Topic            string
	EventType        string
	AggregateType    string
	AggregateID      uuid.UUID
	AggregateVersion int64
	RecordKey        uuid.UUID
	RecordedAt       time.Time
	WarehouseID      uuid.UUID
	RouteIndex       int
	Active           bool
	AllowedWorkerIDs []uuid.UUID
	ReaderWorkerIDs  []uuid.UUID
	SourceMediaRefs  []TaskBoardSourceMediaReference
}

// TaskBoardSourceMediaReference fences a source asset to the exact generation
// task-board authorizes a worker to read.
type TaskBoardSourceMediaReference struct {
	MediaID    uuid.UUID
	Generation int
}

// TaskBoardEntryOwnerProofApplyResult reports an exact replay or quarantine
// outcome after one task-board owner proof is applied.
type TaskBoardEntryOwnerProofApplyResult struct {
	Duplicate   bool
	Quarantined bool
}

type taskBoardOwnerProofReceipt struct {
	EventID          uuid.UUID
	AggregateID      uuid.UUID
	AggregateVersion int64
	RecordKey        uuid.UUID
	BodySHA256       string
	Outcome          string
}

// ApplyTaskBoardEntryOwnerProofMessage advances exactly one aggregate stream
// transactionally. Any gap, changed event-id reuse, or version reuse deactivates
// the binding and opens the same generic quarantine queried by all public media
// reads and mutations.
func (repository *Repository) ApplyTaskBoardEntryOwnerProofMessage(
	ctx context.Context,
	message TaskBoardEntryOwnerProofMessage,
) (TaskBoardEntryOwnerProofApplyResult, error) {
	if err := validateTaskBoardEntryOwnerProofMessage(message); err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	defer tx.Rollback(ctx)
	result, err := repository.applyTaskBoardEntryOwnerProofMessage(ctx, tx, message)
	if err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	return result, nil
}

func (repository *Repository) applyTaskBoardEntryOwnerProofMessage(
	ctx context.Context,
	tx pgx.Tx,
	message TaskBoardEntryOwnerProofMessage,
) (TaskBoardEntryOwnerProofApplyResult, error) {
	if err := lockTaskBoardEntryOwnerProof(ctx, tx, message.EventID, message.AggregateID); err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}

	prior, found, err := readTaskBoardOwnerProofReceipt(ctx, tx, message.EventID)
	if err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	if found {
		if prior.BodySHA256 == message.BodySHA256 && prior.AggregateID == message.AggregateID &&
			prior.AggregateVersion == message.AggregateVersion && prior.RecordKey == message.RecordKey {
			return TaskBoardEntryOwnerProofApplyResult{
				Duplicate: true, Quarantined: prior.Outcome == "QUARANTINED",
			}, nil
		}
		if err := repository.recordTaskBoardOwnerProofConflict(ctx, tx, message, &prior,
			"EVENT_ID_CONFLICT"); err != nil {
			return TaskBoardEntryOwnerProofApplyResult{}, err
		}
		if err := repository.quarantineTaskBoardOwnerAggregate(ctx, tx, prior.AggregateID,
			prior.AggregateVersion, prior.AggregateVersion, "EVENT_ID_CONFLICT", prior.EventID); err != nil {
			return TaskBoardEntryOwnerProofApplyResult{}, err
		}
		if err := repository.quarantineTaskBoardOwnerAggregate(ctx, tx, message.AggregateID,
			message.AggregateVersion, message.AggregateVersion, "EVENT_ID_CONFLICT", message.EventID); err != nil {
			return TaskBoardEntryOwnerProofApplyResult{}, err
		}
		if err := insertTaskBoardOwnerProofDeadLetter(ctx, tx, message.EventID, message.BodySHA256,
			"TASK_BOARD_OWNER_EVENT_ID_CONFLICT"); err != nil {
			return TaskBoardEntryOwnerProofApplyResult{}, err
		}
		return TaskBoardEntryOwnerProofApplyResult{Quarantined: true}, nil
	}

	versionReceipt, versionFound, err := readTaskBoardOwnerProofVersion(ctx, tx,
		message.AggregateID, message.AggregateVersion)
	if err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	if versionFound {
		if err := repository.recordTaskBoardOwnerProofConflict(ctx, tx, message, &versionReceipt,
			"AGGREGATE_VERSION_CONFLICT"); err != nil {
			return TaskBoardEntryOwnerProofApplyResult{}, err
		}
		if err := repository.quarantineTaskBoardOwnerAggregate(ctx, tx, message.AggregateID,
			message.AggregateVersion, message.AggregateVersion, "AGGREGATE_VERSION_CONFLICT", message.EventID); err != nil {
			return TaskBoardEntryOwnerProofApplyResult{}, err
		}
		// The existing receipt owns the unique aggregate-version slot. The
		// competing wire body is retained in the conflict ledger; attempting a
		// second inbox row would fail and leave the authorization binding active.
		return TaskBoardEntryOwnerProofApplyResult{Quarantined: true}, nil
	}

	var quarantined bool
	if err := tx.QueryRow(ctx, `select exists(select 1 from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3
		and reconciled_at is null)`, TaskBoardEntryOwnerProofConsumer,
		TaskBoardEntryOwnerProofAggregate, message.AggregateID).Scan(&quarantined); err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	if quarantined {
		if err := insertTaskBoardOwnerProofInbox(ctx, tx, message, "QUARANTINED",
			"AGGREGATE_ALREADY_QUARANTINED"); err != nil {
			return TaskBoardEntryOwnerProofApplyResult{}, err
		}
		return TaskBoardEntryOwnerProofApplyResult{Quarantined: true}, nil
	}

	checkpoint, checkpointFound, err := taskBoardOwnerProofCheckpoint(ctx, tx, message.AggregateID)
	if err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	expected := int64(0)
	if checkpointFound {
		expected = checkpoint + 1
	}
	if message.AggregateVersion != expected {
		reason := "VERSION_GAP"
		if message.AggregateVersion < expected {
			reason = "AGGREGATE_VERSION_REGRESSION"
		}
		if err := repository.quarantineTaskBoardOwnerProof(ctx, tx, message, expected, reason); err != nil {
			return TaskBoardEntryOwnerProofApplyResult{}, err
		}
		return TaskBoardEntryOwnerProofApplyResult{Quarantined: true}, nil
	}

	if err := applyTaskBoardOwnerProof(ctx, tx, message); err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	if err := insertTaskBoardOwnerProofInbox(ctx, tx, message, "APPLIED", ""); err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	if err := advanceTaskBoardOwnerProofCheckpoint(ctx, tx, message); err != nil {
		return TaskBoardEntryOwnerProofApplyResult{}, err
	}
	return TaskBoardEntryOwnerProofApplyResult{}, nil
}

func validateTaskBoardEntryOwnerProofMessage(message TaskBoardEntryOwnerProofMessage) error {
	if message.EventID == uuid.Nil || message.AggregateID == uuid.Nil || message.RecordKey == uuid.Nil ||
		message.WarehouseID == uuid.Nil || message.AggregateID != message.RecordKey ||
		message.Topic != TaskBoardEntryOwnerProofTopic ||
		message.EventType != "task-board.entry-owner-proof.changed.v1" ||
		message.AggregateType != TaskBoardEntryOwnerProofAggregate ||
		message.AggregateVersion < 0 || message.RouteIndex < 0 || message.RecordedAt.IsZero() ||
		len(message.WireBody) == 0 || len(message.WireBody) > 1<<20 || !validSHA256(message.BodySHA256) {
		return ErrConflict
	}
	sum := sha256.Sum256(message.WireBody)
	if hex.EncodeToString(sum[:]) != message.BodySHA256 {
		return ErrIdempotencyMismatch
	}
	if len(message.AllowedWorkerIDs) > 1000 || len(message.ReaderWorkerIDs) > 1000 ||
		len(message.SourceMediaRefs) > 1000 {
		return ErrConflict
	}
	workers := make(map[uuid.UUID]struct{}, len(message.AllowedWorkerIDs))
	for _, workerID := range message.AllowedWorkerIDs {
		if workerID == uuid.Nil {
			return ErrConflict
		}
		if _, duplicate := workers[workerID]; duplicate {
			return ErrConflict
		}
		workers[workerID] = struct{}{}
	}
	readers := make(map[uuid.UUID]struct{}, len(message.ReaderWorkerIDs))
	for _, workerID := range message.ReaderWorkerIDs {
		if workerID == uuid.Nil {
			return ErrConflict
		}
		if _, duplicate := readers[workerID]; duplicate {
			return ErrConflict
		}
		readers[workerID] = struct{}{}
	}
	references := make(map[TaskBoardSourceMediaReference]struct{}, len(message.SourceMediaRefs))
	for _, reference := range message.SourceMediaRefs {
		if reference.MediaID == uuid.Nil || reference.Generation <= 0 {
			return ErrConflict
		}
		if _, duplicate := references[reference]; duplicate {
			return ErrConflict
		}
		references[reference] = struct{}{}
	}
	return nil
}

func lockTaskBoardEntryOwnerProof(ctx context.Context, tx pgx.Tx, eventID, entryID uuid.UUID) error {
	locks := []string{
		"task-board-entry-owner-proof:event:" + eventID.String(),
		"task-board-entry-owner-proof:entry:" + entryID.String(),
	}
	sort.Strings(locks)
	for _, lock := range locks {
		if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`, lock); err != nil {
			return err
		}
	}
	return nil
}

func readTaskBoardOwnerProofReceipt(ctx context.Context, tx pgx.Tx, eventID uuid.UUID) (taskBoardOwnerProofReceipt, bool, error) {
	var receipt taskBoardOwnerProofReceipt
	err := tx.QueryRow(ctx, `select event_id,aggregate_id,aggregate_version,record_key,body_sha256,outcome
		from media_task_board_entry_owner_proof_inbox
		where consumer_name=$1 and event_id=$2 for update`, TaskBoardEntryOwnerProofConsumer, eventID).
		Scan(&receipt.EventID, &receipt.AggregateID, &receipt.AggregateVersion, &receipt.RecordKey,
			&receipt.BodySHA256, &receipt.Outcome)
	if errors.Is(err, pgx.ErrNoRows) {
		return taskBoardOwnerProofReceipt{}, false, nil
	}
	return receipt, err == nil, err
}

func readTaskBoardOwnerProofVersion(ctx context.Context, tx pgx.Tx, entryID uuid.UUID, version int64) (taskBoardOwnerProofReceipt, bool, error) {
	var receipt taskBoardOwnerProofReceipt
	err := tx.QueryRow(ctx, `select event_id,aggregate_id,aggregate_version,record_key,body_sha256,outcome
		from media_task_board_entry_owner_proof_inbox
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3 and aggregate_version=$4
		for update`, TaskBoardEntryOwnerProofConsumer, TaskBoardEntryOwnerProofAggregate, entryID, version).
		Scan(&receipt.EventID, &receipt.AggregateID, &receipt.AggregateVersion, &receipt.RecordKey,
			&receipt.BodySHA256, &receipt.Outcome)
	if errors.Is(err, pgx.ErrNoRows) {
		return taskBoardOwnerProofReceipt{}, false, nil
	}
	return receipt, err == nil, err
}

func taskBoardOwnerProofCheckpoint(ctx context.Context, tx pgx.Tx, entryID uuid.UUID) (int64, bool, error) {
	var version int64
	err := tx.QueryRow(ctx, `select aggregate_version from media_consumer_aggregate_checkpoint
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3 for update`,
		TaskBoardEntryOwnerProofConsumer, TaskBoardEntryOwnerProofAggregate, entryID).Scan(&version)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	return version, err == nil, err
}

func applyTaskBoardOwnerProof(ctx context.Context, tx pgx.Tx, message TaskBoardEntryOwnerProofMessage) error {
	now := message.RecordedAt.UTC()
	_, err := tx.Exec(ctx, `insert into media_task_board_entry_owner_proof (
		entry_id,warehouse_id,route_index,active,aggregate_version,proof_event_id,body_sha256,
		quarantined,quarantine_reason,updated_at)
	values ($1,$2,$3,$4,$5,$6,$7,false,null,$8)
	on conflict (entry_id) do update set warehouse_id=excluded.warehouse_id,
		route_index=excluded.route_index,active=excluded.active,
		aggregate_version=excluded.aggregate_version,proof_event_id=excluded.proof_event_id,
		body_sha256=excluded.body_sha256,quarantined=false,quarantine_reason=null,
		updated_at=excluded.updated_at`, message.AggregateID, message.WarehouseID, message.RouteIndex,
		message.Active, message.AggregateVersion, message.EventID, message.BodySHA256, now)
	if err != nil {
		return translateConstraint(err)
	}
	if _, err := tx.Exec(ctx, `delete from media_task_board_entry_allowed_worker where entry_id=$1`, message.AggregateID); err != nil {
		return err
	}
	for _, workerID := range message.AllowedWorkerIDs {
		if _, err := tx.Exec(ctx, `insert into media_task_board_entry_allowed_worker (entry_id,worker_id)
				values ($1,$2)`, message.AggregateID, workerID); err != nil {
			return translateConstraint(err)
		}
	}
	if _, err := tx.Exec(ctx, `delete from media_task_board_entry_reader_worker where entry_id=$1`, message.AggregateID); err != nil {
		return err
	}
	for _, workerID := range message.ReaderWorkerIDs {
		if _, err := tx.Exec(ctx, `insert into media_task_board_entry_reader_worker (entry_id,worker_id)
				values ($1,$2)`, message.AggregateID, workerID); err != nil {
			return translateConstraint(err)
		}
	}
	if _, err := tx.Exec(ctx, `delete from media_task_board_entry_source_media_reference where entry_id=$1`, message.AggregateID); err != nil {
		return err
	}
	for _, reference := range message.SourceMediaRefs {
		if _, err := tx.Exec(ctx, `insert into media_task_board_entry_source_media_reference
			(entry_id,media_id,generation) values ($1,$2,$3)`, message.AggregateID,
			reference.MediaID, reference.Generation); err != nil {
			return translateConstraint(err)
		}
	}
	_, err = tx.Exec(ctx, `insert into media_owner_binding (
		owner_type,owner_id,warehouse_id,owner_revision,proof_event_id,proof_consumer_name,
		proof_aggregate_type,proof_aggregate_id,proof_aggregate_version,proof_recorded_at,active,updated_at)
	values ('TASK_BOARD_ENTRY',$1,$2,$3,$4,$5,$6,$7,$3,$8,$9,$8)
	on conflict (owner_type,owner_id) do update set warehouse_id=excluded.warehouse_id,
		owner_revision=excluded.owner_revision,proof_event_id=excluded.proof_event_id,
		proof_consumer_name=excluded.proof_consumer_name,
		proof_aggregate_type=excluded.proof_aggregate_type,
		proof_aggregate_id=excluded.proof_aggregate_id,
		proof_aggregate_version=excluded.proof_aggregate_version,
		proof_recorded_at=excluded.proof_recorded_at,active=excluded.active,
		updated_at=excluded.updated_at`, message.AggregateID.String(), message.WarehouseID,
		message.AggregateVersion, message.EventID, TaskBoardEntryOwnerProofConsumer,
		TaskBoardEntryOwnerProofAggregate, message.AggregateID, now, message.Active)
	return translateConstraint(err)
}

func advanceTaskBoardOwnerProofCheckpoint(ctx context.Context, tx pgx.Tx, message TaskBoardEntryOwnerProofMessage) error {
	_, err := tx.Exec(ctx, `insert into media_consumer_aggregate_checkpoint
		(consumer_name,aggregate_type,aggregate_id,aggregate_version,updated_at)
	values ($1,$2,$3,$4,$5)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		aggregate_version=excluded.aggregate_version,updated_at=excluded.updated_at`,
		TaskBoardEntryOwnerProofConsumer, TaskBoardEntryOwnerProofAggregate, message.AggregateID,
		message.AggregateVersion, message.RecordedAt.UTC())
	return translateConstraint(err)
}

func insertTaskBoardOwnerProofInbox(ctx context.Context, tx pgx.Tx, message TaskBoardEntryOwnerProofMessage, outcome, failureCode string) error {
	var failure any
	var processedAt any
	if outcome == "APPLIED" {
		processedAt = message.RecordedAt.UTC()
	} else {
		failure = failureCode
	}
	_, err := tx.Exec(ctx, `insert into media_task_board_entry_owner_proof_inbox (
		consumer_name,event_id,topic,event_type,aggregate_type,aggregate_id,aggregate_version,
		record_key,warehouse_id,route_index,active,body_sha256,wire_body,outcome,failure_code,
		processed_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)`,
		TaskBoardEntryOwnerProofConsumer, message.EventID, message.Topic, message.EventType,
		message.AggregateType, message.AggregateID, message.AggregateVersion, message.RecordKey,
		message.WarehouseID, message.RouteIndex, message.Active, message.BodySHA256, message.WireBody,
		outcome, failure, processedAt)
	return translateConstraint(err)
}

func (repository *Repository) quarantineTaskBoardOwnerProof(ctx context.Context, tx pgx.Tx, message TaskBoardEntryOwnerProofMessage, expected int64, reason string) error {
	if err := repository.quarantineTaskBoardOwnerAggregate(ctx, tx, message.AggregateID, expected,
		message.AggregateVersion, reason, message.EventID); err != nil {
		return err
	}
	return insertTaskBoardOwnerProofInbox(ctx, tx, message, "QUARANTINED", reason)
}

func (repository *Repository) quarantineTaskBoardOwnerAggregate(ctx context.Context, tx pgx.Tx, entryID uuid.UUID, expected, observed int64, reason string, eventID uuid.UUID) error {
	_, err := tx.Exec(ctx, `insert into media_quarantined_aggregate (
		consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,reason_code,first_event_id)
	values ($1,$2,$3,$4,$5,$6,$7)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		expected_version=excluded.expected_version,observed_version=excluded.observed_version,
		reason_code=excluded.reason_code,first_event_id=excluded.first_event_id,
		quarantined_at=clock_timestamp(),reconciled_at=null,resolution_reason=null,
		resolved_by_subject_id=null`, TaskBoardEntryOwnerProofConsumer,
		TaskBoardEntryOwnerProofAggregate, entryID, expected, observed, reason, eventID)
	if err != nil {
		return translateConstraint(err)
	}
	if _, err := tx.Exec(ctx, `update media_task_board_entry_owner_proof
		set active=false,quarantined=true,quarantine_reason=$2,updated_at=clock_timestamp()
		where entry_id=$1`, entryID, reason); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `update media_owner_binding set active=false,updated_at=clock_timestamp()
		where owner_type='TASK_BOARD_ENTRY' and owner_id=$1`, entryID.String()); err != nil {
		return err
	}
	return nil
}

func (repository *Repository) recordTaskBoardOwnerProofConflict(ctx context.Context, tx pgx.Tx, message TaskBoardEntryOwnerProofMessage, prior *taskBoardOwnerProofReceipt, failureCode string) error {
	var priorEventID, priorAggregateID, priorAggregateVersion, priorSHA any
	if prior != nil {
		priorEventID = prior.EventID
		priorAggregateID = prior.AggregateID
		priorAggregateVersion = prior.AggregateVersion
		priorSHA = prior.BodySHA256
	}
	_, err := tx.Exec(ctx, `insert into media_task_board_entry_owner_proof_conflict (
		consumer_name,event_id,prior_event_id,prior_aggregate_id,prior_aggregate_version,
		prior_body_sha256,incoming_aggregate_id,incoming_aggregate_version,incoming_record_key,
		incoming_body_sha256,incoming_wire_body,failure_code)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
	on conflict (consumer_name,event_id,incoming_body_sha256) do nothing`,
		TaskBoardEntryOwnerProofConsumer, message.EventID, priorEventID, priorAggregateID,
		priorAggregateVersion, priorSHA, message.AggregateID, message.AggregateVersion,
		message.RecordKey, message.BodySHA256, message.WireBody, failureCode)
	return translateConstraint(err)
}

// RecordTaskBoardEntryOwnerProofDLT persists the hash-only dead-letter result
// for an invalid task-board owner-proof record.
func (repository *Repository) RecordTaskBoardEntryOwnerProofDLT(ctx context.Context, bodySHA256 string) error {
	if !validSHA256(bodySHA256) {
		return ErrConflict
	}
	eventID := uuid.NewSHA1(uuid.NameSpaceOID, []byte("invalid-task-board-entry-owner-proof:"+bodySHA256))
	_, err := repository.pool.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,'INVALID_TASK_BOARD_ENTRY_OWNER_PROOF',0)
	on conflict (consumer_name,event_id) do nothing`, TaskBoardEntryOwnerProofConsumer, eventID, bodySHA256)
	return translateConstraint(err)
}

func insertTaskBoardOwnerProofDeadLetter(ctx context.Context, tx pgx.Tx, eventID uuid.UUID, bodySHA256, failureCode string) error {
	_, err := tx.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,aggregate_type,body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,$4,$5,1)
	on conflict (consumer_name,event_id) do nothing`, TaskBoardEntryOwnerProofConsumer, eventID,
		TaskBoardEntryOwnerProofAggregate, bodySHA256, failureCode)
	return translateConstraint(err)
}

// RequireTaskBoardEntryWorkerAccess confirms current worker authorization for a
// task-board entry in the requested warehouse. Command transactions and request
// handlers acquire the proof row before the binding and membership rows so a
// revocation cannot race a metadata or content callback and cannot invert the
// projection's lock order.
func RequireTaskBoardEntryWorkerAccess(ctx context.Context, database queryer, entryID, warehouseID, workerID uuid.UUID) error {
	if entryID == uuid.Nil || warehouseID == uuid.Nil || workerID == uuid.Nil {
		return ErrOwnerProofMissing
	}
	if err := lockTaskBoardEntryOwnerProofForAuthorization(ctx, database, entryID); err != nil {
		return err
	}
	var found string
	err := database.QueryRow(ctx, `select binding.owner_id
		from media_owner_binding binding
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		join media_task_board_entry_allowed_worker allowed
		  on allowed.entry_id=binding.proof_aggregate_id and allowed.worker_id=$3
		where binding.owner_type='TASK_BOARD_ENTRY' and binding.owner_id=$1
		  and binding.warehouse_id=$2 and binding.active
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=binding.proof_consumer_name
		      and quarantine.aggregate_type=binding.proof_aggregate_type
		      and quarantine.aggregate_id=binding.proof_aggregate_id
		      and quarantine.reconciled_at is null)
		for share of binding,allowed`, entryID.String(), warehouseID, workerID).Scan(&found)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrOwnerProofMissing
	}
	if err != nil {
		return err
	}
	return nil
}

// RequireTaskBoardEntryWorkerReadAccess keeps task photos readable to a worker
// named by the latest read audience while leaving command/upload access
// gated by RequireTaskBoardEntryWorkerAccess and the proof's active flag.
func RequireTaskBoardEntryWorkerReadAccess(ctx context.Context, database queryer, entryID, warehouseID, workerID uuid.UUID) error {
	if entryID == uuid.Nil || warehouseID == uuid.Nil || workerID == uuid.Nil {
		return ErrOwnerProofMissing
	}
	if err := lockTaskBoardEntryOwnerProofForAuthorization(ctx, database, entryID); err != nil {
		return err
	}
	var found string
	err := database.QueryRow(ctx, `select binding.owner_id
		from media_owner_binding binding
		join media_task_board_entry_owner_proof proof
		  on proof.entry_id=binding.proof_aggregate_id
		 and proof.warehouse_id=binding.warehouse_id
		 and proof.aggregate_version=binding.proof_aggregate_version
		 and proof.proof_event_id=binding.proof_event_id
		 and not proof.quarantined
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		join media_task_board_entry_reader_worker reader
		  on reader.entry_id=binding.proof_aggregate_id and reader.worker_id=$3
		where binding.owner_type='TASK_BOARD_ENTRY' and binding.owner_id=$1
		  and binding.warehouse_id=$2
		  and binding.proof_consumer_name=$4
		  and binding.proof_aggregate_type=$5
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=binding.proof_consumer_name
		      and quarantine.aggregate_type=binding.proof_aggregate_type
		      and quarantine.aggregate_id=binding.proof_aggregate_id
		      and quarantine.reconciled_at is null)
		for share of binding,proof,reader`, entryID.String(), warehouseID, workerID,
		TaskBoardEntryOwnerProofConsumer, TaskBoardEntryOwnerProofAggregate).Scan(&found)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrOwnerProofMissing
	}
	if err != nil {
		return err
	}
	return nil
}

// RequireTaskBoardEntryUserReadAccess authorizes a warehouse reader to inspect
// a task-board entry's acceptance evidence. Completion deactivates the worker
// proof, but it does not revoke this read-only acceptance scope: the entry
// proof itself must still be current, warehouse-scoped and non-quarantined.
// The proof and binding rows remain share-locked for the caller's complete
// metadata or content callback so a concurrent proof replacement cannot race
// a source-reference read.
func RequireTaskBoardEntryUserReadAccess(ctx context.Context, database queryer, entryID, warehouseID uuid.UUID) error {
	if entryID == uuid.Nil || warehouseID == uuid.Nil {
		return ErrOwnerProofMissing
	}
	if err := lockTaskBoardEntryOwnerProofForAuthorization(ctx, database, entryID); err != nil {
		return err
	}
	var found string
	err := database.QueryRow(ctx, `select binding.owner_id
		from media_owner_binding binding
		join media_task_board_entry_owner_proof proof
		  on proof.entry_id=binding.proof_aggregate_id
		 and proof.warehouse_id=binding.warehouse_id
		 and proof.aggregate_version=binding.proof_aggregate_version
		 and proof.proof_event_id=binding.proof_event_id
		 and not proof.quarantined
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		where binding.owner_type='TASK_BOARD_ENTRY' and binding.owner_id=$1
		  and binding.warehouse_id=$2
		  and binding.proof_consumer_name=$3
		  and binding.proof_aggregate_type=$4
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=binding.proof_consumer_name
		      and quarantine.aggregate_type=binding.proof_aggregate_type
		      and quarantine.aggregate_id=binding.proof_aggregate_id
		      and quarantine.reconciled_at is null)
		for share of binding,proof`, entryID.String(), warehouseID,
		TaskBoardEntryOwnerProofConsumer, TaskBoardEntryOwnerProofAggregate).Scan(&found)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrOwnerProofMissing
	}
	if err != nil {
		return err
	}
	return nil
}

// lockTaskBoardEntryOwnerProofForAuthorization establishes the same leading
// row-lock order as task-board proof replacement. Authorization transactions
// therefore wait before locking binding or audience rows, avoiding a lock
// inversion while still fencing concurrent revocation for their full scope.
func lockTaskBoardEntryOwnerProofForAuthorization(
	ctx context.Context,
	database queryer,
	entryID uuid.UUID,
) error {
	var found uuid.UUID
	err := database.QueryRow(ctx, `select entry_id from media_task_board_entry_owner_proof
		where entry_id=$1 for share`, entryID).Scan(&found)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrOwnerProofMissing
	}
	return err
}

// AuthorizeTaskBoardEntryWorker applies the worker-access check through this
// repository's PostgreSQL pool.
func (repository *Repository) AuthorizeTaskBoardEntryWorker(ctx context.Context, entryID, warehouseID, workerID uuid.UUID) error {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireTaskBoardEntryWorkerAccess(ctx, tx, entryID, warehouseID, workerID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
