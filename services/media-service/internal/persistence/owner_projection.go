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

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

const (
	InventoryOwnerConsumerGroup = "media-service-inventory-owner-v1"
	InventorySessionTopic       = "rwms.inventory.session.v1"
	InventoryOwnerDLTTopic      = "rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt"
	InventoryOwnerProofEvent    = "inventory.finding.owner-proof.v1"
	InventoryFindingAggregate   = "FINDING"
)

var (
	ErrVersionGap         = errors.New("aggregate version gap")
	ErrOwnerProofConflict = errors.New("owner proof revision conflict")
	ErrAggregateBlocked   = errors.New("inventory finding aggregate is quarantined")
	ErrReconciliation     = errors.New("inventory owner reconciliation evidence is invalid")
)

type InventoryOwnerProof struct {
	OwnerType     string
	OwnerID       uuid.UUID
	WarehouseID   uuid.UUID
	OwnerRevision int64
	Active        bool
}

type InventoryFindingMessage struct {
	EventID          uuid.UUID
	BodySHA256       string
	WireBody         []byte
	Topic            string
	EventType        string
	AggregateType    string
	AggregateID      uuid.UUID
	AggregateVersion int64
	RecordKey        uuid.UUID
	WarehouseID      uuid.UUID
	RecordedAt       time.Time
	Proof            *InventoryOwnerProof
}

type InventoryFindingApplyResult struct {
	Duplicate   bool
	Quarantined bool
}

type InventoryOwnerDLTMessage struct {
	SourceEventID    uuid.UUID
	BodySHA256       string
	AggregateID      *uuid.UUID
	AggregateVersion *int64
	RecordKey        uuid.UUID
	FailureCode      string
	AttemptCount     int
}

type InventoryOwnerRetryState struct {
	Attempt          int
	AvailableAt      time.Time
	AggregateID      uuid.UUID
	AggregateVersion int64
	RecordKey        uuid.UUID
	WarehouseID      uuid.UUID
}

func (repository *Repository) ApplyInventoryFindingMessage(
	ctx context.Context,
	message InventoryFindingMessage,
) (InventoryFindingApplyResult, error) {
	if err := validateInventoryFindingMessage(message); err != nil {
		return InventoryFindingApplyResult{}, err
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return InventoryFindingApplyResult{}, err
	}
	defer tx.Rollback(ctx)
	result, err := repository.applyInventoryFindingMessage(ctx, tx, message, false)
	if err != nil {
		return InventoryFindingApplyResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return InventoryFindingApplyResult{}, err
	}
	return result, nil
}

