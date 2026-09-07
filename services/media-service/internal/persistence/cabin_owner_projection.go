package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

const (
	CabinOwnerConsumerGroup = "media-service-cabin-owner-v1"
	AssetRentalItemTopic    = "rwms.asset.rental-item.v1"
	CabinOwnerAggregate     = "RENTAL_ITEM"
)

const (
	cabinCreatedEvent         = "asset.rental-item.created.v1"
	cabinPassportEvent        = "asset.rental-item.passport-changed.v1"
	cabinStatusEvent          = "asset.rental-item.status-changed.v1"
	cabinWarehouseEvent       = "asset.rental-item.warehouse-changed.v1"
	cabinLogisticsEffectEvent = "asset.rental-item.logistics-effect-applied.v1"
	// cabinInventoryVisibilityEvent only advances the source stream checkpoint.
	// It must never turn private inventory visibility into CABIN media authority.
	cabinInventoryVisibilityEvent = "asset.rental-item.inventory-visibility-changed.v1"
	cabinCommentEvent             = "asset.rental-item.general-comment-changed.v1"
	cabinNoteEvent                = "asset.rental-item.manual-note-added.v1"
)

// CabinOwnerProof is the authoritative asset rental-item state that binds a
// CABIN to a warehouse and active owner revision.
type CabinOwnerProof struct {
	WarehouseID   uuid.UUID
	Status        string
	OwnerRevision int64
	Active        bool
}

// CabinOwnerMessage is the strictly parsed asset rental-item fact used to
// advance the local CABIN owner projection.
type CabinOwnerMessage struct {
	EventID          uuid.UUID
	BodySHA256       string
	WireBody         []byte
	Topic            string
	EventType        string
	AggregateType    string
	AggregateID      uuid.UUID
	AggregateVersion int64
	RecordKey        uuid.UUID
	PayloadOwnerID   uuid.UUID
	RecordedAt       time.Time
	Proof            *CabinOwnerProof
}

// CabinOwnerApplyResult reports an exact replay or a newly quarantined CABIN
// owner aggregate after applying one fact.
type CabinOwnerApplyResult struct {
	Duplicate   bool
	Quarantined bool
}

// CabinOwnerDeadLetter is the minimal sanitized identity retained for an
// invalid CABIN owner fact.
type CabinOwnerDeadLetter struct {
	EventID    uuid.UUID
	BodySHA256 string
}

// ApplyCabinOwnerMessage advances one CABIN owner stream atomically with
// deduplication, continuity checks, and quarantine handling.
func (repository *Repository) ApplyCabinOwnerMessage(
	ctx context.Context,
	message CabinOwnerMessage,
) (CabinOwnerApplyResult, error) {
	if err := validateCabinOwnerMessage(message); err != nil {
		return CabinOwnerApplyResult{}, err
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return CabinOwnerApplyResult{}, err
	}
	defer tx.Rollback(ctx)
	result, err := repository.applyCabinOwnerMessage(ctx, tx, message, false)
	if err != nil {
		return CabinOwnerApplyResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return CabinOwnerApplyResult{}, translateConstraint(err)
	}
	return result, nil
}

