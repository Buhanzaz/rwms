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

// DriverShiftOwnerProofMessage is the validated canonical form of one
// task-board driver-shift owner-proof record.
type DriverShiftOwnerProofMessage struct {
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
	Active           bool
	AllowedWorkerIDs []uuid.UUID
	ReaderWorkerIDs  []uuid.UUID
}

// DriverShiftOwnerProofApplyResult reports exact replay and quarantine
// outcomes for one driver-shift proof application.
type DriverShiftOwnerProofApplyResult struct {
	Duplicate   bool
	Quarantined bool
}

// driverShiftOwnerProofReceipt is the durable identity used to distinguish an
// exact replay from event-ID or aggregate-version reuse.
type driverShiftOwnerProofReceipt struct {
	EventID          uuid.UUID
	AggregateID      uuid.UUID
	AggregateVersion int64
	RecordKey        uuid.UUID
	BodySHA256       string
	Outcome          string
}

// ApplyDriverShiftOwnerProofMessage advances one dedicated driver-shift proof
// stream transactionally and fails closed on gaps or identity conflicts.
func (repository *Repository) ApplyDriverShiftOwnerProofMessage(
	ctx context.Context,
	message DriverShiftOwnerProofMessage,
) (DriverShiftOwnerProofApplyResult, error) {
	if err := validateDriverShiftOwnerProofMessage(message); err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	defer tx.Rollback(ctx)
	result, err := repository.applyDriverShiftOwnerProofMessage(ctx, tx, message)
	if err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	return result, nil
}

func (repository *Repository) applyDriverShiftOwnerProofMessage(
	ctx context.Context,
	tx pgx.Tx,
	message DriverShiftOwnerProofMessage,
) (DriverShiftOwnerProofApplyResult, error) {
	if err := lockDriverShiftOwnerProof(ctx, tx, message.EventID, message.AggregateID); err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	prior, found, err := readDriverShiftOwnerProofReceipt(ctx, tx, message.EventID)
	if err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	if found {
		if prior.BodySHA256 == message.BodySHA256 && prior.AggregateID == message.AggregateID &&
			prior.AggregateVersion == message.AggregateVersion && prior.RecordKey == message.RecordKey {
			return DriverShiftOwnerProofApplyResult{
				Duplicate: true, Quarantined: prior.Outcome == "QUARANTINED",
			}, nil
		}
		if err := repository.recordDriverShiftOwnerProofConflict(ctx, tx, message, &prior,
			"EVENT_ID_CONFLICT"); err != nil {
			return DriverShiftOwnerProofApplyResult{}, err
		}
		if err := repository.quarantineDriverShiftOwnerAggregate(ctx, tx, prior.AggregateID,
			prior.AggregateVersion, prior.AggregateVersion, "EVENT_ID_CONFLICT", prior.EventID); err != nil {
			return DriverShiftOwnerProofApplyResult{}, err
		}
		if err := repository.quarantineDriverShiftOwnerAggregate(ctx, tx, message.AggregateID,
			message.AggregateVersion, message.AggregateVersion, "EVENT_ID_CONFLICT", message.EventID); err != nil {
			return DriverShiftOwnerProofApplyResult{}, err
		}
		if err := insertDriverShiftOwnerProofDeadLetter(ctx, tx, message.EventID, message.BodySHA256,
			"DRIVER_SHIFT_OWNER_EVENT_ID_CONFLICT"); err != nil {
			return DriverShiftOwnerProofApplyResult{}, err
		}
		return DriverShiftOwnerProofApplyResult{Quarantined: true}, nil
	}

	versionReceipt, versionFound, err := readDriverShiftOwnerProofVersion(ctx, tx,
		message.AggregateID, message.AggregateVersion)
	if err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	if versionFound {
		if err := repository.recordDriverShiftOwnerProofConflict(ctx, tx, message, &versionReceipt,
			"AGGREGATE_VERSION_CONFLICT"); err != nil {
			return DriverShiftOwnerProofApplyResult{}, err
		}
		if err := repository.quarantineDriverShiftOwnerAggregate(ctx, tx, message.AggregateID,
			message.AggregateVersion, message.AggregateVersion, "AGGREGATE_VERSION_CONFLICT", message.EventID); err != nil {
			return DriverShiftOwnerProofApplyResult{}, err
		}
		return DriverShiftOwnerProofApplyResult{Quarantined: true}, nil
	}

	var quarantined bool
	if err := tx.QueryRow(ctx, `select exists(select 1 from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3
		and reconciled_at is null)`, DriverShiftOwnerProofConsumer,
		DriverShiftOwnerProofAggregate, message.AggregateID).Scan(&quarantined); err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	if quarantined {
		if err := insertDriverShiftOwnerProofInbox(ctx, tx, message, "QUARANTINED",
			"AGGREGATE_ALREADY_QUARANTINED"); err != nil {
			return DriverShiftOwnerProofApplyResult{}, err
		}
		return DriverShiftOwnerProofApplyResult{Quarantined: true}, nil
	}

	checkpoint, checkpointFound, err := driverShiftOwnerProofCheckpoint(ctx, tx, message.AggregateID)
	if err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
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
		if err := repository.quarantineDriverShiftOwnerProof(ctx, tx, message, expected, reason); err != nil {
			return DriverShiftOwnerProofApplyResult{}, err
		}
		return DriverShiftOwnerProofApplyResult{Quarantined: true}, nil
	}

	if err := applyDriverShiftOwnerProof(ctx, tx, message); err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	if err := insertDriverShiftOwnerProofInbox(ctx, tx, message, "APPLIED", ""); err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	if err := advanceDriverShiftOwnerProofCheckpoint(ctx, tx, message); err != nil {
		return DriverShiftOwnerProofApplyResult{}, err
	}
	return DriverShiftOwnerProofApplyResult{}, nil
}