func (repository *Repository) applyInventoryFindingMessage(
	ctx context.Context,
	tx pgx.Tx,
	message InventoryFindingMessage,
	reconciling bool,
) (InventoryFindingApplyResult, error) {
	// Serialize the global event-ID namespace even when no inbox row exists yet,
	// so concurrent cross-aggregate reuse cannot bypass two-sided quarantine.
	if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		message.EventID.String()); err != nil {
		return InventoryFindingApplyResult{}, err
	}
	var priorAggregateID uuid.UUID
	var priorAggregateVersion int64
	var priorRecordKey uuid.UUID
	var priorWarehouseID uuid.UUID
	var priorHash string
	var priorOutcome string
	err := tx.QueryRow(ctx, `select aggregate_id,aggregate_version,record_key,warehouse_id,
		body_sha256,outcome from media_inventory_finding_inbox
		where consumer_name=$1 and event_id=$2 for update`, InventoryOwnerConsumerGroup, message.EventID).
		Scan(&priorAggregateID, &priorAggregateVersion, &priorRecordKey, &priorWarehouseID,
			&priorHash, &priorOutcome)
	if err == nil {
		if priorHash != message.BodySHA256 || priorAggregateID != message.AggregateID ||
			priorAggregateVersion != message.AggregateVersion || priorRecordKey != message.RecordKey {
			if _, err := tx.Exec(ctx, `insert into media_inventory_finding_event_conflict (
				consumer_name,event_id,prior_aggregate_id,prior_aggregate_version,prior_record_key,
				prior_body_sha256,incoming_aggregate_id,incoming_aggregate_version,
				incoming_record_key,incoming_warehouse_id,incoming_body_sha256,incoming_wire_body)
			values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
			on conflict (consumer_name,event_id,incoming_body_sha256) do nothing`,
				InventoryOwnerConsumerGroup, message.EventID, priorAggregateID, priorAggregateVersion,
				priorRecordKey, priorHash, message.AggregateID, message.AggregateVersion,
				message.RecordKey, message.WarehouseID, message.BodySHA256, message.WireBody); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			if priorOutcome == "APPLIED" {
				if err := repository.openInventoryFindingQuarantine(ctx, tx, priorAggregateID,
					priorAggregateVersion, priorAggregateVersion, "EVENT_ID_CONFLICT", message.EventID); err != nil {
					return InventoryFindingApplyResult{}, err
				}
			}
			incomingExpected, incomingExists, checkpointErr := inventoryCheckpoint(ctx, tx, message.AggregateID)
			if checkpointErr != nil {
				return InventoryFindingApplyResult{}, checkpointErr
			}
			if !incomingExists {
				incomingExpected = -1
			}
			if err := repository.openInventoryFindingQuarantine(ctx, tx, message.AggregateID,
				incomingExpected+1, message.AggregateVersion, "EVENT_ID_CONFLICT", message.EventID); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			if err := deactivateInventoryFindingBindings(ctx, tx, priorAggregateID,
				message.AggregateID); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			if err := repository.insertInventoryOwnerDLT(ctx, tx, InventoryOwnerDLTMessage{
				SourceEventID: message.EventID, BodySHA256: message.BodySHA256,
				AggregateID: &message.AggregateID, AggregateVersion: &message.AggregateVersion,
				RecordKey: message.RecordKey, FailureCode: "OWNER_EVENT_ID_CONFLICT", AttemptCount: 1,
			}); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			return InventoryFindingApplyResult{Quarantined: true}, nil
		}
		if reconciling && priorOutcome == "QUARANTINED" {
			// The reviewed batch is allowed to apply the exact quarantined bytes.
		} else {
			return InventoryFindingApplyResult{Duplicate: true, Quarantined: priorOutcome == "QUARANTINED"}, nil
		}
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return InventoryFindingApplyResult{}, err
	}

	if !reconciling {
		var blocked bool
		if err := tx.QueryRow(ctx, `select exists(select 1 from media_quarantined_aggregate
			where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2
			  and reconciled_at is null)`, InventoryOwnerConsumerGroup, message.AggregateID).Scan(&blocked); err != nil {
			return InventoryFindingApplyResult{}, err
		}
		if blocked {
			if err := upsertInventoryInbox(ctx, tx, message, "QUARANTINED", "AGGREGATE_ALREADY_QUARANTINED"); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			return InventoryFindingApplyResult{Quarantined: true}, nil
		}
	}

	checkpoint, exists, err := inventoryCheckpoint(ctx, tx, message.AggregateID)
	if err != nil {
		return InventoryFindingApplyResult{}, err
	}
	expected := int64(0)
	if exists {
		expected = checkpoint + 1
	}
	if message.AggregateVersion != expected {
		reason := "VERSION_GAP"
		if message.AggregateVersion < expected {
			reason = "AGGREGATE_VERSION_REGRESSION"
		}
		if err := repository.quarantineInventoryFinding(ctx, tx, message, expected, reason); err != nil {
			return InventoryFindingApplyResult{}, err
		}
		return InventoryFindingApplyResult{Quarantined: true}, nil
	}

	var streamWarehouseID uuid.UUID
	streamExists := true
	err = tx.QueryRow(ctx, `select warehouse_id from media_inventory_finding_stream
		where consumer_name=$1 and aggregate_id=$2 for update`, InventoryOwnerConsumerGroup,
		message.AggregateID).Scan(&streamWarehouseID)
	if errors.Is(err, pgx.ErrNoRows) {
		streamExists = false
	} else if err != nil {
		return InventoryFindingApplyResult{}, err
	}
	if !streamExists {
		if message.EventType != "inventory.finding.added.v1" || message.AggregateVersion != 0 {
			if err := repository.quarantineInventoryFinding(ctx, tx, message,
				expected, "INVALID_STREAM_BOOTSTRAP"); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			return InventoryFindingApplyResult{Quarantined: true}, nil
		}
		if _, err := tx.Exec(ctx, `insert into media_inventory_finding_stream (
			consumer_name,aggregate_id,warehouse_id,bootstrap_event_id,bootstrapped_at)
		values ($1,$2,$3,$4,$5)`, InventoryOwnerConsumerGroup, message.AggregateID,
			message.WarehouseID, message.EventID, message.RecordedAt.UTC()); err != nil {
			return InventoryFindingApplyResult{}, translateConstraint(err)
		}
	} else {
		if message.EventType == "inventory.finding.added.v1" {
			if err := repository.quarantineInventoryFinding(ctx, tx, message,
				expected, "DUPLICATE_STREAM_BOOTSTRAP"); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			return InventoryFindingApplyResult{Quarantined: true}, nil
		}
		if message.WarehouseID != streamWarehouseID {
			if err := repository.quarantineInventoryFinding(ctx, tx, message,
				expected, "WAREHOUSE_CONFLICT"); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			return InventoryFindingApplyResult{Quarantined: true}, nil
		}
	}

	if message.Proof != nil {
		result, err := repository.applyInventoryOwnerProof(ctx, tx, message)
		if err != nil || result.Quarantined {
			return result, err
		}
	}
	if err := upsertInventoryInbox(ctx, tx, message, "APPLIED", ""); err != nil {
		return InventoryFindingApplyResult{}, err
	}
	if err := advanceInventoryCheckpoint(ctx, tx, message); err != nil {
		return InventoryFindingApplyResult{}, err
	}
	return InventoryFindingApplyResult{}, nil
}