func (repository *Repository) applyCabinOwnerMessage(
	ctx context.Context,
	tx pgx.Tx,
	message CabinOwnerMessage,
	reconciling bool,
) (CabinOwnerApplyResult, error) {
	if _, err := tx.Exec(ctx, `select pg_advisory_xact_lock(hashtextextended($1,0))`,
		"cabin-owner-event:"+message.EventID.String()); err != nil {
		return CabinOwnerApplyResult{}, err
	}

	var priorAggregateID, priorRecordKey uuid.UUID
	var priorAggregateVersion int64
	var priorHash, priorOutcome string
	err := tx.QueryRow(ctx, `select aggregate_id,aggregate_version,record_key,body_sha256,outcome
		from media_cabin_owner_inbox where consumer_name=$1 and event_id=$2 for update`,
		CabinOwnerConsumerGroup, message.EventID).Scan(&priorAggregateID, &priorAggregateVersion,
		&priorRecordKey, &priorHash, &priorOutcome)
	if err == nil {
		if priorHash != message.BodySHA256 || priorAggregateID != message.AggregateID ||
			priorAggregateVersion != message.AggregateVersion || priorRecordKey != message.RecordKey {
			if _, err := tx.Exec(ctx, `insert into media_cabin_owner_event_conflict (
				consumer_name,event_id,prior_aggregate_id,prior_aggregate_version,prior_record_key,
				prior_body_sha256,incoming_aggregate_id,incoming_aggregate_version,
				incoming_record_key,incoming_body_sha256,incoming_wire_body)
			values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
			on conflict (consumer_name,event_id,incoming_body_sha256) do nothing`,
				CabinOwnerConsumerGroup, message.EventID, priorAggregateID, priorAggregateVersion,
				priorRecordKey, priorHash, message.AggregateID, message.AggregateVersion,
				message.RecordKey, message.BodySHA256, message.WireBody); err != nil {
				return CabinOwnerApplyResult{}, err
			}
			if err := repository.openCabinOwnerQuarantine(ctx, tx, priorAggregateID,
				priorAggregateVersion, priorAggregateVersion, "EVENT_ID_CONFLICT", message.EventID); err != nil {
				return CabinOwnerApplyResult{}, err
			}
			if err := repository.openCabinOwnerQuarantine(ctx, tx, message.AggregateID,
				message.AggregateVersion, message.AggregateVersion, "EVENT_ID_CONFLICT", message.EventID); err != nil {
				return CabinOwnerApplyResult{}, err
			}
			if err := deactivateLegacyCabinBinding(ctx, tx, priorAggregateID, message.AggregateID); err != nil {
				return CabinOwnerApplyResult{}, err
			}
			if err := insertCabinOwnerDeadLetter(ctx, tx, CabinOwnerDeadLetter{
				EventID: message.EventID, BodySHA256: message.BodySHA256,
			}); err != nil {
				return CabinOwnerApplyResult{}, err
			}
			return CabinOwnerApplyResult{Quarantined: true}, nil
		}
		if reconciling && priorOutcome == "QUARANTINED" {
			// Reviewed reconciliation may promote these exact retained bytes.
		} else {
			return CabinOwnerApplyResult{
				Duplicate: true, Quarantined: priorOutcome == "QUARANTINED",
			}, nil
		}
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return CabinOwnerApplyResult{}, err
	}

	if !reconciling {
		var blocked bool
		if err := tx.QueryRow(ctx, `select exists(select 1 from media_quarantined_aggregate
			where consumer_name=$1 and aggregate_type='RENTAL_ITEM' and aggregate_id=$2
			  and reconciled_at is null)`, CabinOwnerConsumerGroup,
			message.AggregateID).Scan(&blocked); err != nil {
			return CabinOwnerApplyResult{}, err
		}
		if blocked {
			if err := upsertCabinOwnerInbox(ctx, tx, message, "QUARANTINED",
				"AGGREGATE_ALREADY_QUARANTINED", repository.now().UTC()); err != nil {
				return CabinOwnerApplyResult{}, err
			}
			return CabinOwnerApplyResult{Quarantined: true}, nil
		}
	}

	checkpoint, exists, err := cabinOwnerCheckpoint(ctx, tx, message.AggregateID)
	if err != nil {
		return CabinOwnerApplyResult{}, err
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
		if err := repository.quarantineCabinOwner(ctx, tx, message, expected, reason); err != nil {
			return CabinOwnerApplyResult{}, err
		}
		return CabinOwnerApplyResult{Quarantined: true}, nil
	}
	if (!exists && message.EventType != cabinCreatedEvent) ||
		(exists && message.EventType == cabinCreatedEvent) {
		if err := repository.quarantineCabinOwner(ctx, tx, message,
			message.AggregateVersion, "INVALID_STREAM_BOOTSTRAP"); err != nil {
			return CabinOwnerApplyResult{}, err
		}
		return CabinOwnerApplyResult{Quarantined: true}, nil
	}

	if message.Proof != nil {
		proof := *message.Proof
		reason := ""
		switch {
		case message.PayloadOwnerID != message.AggregateID:
			reason = "OWNER_IDENTITY_CONFLICT"
		case proof.OwnerRevision != message.AggregateVersion:
			reason = "OWNER_REVISION_CONFLICT"
		case !validRentalItemStatus(proof.Status):
			reason = "INVALID_RENTAL_STATUS"
		case proof.Active != (proof.Status != "WRITTEN_OFF"):
			reason = "TERMINAL_STATE_CONFLICT"
		}
		if reason != "" {
			if err := repository.quarantineCabinOwner(ctx, tx, message,
				message.AggregateVersion, reason); err != nil {
				return CabinOwnerApplyResult{}, err
			}
			return CabinOwnerApplyResult{Quarantined: true}, nil
		}
		result, err := repository.applyCabinOwnerProof(ctx, tx, message)
		if err != nil || result.Quarantined {
			return result, err
		}
	}
	if err := upsertCabinOwnerInbox(ctx, tx, message, "APPLIED", "",
		repository.now().UTC()); err != nil {
		return CabinOwnerApplyResult{}, err
	}
	if err := advanceCabinOwnerCheckpoint(ctx, tx, message); err != nil {
		return CabinOwnerApplyResult{}, err
	}
	return CabinOwnerApplyResult{}, nil
}