func validateDriverShiftOwnerProofMessage(message DriverShiftOwnerProofMessage) error {
	if message.EventID == uuid.Nil || message.AggregateID == uuid.Nil || message.RecordKey == uuid.Nil ||
		message.WarehouseID == uuid.Nil || message.AggregateID != message.RecordKey ||
		message.Topic != DriverShiftOwnerProofTopic ||
		message.EventType != "task-board.driver-shift-owner-proof.changed.v1" ||
		message.AggregateType != DriverShiftOwnerProofAggregate || message.AggregateVersion < 0 ||
		message.RecordedAt.IsZero() || len(message.WireBody) == 0 || len(message.WireBody) > 1<<20 ||
		!validSHA256(message.BodySHA256) {
		return ErrConflict
	}
	sum := sha256.Sum256(message.WireBody)
	if hex.EncodeToString(sum[:]) != message.BodySHA256 {
		return ErrIdempotencyMismatch
	}
	if len(message.AllowedWorkerIDs) > 1 || len(message.ReaderWorkerIDs) > 1 {
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
	return nil
}

func lockDriverShiftOwnerProof(ctx context.Context, tx pgx.Tx, eventID, shiftID uuid.UUID) error {
	locks := []string{
		"driver-shift-owner-proof:event:" + eventID.String(),
		"driver-shift-owner-proof:shift:" + shiftID.String(),
	}
	sort.Strings(locks)
	for _, lock := range locks {
		if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`, lock); err != nil {
			return err
		}
	}
	return nil
}

func readDriverShiftOwnerProofReceipt(ctx context.Context, tx pgx.Tx, eventID uuid.UUID) (driverShiftOwnerProofReceipt, bool, error) {
	var receipt driverShiftOwnerProofReceipt
	err := tx.QueryRow(ctx, `select event_id,aggregate_id,aggregate_version,record_key,body_sha256,outcome
		from media_driver_shift_owner_proof_inbox
		where consumer_name=$1 and event_id=$2 for update`, DriverShiftOwnerProofConsumer, eventID).
		Scan(&receipt.EventID, &receipt.AggregateID, &receipt.AggregateVersion, &receipt.RecordKey,
			&receipt.BodySHA256, &receipt.Outcome)
	if errors.Is(err, pgx.ErrNoRows) {
		return driverShiftOwnerProofReceipt{}, false, nil
	}
	return receipt, err == nil, err
}

func readDriverShiftOwnerProofVersion(ctx context.Context, tx pgx.Tx, shiftID uuid.UUID, version int64) (driverShiftOwnerProofReceipt, bool, error) {
	var receipt driverShiftOwnerProofReceipt
	err := tx.QueryRow(ctx, `select event_id,aggregate_id,aggregate_version,record_key,body_sha256,outcome
		from media_driver_shift_owner_proof_inbox
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3 and aggregate_version=$4
		for update`, DriverShiftOwnerProofConsumer, DriverShiftOwnerProofAggregate, shiftID, version).
		Scan(&receipt.EventID, &receipt.AggregateID, &receipt.AggregateVersion, &receipt.RecordKey,
			&receipt.BodySHA256, &receipt.Outcome)
	if errors.Is(err, pgx.ErrNoRows) {
		return driverShiftOwnerProofReceipt{}, false, nil
	}
	return receipt, err == nil, err
}

func driverShiftOwnerProofCheckpoint(ctx context.Context, tx pgx.Tx, shiftID uuid.UUID) (int64, bool, error) {
	var version int64
	err := tx.QueryRow(ctx, `select aggregate_version from media_consumer_aggregate_checkpoint
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3 for update`,
		DriverShiftOwnerProofConsumer, DriverShiftOwnerProofAggregate, shiftID).Scan(&version)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	return version, err == nil, err
}

func applyDriverShiftOwnerProof(ctx context.Context, tx pgx.Tx, message DriverShiftOwnerProofMessage) error {
	now := message.RecordedAt.UTC()
	_, err := tx.Exec(ctx, `insert into media_driver_shift_owner_proof (
		shift_id,warehouse_id,active,aggregate_version,proof_event_id,body_sha256,
		quarantined,quarantine_reason,updated_at)
	values ($1,$2,$3,$4,$5,$6,false,null,$7)
	on conflict (shift_id) do update set warehouse_id=excluded.warehouse_id,
		active=excluded.active,aggregate_version=excluded.aggregate_version,
		proof_event_id=excluded.proof_event_id,body_sha256=excluded.body_sha256,
		quarantined=false,quarantine_reason=null,updated_at=excluded.updated_at`,
		message.AggregateID, message.WarehouseID, message.Active, message.AggregateVersion,
		message.EventID, message.BodySHA256, now)
	if err != nil {
		return translateConstraint(err)
	}
	if _, err := tx.Exec(ctx, `delete from media_driver_shift_allowed_worker where shift_id=$1`, message.AggregateID); err != nil {
		return err
	}
	for _, workerID := range message.AllowedWorkerIDs {
		if _, err := tx.Exec(ctx, `insert into media_driver_shift_allowed_worker (shift_id,worker_id)
			values ($1,$2)`, message.AggregateID, workerID); err != nil {
			return translateConstraint(err)
		}
	}
	if _, err := tx.Exec(ctx, `delete from media_driver_shift_reader_worker where shift_id=$1`, message.AggregateID); err != nil {
		return err
	}
	for _, workerID := range message.ReaderWorkerIDs {
		if _, err := tx.Exec(ctx, `insert into media_driver_shift_reader_worker (shift_id,worker_id)
			values ($1,$2)`, message.AggregateID, workerID); err != nil {
			return translateConstraint(err)
		}
	}
	_, err = tx.Exec(ctx, `insert into media_owner_binding (
		owner_type,owner_id,warehouse_id,owner_revision,proof_event_id,proof_consumer_name,
		proof_aggregate_type,proof_aggregate_id,proof_aggregate_version,proof_recorded_at,active,updated_at)
	values ('DRIVER_SHIFT',$1,$2,$3,$4,$5,$6,$7,$3,$8,$9,$8)
	on conflict (owner_type,owner_id) do update set warehouse_id=excluded.warehouse_id,
		owner_revision=excluded.owner_revision,proof_event_id=excluded.proof_event_id,
		proof_consumer_name=excluded.proof_consumer_name,
		proof_aggregate_type=excluded.proof_aggregate_type,
		proof_aggregate_id=excluded.proof_aggregate_id,
		proof_aggregate_version=excluded.proof_aggregate_version,
		proof_recorded_at=excluded.proof_recorded_at,active=excluded.active,
		updated_at=excluded.updated_at`, message.AggregateID.String(), message.WarehouseID,
		message.AggregateVersion, message.EventID, DriverShiftOwnerProofConsumer,
		DriverShiftOwnerProofAggregate, message.AggregateID, now, message.Active)
	return translateConstraint(err)
}

func advanceDriverShiftOwnerProofCheckpoint(ctx context.Context, tx pgx.Tx, message DriverShiftOwnerProofMessage) error {
	_, err := tx.Exec(ctx, `insert into media_consumer_aggregate_checkpoint
		(consumer_name,aggregate_type,aggregate_id,aggregate_version,updated_at)
	values ($1,$2,$3,$4,$5)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		aggregate_version=excluded.aggregate_version,updated_at=excluded.updated_at`,
		DriverShiftOwnerProofConsumer, DriverShiftOwnerProofAggregate, message.AggregateID,
		message.AggregateVersion, message.RecordedAt.UTC())
	return translateConstraint(err)
}

func insertDriverShiftOwnerProofInbox(ctx context.Context, tx pgx.Tx, message DriverShiftOwnerProofMessage, outcome, failureCode string) error {
	var failure any
	var processedAt any
	if outcome == "APPLIED" {
		processedAt = message.RecordedAt.UTC()
	} else {
		failure = failureCode
	}
	_, err := tx.Exec(ctx, `insert into media_driver_shift_owner_proof_inbox (
		consumer_name,event_id,topic,event_type,aggregate_type,aggregate_id,aggregate_version,
		record_key,warehouse_id,active,body_sha256,wire_body,outcome,failure_code,processed_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)`,
		DriverShiftOwnerProofConsumer, message.EventID, message.Topic, message.EventType,
		message.AggregateType, message.AggregateID, message.AggregateVersion, message.RecordKey,
		message.WarehouseID, message.Active, message.BodySHA256, message.WireBody,
		outcome, failure, processedAt)
	return translateConstraint(err)
}

func (repository *Repository) quarantineDriverShiftOwnerProof(ctx context.Context, tx pgx.Tx, message DriverShiftOwnerProofMessage, expected int64, reason string) error {
	if err := repository.quarantineDriverShiftOwnerAggregate(ctx, tx, message.AggregateID, expected,
		message.AggregateVersion, reason, message.EventID); err != nil {
		return err
	}
	return insertDriverShiftOwnerProofInbox(ctx, tx, message, "QUARANTINED", reason)
}

func (repository *Repository) quarantineDriverShiftOwnerAggregate(ctx context.Context, tx pgx.Tx, shiftID uuid.UUID, expected, observed int64, reason string, eventID uuid.UUID) error {
	_, err := tx.Exec(ctx, `insert into media_quarantined_aggregate (
		consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,reason_code,first_event_id)
	values ($1,$2,$3,$4,$5,$6,$7)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		expected_version=excluded.expected_version,observed_version=excluded.observed_version,
		reason_code=excluded.reason_code,first_event_id=excluded.first_event_id,
		quarantined_at=clock_timestamp(),reconciled_at=null,resolution_reason=null,
		resolved_by_subject_id=null`, DriverShiftOwnerProofConsumer,
		DriverShiftOwnerProofAggregate, shiftID, expected, observed, reason, eventID)
	if err != nil {
		return translateConstraint(err)
	}
	if _, err := tx.Exec(ctx, `update media_driver_shift_owner_proof
		set active=false,quarantined=true,quarantine_reason=$2,updated_at=clock_timestamp()
		where shift_id=$1`, shiftID, reason); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `update media_owner_binding set active=false,updated_at=clock_timestamp()
		where owner_type='DRIVER_SHIFT' and owner_id=$1`, shiftID.String()); err != nil {
		return err
	}
	return nil
}

func (repository *Repository) recordDriverShiftOwnerProofConflict(ctx context.Context, tx pgx.Tx, message DriverShiftOwnerProofMessage, prior *driverShiftOwnerProofReceipt, failureCode string) error {
	var priorEventID, priorAggregateID, priorAggregateVersion, priorSHA any
	if prior != nil {
		priorEventID = prior.EventID
		priorAggregateID = prior.AggregateID
		priorAggregateVersion = prior.AggregateVersion
		priorSHA = prior.BodySHA256
	}
	_, err := tx.Exec(ctx, `insert into media_driver_shift_owner_proof_conflict (
		consumer_name,event_id,prior_event_id,prior_aggregate_id,prior_aggregate_version,
		prior_body_sha256,incoming_aggregate_id,incoming_aggregate_version,incoming_record_key,
		incoming_body_sha256,incoming_wire_body,failure_code)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
	on conflict (consumer_name,event_id,incoming_body_sha256) do nothing`,
		DriverShiftOwnerProofConsumer, message.EventID, priorEventID, priorAggregateID,
		priorAggregateVersion, priorSHA, message.AggregateID, message.AggregateVersion,
		message.RecordKey, message.BodySHA256, message.WireBody, failureCode)
	return translateConstraint(err)
}

// RecordDriverShiftOwnerProofDLT persists a hash-only local dead-letter for an
// invalid driver-shift proof before its Kafka offset is committed.
func (repository *Repository) RecordDriverShiftOwnerProofDLT(ctx context.Context, bodySHA256 string) error {
	if !validSHA256(bodySHA256) {
		return ErrConflict
	}
	eventID := uuid.NewSHA1(uuid.NameSpaceOID, []byte("invalid-driver-shift-owner-proof:"+bodySHA256))
	_, err := repository.pool.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,'INVALID_DRIVER_SHIFT_OWNER_PROOF',0)
	on conflict (consumer_name,event_id) do nothing`, DriverShiftOwnerProofConsumer, eventID, bodySHA256)
	return translateConstraint(err)
}

func insertDriverShiftOwnerProofDeadLetter(ctx context.Context, tx pgx.Tx, eventID uuid.UUID, bodySHA256, failureCode string) error {
	_, err := tx.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,aggregate_type,body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,$4,$5,1)
	on conflict (consumer_name,event_id) do nothing`, DriverShiftOwnerProofConsumer, eventID,
		DriverShiftOwnerProofAggregate, bodySHA256, failureCode)
	return translateConstraint(err)
}

// RequireDriverShiftWorkerAccess confirms that an active, current and
// non-quarantined proof names the driver in its upload audience.
func RequireDriverShiftWorkerAccess(ctx context.Context, database queryer, shiftID, warehouseID, workerID uuid.UUID) error {
	return requireDriverShiftWorkerAudience(ctx, database, shiftID, warehouseID, workerID,
		"media_driver_shift_allowed_worker")
}

// RequireDriverShiftWorkerReadAccess confirms that an active, current and
// non-quarantined proof names the driver in its read audience.
func RequireDriverShiftWorkerReadAccess(ctx context.Context, database queryer, shiftID, warehouseID, workerID uuid.UUID) error {
	return requireDriverShiftWorkerAudience(ctx, database, shiftID, warehouseID, workerID,
		"media_driver_shift_reader_worker")
}

func requireDriverShiftWorkerAudience(ctx context.Context, database queryer, shiftID, warehouseID, workerID uuid.UUID, audienceTable string) error {
	if shiftID == uuid.Nil || warehouseID == uuid.Nil || workerID == uuid.Nil ||
		(audienceTable != "media_driver_shift_allowed_worker" && audienceTable != "media_driver_shift_reader_worker") {
		return ErrOwnerProofMissing
	}
	if err := lockDriverShiftOwnerProofForAuthorization(ctx, database, shiftID); err != nil {
		return err
	}
	var found string
	query := `select binding.owner_id
		from media_owner_binding binding
		join media_driver_shift_owner_proof proof
		  on proof.shift_id=binding.proof_aggregate_id
		 and proof.warehouse_id=binding.warehouse_id
		 and proof.aggregate_version=binding.proof_aggregate_version
		 and proof.proof_event_id=binding.proof_event_id
		 and proof.active and not proof.quarantined
		join media_consumer_aggregate_checkpoint checkpoint
		  on checkpoint.consumer_name=binding.proof_consumer_name
		 and checkpoint.aggregate_type=binding.proof_aggregate_type
		 and checkpoint.aggregate_id=binding.proof_aggregate_id
		 and checkpoint.aggregate_version>=binding.proof_aggregate_version
		join ` + audienceTable + ` audience
		  on audience.shift_id=binding.proof_aggregate_id and audience.worker_id=$3
		where binding.owner_type='DRIVER_SHIFT' and binding.owner_id=$1
		  and binding.warehouse_id=$2 and binding.active
		  and binding.proof_consumer_name=$4 and binding.proof_aggregate_type=$5
		  and not exists (select 1 from media_quarantined_aggregate quarantine
		    where quarantine.consumer_name=binding.proof_consumer_name
		      and quarantine.aggregate_type=binding.proof_aggregate_type
		      and quarantine.aggregate_id=binding.proof_aggregate_id
		      and quarantine.reconciled_at is null)
		for share of binding,proof,audience`
	err := database.QueryRow(ctx, query, shiftID.String(), warehouseID, workerID,
		DriverShiftOwnerProofConsumer, DriverShiftOwnerProofAggregate).Scan(&found)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrOwnerProofMissing
	}
	return err
}

func lockDriverShiftOwnerProofForAuthorization(ctx context.Context, database queryer, shiftID uuid.UUID) error {
	var found uuid.UUID
	err := database.QueryRow(ctx, `select shift_id from media_driver_shift_owner_proof
		where shift_id=$1 for share`, shiftID).Scan(&found)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrOwnerProofMissing
	}
	return err
}

// AuthorizeDriverShiftWorker applies the driver-shift upload audience check
// through this repository's PostgreSQL pool.
func (repository *Repository) AuthorizeDriverShiftWorker(ctx context.Context, shiftID, warehouseID, workerID uuid.UUID) error {
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := RequireDriverShiftWorkerAccess(ctx, tx, shiftID, warehouseID, workerID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