func (repository *Repository) applyInventoryOwnerProof(
	ctx context.Context,
	tx pgx.Tx,
	message InventoryFindingMessage,
) (InventoryFindingApplyResult, error) {
	proof := *message.Proof
	var replacingUnverifiedLegacy bool
	if err := tx.QueryRow(ctx, `select exists(select 1 from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2
		  and reason_code='UNVERIFIED_PRE_TASK1B_BINDING' and reconciled_at is null)`,
		InventoryOwnerConsumerGroup, message.AggregateID).Scan(&replacingUnverifiedLegacy); err != nil {
		return InventoryFindingApplyResult{}, err
	}
	var revision int64
	var warehouseID uuid.UUID
	var active bool
	err := tx.QueryRow(ctx, `select owner_revision,warehouse_id,active from media_owner_binding
		where owner_type=$1 and owner_id=$2 for update`, OwnerTypeInventoryFinding,
		proof.OwnerID.String()).Scan(&revision, &warehouseID, &active)
	if err == nil {
		reason := ""
		if proof.OwnerRevision < revision {
			reason = "OWNER_REVISION_REGRESSION"
		} else if proof.OwnerRevision == revision && (warehouseID != proof.WarehouseID || active != proof.Active) {
			reason = "OWNER_REVISION_CONFLICT"
		}
		if reason != "" && !replacingUnverifiedLegacy {
			if err := repository.quarantineInventoryFinding(ctx, tx, message,
				message.AggregateVersion, reason); err != nil {
				return InventoryFindingApplyResult{}, err
			}
			return InventoryFindingApplyResult{Quarantined: true}, nil
		}
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return InventoryFindingApplyResult{}, err
	}
	_, err = tx.Exec(ctx, `insert into media_owner_binding (
		owner_type,owner_id,warehouse_id,owner_revision,proof_event_id,
		proof_consumer_name,proof_aggregate_type,proof_aggregate_id,proof_aggregate_version,
		proof_recorded_at,active,updated_at)
	values ($1,$2,$3,$4,$5,$6,'FINDING',$7,$8,$9,$10,clock_timestamp())
	on conflict (owner_type,owner_id) do update set
		warehouse_id=excluded.warehouse_id,owner_revision=excluded.owner_revision,
		proof_event_id=excluded.proof_event_id,proof_consumer_name=excluded.proof_consumer_name,
		proof_aggregate_type=excluded.proof_aggregate_type,proof_aggregate_id=excluded.proof_aggregate_id,
		proof_aggregate_version=excluded.proof_aggregate_version,
		proof_recorded_at=excluded.proof_recorded_at,active=excluded.active,updated_at=clock_timestamp()
	where excluded.owner_revision>=media_owner_binding.owner_revision
	   or exists (select 1 from media_quarantined_aggregate quarantine
		where quarantine.consumer_name=$6 and quarantine.aggregate_type='FINDING'
		  and quarantine.aggregate_id=$7
		  and quarantine.reason_code='UNVERIFIED_PRE_TASK1B_BINDING'
		  and quarantine.reconciled_at is null)`,
		OwnerTypeInventoryFinding, proof.OwnerID.String(), proof.WarehouseID, proof.OwnerRevision,
		message.EventID, InventoryOwnerConsumerGroup, message.AggregateID, message.AggregateVersion,
		message.RecordedAt.UTC(), proof.Active)
	return InventoryFindingApplyResult{}, translateConstraint(err)
}

func inventoryCheckpoint(ctx context.Context, tx pgx.Tx, aggregateID uuid.UUID) (int64, bool, error) {
	var checkpoint int64
	err := tx.QueryRow(ctx, `select aggregate_version from media_consumer_aggregate_checkpoint
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2 for update`,
		InventoryOwnerConsumerGroup, aggregateID).Scan(&checkpoint)
	if errors.Is(err, pgx.ErrNoRows) {
		return -1, false, nil
	}
	return checkpoint, err == nil, err
}