func (repository *Repository) applyCabinOwnerProof(
	ctx context.Context,
	tx pgx.Tx,
	message CabinOwnerMessage,
) (CabinOwnerApplyResult, error) {
	proof := *message.Proof
	var revision int64
	var warehouseID uuid.UUID
	var active bool
	var proofConsumer string
	err := tx.QueryRow(ctx, `select owner_revision,warehouse_id,active,proof_consumer_name
		from media_owner_binding where owner_type='CABIN' and owner_id=$1 for update`,
		message.AggregateID.String()).Scan(&revision, &warehouseID, &active, &proofConsumer)
	if err == nil {
		reason := ""
		switch {
		case proof.OwnerRevision < revision:
			reason = "OWNER_REVISION_REGRESSION"
		case proof.OwnerRevision == revision &&
			(warehouseID != proof.WarehouseID || active != proof.Active):
			reason = "OWNER_REVISION_CONFLICT"
		case proofConsumer == CabinOwnerConsumerGroup && !active && proof.Active:
			reason = "TERMINAL_REACTIVATION"
		}
		if reason != "" {
			if err := repository.quarantineCabinOwner(ctx, tx, message,
				message.AggregateVersion, reason); err != nil {
				return CabinOwnerApplyResult{}, err
			}
			return CabinOwnerApplyResult{Quarantined: true}, nil
		}
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return CabinOwnerApplyResult{}, err
	}

	_, err = tx.Exec(ctx, `insert into media_owner_binding (
		owner_type,owner_id,warehouse_id,owner_revision,proof_event_id,
		proof_consumer_name,proof_aggregate_type,proof_aggregate_id,
		proof_aggregate_version,proof_recorded_at,active,updated_at)
	values ('CABIN',$1,$2,$3,$4,$5,'RENTAL_ITEM',$6,$7,$8,$9,clock_timestamp())
	on conflict (owner_type,owner_id) do update set
		warehouse_id=excluded.warehouse_id,owner_revision=excluded.owner_revision,
		proof_event_id=excluded.proof_event_id,proof_consumer_name=excluded.proof_consumer_name,
		proof_aggregate_type=excluded.proof_aggregate_type,
		proof_aggregate_id=excluded.proof_aggregate_id,
		proof_aggregate_version=excluded.proof_aggregate_version,
		proof_recorded_at=excluded.proof_recorded_at,active=excluded.active,
		updated_at=clock_timestamp()`, message.AggregateID.String(), proof.WarehouseID,
		proof.OwnerRevision, message.EventID, CabinOwnerConsumerGroup, message.AggregateID,
		message.AggregateVersion, message.RecordedAt.UTC(), proof.Active)
	if err != nil {
		return CabinOwnerApplyResult{}, translateConstraint(err)
	}
	if _, err := tx.Exec(ctx, `update media_cabin_photo
		set warehouse_id=$2 where cabin_id=$1 and warehouse_id<>$2`,
		message.AggregateID, proof.WarehouseID); err != nil {
		return CabinOwnerApplyResult{}, err
	}
	if _, err := tx.Exec(ctx, `update media_cabin_photo_library
		set warehouse_id=$2,updated_at=clock_timestamp()
		where cabin_id=$1 and warehouse_id<>$2`,
		message.AggregateID, proof.WarehouseID); err != nil {
		return CabinOwnerApplyResult{}, err
	}
	return CabinOwnerApplyResult{}, nil
}

func cabinOwnerCheckpoint(
	ctx context.Context,
	tx pgx.Tx,
	aggregateID uuid.UUID,
) (int64, bool, error) {
	var checkpoint int64
	err := tx.QueryRow(ctx, `select aggregate_version from media_consumer_aggregate_checkpoint
		where consumer_name=$1 and aggregate_type='RENTAL_ITEM' and aggregate_id=$2 for update`,
		CabinOwnerConsumerGroup, aggregateID).Scan(&checkpoint)
	if errors.Is(err, pgx.ErrNoRows) {
		return -1, false, nil
	}
	return checkpoint, err == nil, err
}

func advanceCabinOwnerCheckpoint(ctx context.Context, tx pgx.Tx, message CabinOwnerMessage) error {
	_, err := tx.Exec(ctx, `insert into media_consumer_aggregate_checkpoint (
		consumer_name,aggregate_type,aggregate_id,aggregate_version)
	values ($1,'RENTAL_ITEM',$2,$3)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		aggregate_version=excluded.aggregate_version,updated_at=clock_timestamp()`,
		CabinOwnerConsumerGroup, message.AggregateID, message.AggregateVersion)
	return err
}

func upsertCabinOwnerInbox(
	ctx context.Context,
	tx pgx.Tx,
	message CabinOwnerMessage,
	outcome string,
	failureCode string,
	processed time.Time,
) error {
	var warehouseID, status, ownerRevision, active, processedAt, failure any
	if message.Proof != nil {
		warehouseID = message.Proof.WarehouseID
		status = message.Proof.Status
		ownerRevision = message.Proof.OwnerRevision
		active = message.Proof.Active
	}
	if outcome == "APPLIED" {
		processedAt = processed
	} else {
		failure = failureCode
	}
	_, err := tx.Exec(ctx, `insert into media_cabin_owner_inbox (
		consumer_name,event_id,topic,event_type,aggregate_type,aggregate_id,aggregate_version,
		record_key,payload_owner_id,warehouse_id,rental_status,owner_revision,active,
		body_sha256,wire_body,outcome,failure_code,processed_at)
	values ($1,$2,$3,$4,'RENTAL_ITEM',$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17)
	on conflict (consumer_name,event_id) do update set outcome=excluded.outcome,
		failure_code=excluded.failure_code,processed_at=excluded.processed_at
	where media_cabin_owner_inbox.body_sha256=excluded.body_sha256`, CabinOwnerConsumerGroup,
		message.EventID, message.Topic, message.EventType, message.AggregateID,
		message.AggregateVersion, message.RecordKey, message.PayloadOwnerID, warehouseID,
		status, ownerRevision, active, message.BodySHA256, message.WireBody, outcome,
		failure, processedAt)
	return translateConstraint(err)
}

func (repository *Repository) quarantineCabinOwner(
	ctx context.Context,
	tx pgx.Tx,
	message CabinOwnerMessage,
	expected int64,
	reason string,
) error {
	if err := upsertCabinOwnerInbox(ctx, tx, message, "QUARANTINED", reason,
		repository.now().UTC()); err != nil {
		return err
	}
	if err := repository.openCabinOwnerQuarantine(ctx, tx, message.AggregateID,
		expected, message.AggregateVersion, reason, message.EventID); err != nil {
		return err
	}
	return deactivateLegacyCabinBinding(ctx, tx, message.AggregateID)
}

func (repository *Repository) openCabinOwnerQuarantine(
	ctx context.Context,
	tx pgx.Tx,
	aggregateID uuid.UUID,
	expected, observed int64,
	reason string,
	eventID uuid.UUID,
) error {
	_, err := tx.Exec(ctx, `insert into media_quarantined_aggregate (
		consumer_name,aggregate_type,aggregate_id,expected_version,observed_version,
		reason_code,first_event_id)
	values ($1,'RENTAL_ITEM',$2,$3,$4,$5,$6)
	on conflict (consumer_name,aggregate_type,aggregate_id) do update set
		expected_version=excluded.expected_version,observed_version=excluded.observed_version,
		reason_code=excluded.reason_code,first_event_id=excluded.first_event_id,
		quarantined_at=clock_timestamp(),reconciled_at=null,resolution_reason=null,
		resolved_by_subject_id=null`, CabinOwnerConsumerGroup, aggregateID, expected,
		observed, reason, eventID)
	return err
}