func advanceInventoryCheckpoint(ctx context.Context, tx pgx.Tx, message InventoryFindingMessage) error {
	_, err := tx.Exec(ctx, `insert into media_consumer_aggregate_checkpoint (
		consumer_name,aggregate_type,aggregate_id,aggregate_version)
	values ($1,'FINDING',$2,$3)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update
	set aggregate_version=excluded.aggregate_version,updated_at=clock_timestamp()`,
		InventoryOwnerConsumerGroup, message.AggregateID, message.AggregateVersion)
	return err
}

func upsertInventoryInbox(
	ctx context.Context,
	tx pgx.Tx,
	message InventoryFindingMessage,
	outcome string,
	failureCode string,
) error {
	var ownerRevision any
	if message.Proof != nil {
		ownerRevision = message.Proof.OwnerRevision
	}
	var processedAt any
	var failure any
	if outcome == "APPLIED" {
		processedAt = time.Now().UTC()
	} else {
		failure = failureCode
	}
	_, err := tx.Exec(ctx, `insert into media_inventory_finding_inbox (
		consumer_name,event_id,topic,event_type,aggregate_type,aggregate_id,aggregate_version,
		record_key,warehouse_id,body_sha256,wire_body,owner_revision,outcome,failure_code,processed_at)
	values ($1,$2,$3,$4,'FINDING',$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
	on conflict (consumer_name,event_id) do update set outcome=excluded.outcome,
		failure_code=excluded.failure_code,processed_at=excluded.processed_at
	where media_inventory_finding_inbox.body_sha256=excluded.body_sha256`,
		InventoryOwnerConsumerGroup, message.EventID, message.Topic, message.EventType,
		message.AggregateID, message.AggregateVersion, message.RecordKey, message.WarehouseID,
		message.BodySHA256, message.WireBody, ownerRevision, outcome, failure, processedAt)
	return translateConstraint(err)
}

func (repository *Repository) quarantineInventoryFinding(
	ctx context.Context,
	tx pgx.Tx,
	message InventoryFindingMessage,
	expected int64,
	reason string,
) error {
	if err := upsertInventoryInbox(ctx, tx, message, "QUARANTINED", reason); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `insert into media_quarantined_aggregate (
		consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,
		reason_code,first_event_id)
	values ($1,'FINDING',$2,$3,$4,$5,$6)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		expected_version=excluded.expected_version,observed_version=excluded.observed_version,
		reason_code=excluded.reason_code,first_event_id=excluded.first_event_id,
		quarantined_at=clock_timestamp(),reconciled_at=null,resolution_reason=null,
		resolved_by_subject_id=null`, InventoryOwnerConsumerGroup, message.AggregateID,
		expected, message.AggregateVersion, reason, message.EventID)
	return err
}

func (repository *Repository) openInventoryFindingQuarantine(
	ctx context.Context,
	tx pgx.Tx,
	aggregateID uuid.UUID,
	expected int64,
	observed int64,
	reason string,
	eventID uuid.UUID,
) error {
	_, err := tx.Exec(ctx, `insert into media_quarantined_aggregate (
		consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,
		reason_code,first_event_id)
	values ($1,'FINDING',$2,$3,$4,$5,$6)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		expected_version=excluded.expected_version,observed_version=excluded.observed_version,
		reason_code=excluded.reason_code,first_event_id=excluded.first_event_id,
		quarantined_at=clock_timestamp(),reconciled_at=null,resolution_reason=null,
		resolved_by_subject_id=null`, InventoryOwnerConsumerGroup, aggregateID,
		expected, observed, reason, eventID)
	return err
}

func validateInventoryFindingMessage(message InventoryFindingMessage) error {
	if message.EventID == uuid.Nil || message.Topic != InventorySessionTopic ||
		message.AggregateType != InventoryFindingAggregate || message.AggregateID == uuid.Nil ||
		message.AggregateVersion < 0 || message.RecordKey != message.AggregateID ||
		message.WarehouseID == uuid.Nil ||
		message.RecordedAt.IsZero() || len(message.WireBody) == 0 || len(message.WireBody) > 1<<20 ||
		!validSHA256(message.BodySHA256) {
		return ErrConflict
	}
	sum := sha256.Sum256(message.WireBody)
	if hex.EncodeToString(sum[:]) != message.BodySHA256 {
		return ErrIdempotencyMismatch
	}
	switch message.EventType {
	case "inventory.finding.added.v1", "inventory.finding.inspection-saved.v1":
		if message.Proof != nil {
			return ErrConflict
		}
	case InventoryOwnerProofEvent:
		if message.Proof == nil || message.Proof.OwnerType != OwnerTypeInventoryFinding ||
			message.Proof.OwnerID != message.AggregateID || message.Proof.WarehouseID == uuid.Nil ||
			message.Proof.WarehouseID != message.WarehouseID || message.Proof.OwnerRevision < 0 {
			return ErrConflict
		}
	default:
		return ErrConflict
	}
	return nil
}