func deactivateLegacyCabinBinding(ctx context.Context, tx pgx.Tx, aggregateIDs ...uuid.UUID) error {
	for _, aggregateID := range aggregateIDs {
		if aggregateID == uuid.Nil {
			continue
		}
		if _, err := tx.Exec(ctx, `update media_owner_binding
			set active=false,updated_at=clock_timestamp()
			where owner_type='CABIN' and owner_id=$1 and proof_consumer_name<>$2`,
			aggregateID.String(), CabinOwnerConsumerGroup); err != nil {
			return err
		}
	}
	return nil
}

func validateCabinOwnerMessage(message CabinOwnerMessage) error {
	if message.EventID == uuid.Nil || message.Topic != AssetRentalItemTopic ||
		message.AggregateType != CabinOwnerAggregate || message.AggregateID == uuid.Nil ||
		message.AggregateVersion < 0 || message.RecordKey != message.AggregateID ||
		message.PayloadOwnerID == uuid.Nil || message.RecordedAt.IsZero() ||
		len(message.WireBody) == 0 || len(message.WireBody) > 1<<20 ||
		!validSHA256(message.BodySHA256) {
		return ErrConflict
	}
	sum := sha256.Sum256(message.WireBody)
	if hex.EncodeToString(sum[:]) != message.BodySHA256 {
		return ErrIdempotencyMismatch
	}
	switch message.EventType {
	case cabinCreatedEvent, cabinPassportEvent, cabinStatusEvent, cabinWarehouseEvent,
		cabinLogisticsEffectEvent:
		if message.Proof == nil || message.Proof.WarehouseID == uuid.Nil ||
			strings.TrimSpace(message.Proof.Status) == "" || message.Proof.OwnerRevision < 0 {
			return ErrConflict
		}
	case cabinInventoryVisibilityEvent, cabinCommentEvent, cabinNoteEvent:
		if message.Proof != nil {
			return ErrConflict
		}
	default:
		return ErrConflict
	}
	return nil
}

func validRentalItemStatus(status string) bool {
	switch status {
	case "RENTED", "BOOKED", "REPAIR", "WAITING_REPAIR_CHECK", "WRITTEN_OFF",
		"CAPITAL_REPAIR", "AFTER_RENT", "WAITING_ESTIMATE_CONFIRMATION", "SALE",
		"USED_SALE", "RESERVED", "FREE", "WAREHOUSE", "OWN_NEEDS", "IN_TRANSFER":
		return true
	default:
		return false
	}
}

// RecordCabinOwnerDLT persists a sanitized dead-letter identity for an invalid
// CABIN owner fact.
func (repository *Repository) RecordCabinOwnerDLT(
	ctx context.Context,
	message CabinOwnerDeadLetter,
) error {
	if !validSHA256(message.BodySHA256) {
		return ErrConflict
	}
	if message.EventID == uuid.Nil {
		message.EventID = uuid.NewSHA1(uuid.NameSpaceOID,
			[]byte("invalid-cabin-owner:"+message.BodySHA256))
	}
	_, err := repository.pool.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,body_sha256,failure_code,attempt_count)
	values ($1,$2,$3,'INVALID_ASSET_CABIN_OWNER_FACT',0)
	on conflict (consumer_name,event_id) do nothing`, CabinOwnerConsumerGroup,
		message.EventID, message.BodySHA256)
	return translateConstraint(err)
}

func insertCabinOwnerDeadLetter(
	ctx context.Context,
	tx pgx.Tx,
	message CabinOwnerDeadLetter,
) error {
	_, err := tx.Exec(ctx, `insert into media_dead_letter (
		consumer_name,event_id,aggregate_type,body_sha256,failure_code,attempt_count)
	values ($1,$2,'RENTAL_ITEM',$3,'CABIN_OWNER_EVENT_ID_CONFLICT',1)
	on conflict (consumer_name,event_id) do nothing`, CabinOwnerConsumerGroup,
		message.EventID, message.BodySHA256)
	return translateConstraint(err)
}