func (repository *Repository) RecordInventoryOwnerDLT(
	ctx context.Context,
	message InventoryOwnerDLTMessage,
) error {
	if !validSHA256(message.BodySHA256) || strings.TrimSpace(message.FailureCode) == "" ||
		!validInventoryOwnerDLTFailure(message.FailureCode) ||
		message.AttemptCount < 0 || message.AttemptCount > 4 {
		return ErrConflict
	}
	if (message.AggregateID == nil) != (message.AggregateVersion == nil) ||
		(message.AggregateID != nil && (*message.AggregateID == uuid.Nil ||
			message.AggregateVersion == nil || *message.AggregateVersion < 0 ||
			message.RecordKey != *message.AggregateID)) {
		return ErrConflict
	}
	if message.AggregateID == nil {
		message.RecordKey = uuid.NewSHA1(uuid.NameSpaceOID, []byte(message.BodySHA256))
	}
	if message.SourceEventID == uuid.Nil {
		message.SourceEventID = uuid.NewSHA1(uuid.NameSpaceOID, []byte(message.BodySHA256))
	}
	tx, err := repository.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := repository.insertInventoryOwnerDLT(ctx, tx, message); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// QuarantineInventoryOwnerProcessingFailure is the terminal owner-consumer
// transaction. The DLT, open quarantine, binding invalidation, inbox outcome,
// and retry cleanup become visible together before the Kafka offset is committed.
func (repository *Repository) QuarantineInventoryOwnerProcessingFailure(
	ctx context.Context,
	message InventoryFindingMessage,
	attemptCount int,
) error {
	if err := validateInventoryFindingMessage(message); err != nil || attemptCount != 4 {
		return ErrConflict
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		message.EventID.String()); err != nil {
		return err
	}
	if _, _, err := inventoryCheckpoint(ctx, tx, message.AggregateID); err != nil {
		return err
	}
	if err := upsertInventoryInbox(ctx, tx, message, "QUARANTINED",
		"OWNER_PROOF_PROCESSING_FAILED"); err != nil {
		return err
	}
	if err := repository.openInventoryFindingQuarantine(ctx, tx, message.AggregateID,
		message.AggregateVersion, message.AggregateVersion,
		"OWNER_PROOF_PROCESSING_FAILED", message.EventID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `update media_owner_binding set active=false,updated_at=clock_timestamp()
		where owner_type=$1 and owner_id=$2`, OwnerTypeInventoryFinding,
		message.AggregateID.String()); err != nil {
		return err
	}
	version := message.AggregateVersion
	if err := repository.insertInventoryOwnerDLT(ctx, tx, InventoryOwnerDLTMessage{
		SourceEventID: message.EventID, BodySHA256: message.BodySHA256,
		AggregateID: &message.AggregateID, AggregateVersion: &version,
		RecordKey: message.RecordKey, FailureCode: "OWNER_PROOF_PROCESSING_FAILED",
		AttemptCount: attemptCount,
	}); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (repository *Repository) ScheduleInventoryOwnerRetry(
	ctx context.Context,
	message InventoryFindingMessage,
	attempt int,
	delay time.Duration,
) error {
	if attempt < 1 || attempt > 3 || delay <= 0 {
		return ErrConflict
	}
	if err := validateInventoryFindingMessage(message); err != nil {
		return err
	}
	command, err := repository.pool.Exec(ctx, `insert into media_retry_schedule (
		consumer_name,event_id,body_sha256,attempt,available_at,last_error_code,
		aggregate_type,aggregate_id,aggregate_version,record_key,warehouse_id)
	values ($1,$2,$3,$4,clock_timestamp()+$5::interval,'OWNER_PROOF_DEPENDENCY_UNAVAILABLE',
		'FINDING',$6,$7,$8,$9)
	on conflict (consumer_name,event_id) do update set body_sha256=excluded.body_sha256,
		attempt=excluded.attempt,available_at=excluded.available_at,
		last_error_code=excluded.last_error_code,aggregate_type=excluded.aggregate_type,
		aggregate_id=excluded.aggregate_id,aggregate_version=excluded.aggregate_version,
		record_key=excluded.record_key,warehouse_id=excluded.warehouse_id
	where media_retry_schedule.body_sha256=excluded.body_sha256`, InventoryOwnerConsumerGroup, message.EventID,
		message.BodySHA256, attempt, delay.String(), message.AggregateID,
		message.AggregateVersion, message.RecordKey, message.WarehouseID)
	if err != nil {
		return translateConstraint(err)
	}
	if command.RowsAffected() != 1 {
		return ErrIdempotencyMismatch
	}
	return nil
}

func (repository *Repository) InventoryOwnerRetryState(
	ctx context.Context,
	eventID uuid.UUID,
	bodySHA256 string,
) (InventoryOwnerRetryState, bool, error) {
	var state InventoryOwnerRetryState
	var persistedHash string
	err := repository.pool.QueryRow(ctx, `select body_sha256,attempt,available_at,
		aggregate_id,aggregate_version,record_key,warehouse_id
		from media_retry_schedule where consumer_name=$1 and event_id=$2`,
		InventoryOwnerConsumerGroup, eventID).Scan(&persistedHash, &state.Attempt, &state.AvailableAt,
		&state.AggregateID, &state.AggregateVersion, &state.RecordKey, &state.WarehouseID)
	if errors.Is(err, pgx.ErrNoRows) {
		return InventoryOwnerRetryState{}, false, nil
	}
	if err != nil {
		return InventoryOwnerRetryState{}, false, err
	}
	if persistedHash != bodySHA256 {
		return state, true, ErrIdempotencyMismatch
	}
	return state, true, nil
}

// QuarantineInventoryOwnerRetryIdentityConflict resolves an event-ID/body-hash
// mismatch before apply. The durable retry row is the authoritative prior
// identity, so both the prior and incoming aggregate are failed closed in the
// same transaction as conflict evidence, DLT publication and retry cleanup.
func (repository *Repository) QuarantineInventoryOwnerRetryIdentityConflict(
	ctx context.Context,
	message InventoryFindingMessage,
) error {
	if err := validateInventoryFindingMessage(message); err != nil {
		return err
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		message.EventID.String()); err != nil {
		return err
	}
	var priorHash string
	var priorAggregateID, priorRecordKey, priorWarehouseID uuid.UUID
	var priorAggregateVersion int64
	err = tx.QueryRow(ctx, `select body_sha256,aggregate_id,aggregate_version,record_key,warehouse_id
		from media_retry_schedule
		where consumer_name=$1 and event_id=$2 for update`, InventoryOwnerConsumerGroup,
		message.EventID).Scan(&priorHash, &priorAggregateID, &priorAggregateVersion,
		&priorRecordKey, &priorWarehouseID)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrConflict
	}
	if err != nil {
		return err
	}
	if priorHash == message.BodySHA256 {
		return ErrConflict
	}
	if _, err := tx.Exec(ctx, `insert into media_inventory_finding_event_conflict (
		consumer_name,event_id,prior_aggregate_id,prior_aggregate_version,prior_record_key,
		prior_body_sha256,incoming_aggregate_id,incoming_aggregate_version,
		incoming_record_key,incoming_warehouse_id,incoming_body_sha256,incoming_wire_body)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
	on conflict (consumer_name,event_id,incoming_body_sha256) do nothing`,
		InventoryOwnerConsumerGroup, message.EventID, priorAggregateID, priorAggregateVersion,
		priorRecordKey, priorHash, message.AggregateID, message.AggregateVersion,
		message.RecordKey, message.WarehouseID, message.BodySHA256, message.WireBody); err != nil {
		return err
	}
	priorExpected, priorExists, err := inventoryCheckpoint(ctx, tx, priorAggregateID)
	if err != nil {
		return err
	}
	if !priorExists {
		priorExpected = -1
	}
	if err := repository.openInventoryFindingQuarantine(ctx, tx, priorAggregateID,
		priorExpected+1, priorAggregateVersion, "EVENT_ID_CONFLICT", message.EventID); err != nil {
		return err
	}
	incomingExpected, incomingExists, err := inventoryCheckpoint(ctx, tx, message.AggregateID)
	if err != nil {
		return err
	}
	if !incomingExists {
		incomingExpected = -1
	}
	if err := repository.openInventoryFindingQuarantine(ctx, tx, message.AggregateID,
		incomingExpected+1, message.AggregateVersion, "EVENT_ID_CONFLICT", message.EventID); err != nil {
		return err
	}
	if err := deactivateInventoryFindingBindings(ctx, tx, priorAggregateID,
		message.AggregateID); err != nil {
		return err
	}
	var inboxExists bool
	if err := tx.QueryRow(ctx, `select exists(select 1 from media_inventory_finding_inbox
		where consumer_name=$1 and event_id=$2)`, InventoryOwnerConsumerGroup,
		message.EventID).Scan(&inboxExists); err != nil {
		return err
	}
	if inboxExists {
		if _, err := tx.Exec(ctx, `update media_inventory_finding_inbox
			set outcome='QUARANTINED',failure_code='EVENT_ID_CONFLICT',processed_at=null
			where consumer_name=$1 and event_id=$2`, InventoryOwnerConsumerGroup,
			message.EventID); err != nil {
			return err
		}
	} else if err := upsertInventoryInbox(ctx, tx, message, "QUARANTINED",
		"EVENT_ID_CONFLICT"); err != nil {
		return err
	}
	version := message.AggregateVersion
	if err := repository.insertInventoryOwnerDLT(ctx, tx, InventoryOwnerDLTMessage{
		SourceEventID: message.EventID, BodySHA256: message.BodySHA256,
		AggregateID: &message.AggregateID, AggregateVersion: &version,
		RecordKey: message.RecordKey, FailureCode: "OWNER_EVENT_ID_CONFLICT", AttemptCount: 1,
	}); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func deactivateInventoryFindingBindings(ctx context.Context, tx pgx.Tx, aggregateIDs ...uuid.UUID) error {
	unique := make(map[uuid.UUID]struct{}, len(aggregateIDs))
	for _, aggregateID := range aggregateIDs {
		if aggregateID != uuid.Nil {
			unique[aggregateID] = struct{}{}
		}
	}
	for aggregateID := range unique {
		if _, err := tx.Exec(ctx, `update media_owner_binding
			set active=false,updated_at=clock_timestamp()
			where owner_type=$1 and owner_id=$2`, OwnerTypeInventoryFinding,
			aggregateID.String()); err != nil {
			return err
		}
	}
	return nil
}

func (repository *Repository) ClearInventoryOwnerRetry(ctx context.Context, eventID uuid.UUID) error {
	_, err := repository.pool.Exec(ctx, `delete from media_retry_schedule
		where consumer_name=$1 and event_id=$2`, InventoryOwnerConsumerGroup, eventID)
	return err
}

func (repository *Repository) insertInventoryOwnerDLT(
	ctx context.Context,
	tx pgx.Tx,
	message InventoryOwnerDLTMessage,
) error {
	_, err := tx.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,aggregate_type,aggregate_id,aggregate_version,
		body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,$4,$5,$6,$7,$8)
	on conflict (consumer_name,event_id) do nothing`, InventoryOwnerConsumerGroup,
		message.SourceEventID, nullableAggregateType(message.AggregateID), message.AggregateID,
		message.AggregateVersion, message.BodySHA256, message.FailureCode, message.AttemptCount)
	if err != nil {
		return err
	}
	recordedAt := repository.now().UTC()
	body, checksum, err := canonicalJSON(map[string]any{
		"failureCode": message.FailureCode, "messageSha256": message.BodySHA256,
		"recordedAt": recordedAt,
	})
	if err != nil {
		return err
	}
	if err := validateInventoryOwnerDLTWire(body); err != nil {
		return err
	}
	dltID := uuid.NewSHA1(uuid.NameSpaceOID, []byte(fmt.Sprintf("inventory-owner-dlt:%s:%s",
		message.SourceEventID, message.FailureCode)))
	_, err = tx.Exec(ctx, `insert into media_transport_outbox (
		event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,record_key,
		publication_ordinal,envelope_body,wire_body,envelope_sha256,recorded_at)
	values ($1,'INVENTORY_OWNER_DLT',$1,1,'media.inventory-owner.dlt.v1',$2,$3,1,
		$4::jsonb,$5,$6,$7) on conflict (event_id) do nothing`, dltID,
		InventoryOwnerDLTTopic, message.RecordKey, string(body), body, checksum, recordedAt)
	if err != nil {
		return translateConstraint(err)
	}
	_, err = tx.Exec(ctx, `delete from media_retry_schedule
		where consumer_name=$1 and event_id=$2`, InventoryOwnerConsumerGroup, message.SourceEventID)
	return err
}

func validInventoryOwnerDLTFailure(value string) bool {
	switch value {
	case "INVALID_INVENTORY_OWNER_FACT", "OWNER_EVENT_ID_CONFLICT", "OWNER_PROOF_PROCESSING_FAILED":
		return true
	default:
		return false
	}
}

func validateInventoryOwnerDLTWire(body []byte) error {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(body, &fields); err != nil || len(fields) != 3 {
		return ErrConflict
	}
	for _, required := range []string{"failureCode", "messageSha256", "recordedAt"} {
		if _, exists := fields[required]; !exists {
			return ErrConflict
		}
	}
	var wire struct {
		FailureCode   string `json:"failureCode"`
		MessageSHA256 string `json:"messageSha256"`
		RecordedAt    string `json:"recordedAt"`
	}
	if err := json.Unmarshal(body, &wire); err != nil ||
		!validInventoryOwnerDLTFailure(wire.FailureCode) || !validSHA256(wire.MessageSHA256) {
		return ErrConflict
	}
	if _, err := time.Parse(time.RFC3339Nano, wire.RecordedAt); err != nil {
		return ErrConflict
	}
	return nil
}

func nullableAggregateType(aggregateID *uuid.UUID) any {
	if aggregateID == nil {
		return nil
	}
	return InventoryFindingAggregate
}

// ValidatedOwnerProof remains an internal fixture boundary for existing API and
// persistence integration tests. Production Kafka delivery always uses the
// strict worker parser and ApplyInventoryFindingMessage.
type ValidatedOwnerProof struct {
	ConsumerName     string
	EventID          uuid.UUID
	BodySHA256       string
	AggregateType    string
	AggregateID      uuid.UUID
	AggregateVersion int64
	OwnerType        string
	OwnerID          string
	WarehouseID      uuid.UUID
	OwnerRevision    int64
	Active           bool
	RecordedAt       time.Time
}

func (repository *Repository) ApplyValidatedOwnerProof(
	ctx context.Context,
	proof ValidatedOwnerProof,
) (bool, error) {
	ownerID, err := uuid.Parse(proof.OwnerID)
	if err != nil || ownerID != proof.AggregateID || proof.AggregateVersion < 0 {
		return false, ErrConflict
	}
	if proof.AggregateVersion > 0 {
		markerID := uuid.NewSHA1(uuid.NameSpaceOID, []byte("inventory-finding-marker:"+proof.AggregateID.String()))
		markerBody, markerHash, marshalErr := canonicalJSON(map[string]any{
			"fixture": "inventory.finding.added.v1", "aggregateId": proof.AggregateID,
		})
		if marshalErr != nil {
			return false, marshalErr
		}
		_, markerErr := repository.ApplyInventoryFindingMessage(ctx, InventoryFindingMessage{
			EventID: markerID, BodySHA256: markerHash, WireBody: markerBody,
			Topic: InventorySessionTopic, EventType: "inventory.finding.added.v1",
			AggregateType: InventoryFindingAggregate, AggregateID: proof.AggregateID,
			AggregateVersion: 0, RecordKey: proof.AggregateID, WarehouseID: proof.WarehouseID,
			RecordedAt: proof.RecordedAt.Add(-time.Nanosecond),
		})
		if markerErr != nil {
			return false, markerErr
		}
	}
	body, hash, err := canonicalJSON(map[string]any{
		"fixture": proof.BodySHA256, "eventId": proof.EventID,
		"ownerId": ownerID, "warehouseId": proof.WarehouseID,
		"ownerRevision": proof.OwnerRevision, "active": proof.Active,
	})
	if err != nil {
		return false, err
	}
	result, err := repository.ApplyInventoryFindingMessage(ctx, InventoryFindingMessage{
		EventID: proof.EventID, BodySHA256: hash, WireBody: body,
		Topic: InventorySessionTopic, EventType: InventoryOwnerProofEvent,
		AggregateType: InventoryFindingAggregate, AggregateID: proof.AggregateID,
		AggregateVersion: proof.AggregateVersion, RecordKey: proof.AggregateID,
		WarehouseID: proof.WarehouseID,
		RecordedAt:  proof.RecordedAt.UTC(), Proof: &InventoryOwnerProof{
			OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID, WarehouseID: proof.WarehouseID,
			OwnerRevision: proof.OwnerRevision, Active: proof.Active,
		},
	})
	if err != nil || !result.Quarantined {
		return result.Duplicate, err
	}
	var reason string
	if queryErr := repository.pool.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2
		  and reconciled_at is null`, InventoryOwnerConsumerGroup, proof.AggregateID).Scan(&reason); queryErr != nil {
		return false, queryErr
	}
	switch reason {
	case "VERSION_GAP", "AGGREGATE_VERSION_REGRESSION":
		return false, ErrVersionGap
	case "EVENT_ID_CONFLICT":
		return false, ErrIdempotencyMismatch
	default:
		return false, ErrOwnerProofConflict
	}
}
