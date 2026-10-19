package persistence

import (
	"bytes"
	"context"
	"errors"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// ReconcileCabinOwnerAggregate applies a reviewer-supplied contiguous slice of
// the authoritative asset event stream. The open quarantine keeps every public
// CABIN media path fail-closed until this transaction is complete.
func (repository *Repository) ReconcileCabinOwnerAggregate(
	ctx context.Context,
	aggregateID uuid.UUID,
	expectedCheckpointVersion int64,
	reviewerID uuid.UUID,
	reason string,
	messages []CabinOwnerMessage,
) error {
	reason = strings.TrimSpace(reason)
	if aggregateID == uuid.Nil || reviewerID == uuid.Nil || expectedCheckpointVersion < -1 ||
		reason == "" || len(reason) > 500 || len(messages) == 0 {
		return ErrReconciliation
	}
	for _, message := range messages {
		if validateCabinOwnerMessage(message) != nil || message.AggregateID != aggregateID {
			return ErrReconciliation
		}
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var observedVersion int64
	err = tx.QueryRow(ctx, `select observed_version from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='RENTAL_ITEM' and aggregate_id=$2
		  and reconciled_at is null for update`, CabinOwnerConsumerGroup, aggregateID).
		Scan(&observedVersion)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrReconciliation
	}
	if err != nil {
		return err
	}
	checkpoint, exists, err := cabinOwnerCheckpoint(ctx, tx, aggregateID)
	if err != nil {
		return err
	}
	if !exists {
		checkpoint = -1
	}
	if checkpoint != expectedCheckpointVersion ||
		messages[0].AggregateVersion != checkpoint+1 {
		return ErrReconciliation
	}
	var quarantinedTail int64
	if err := tx.QueryRow(ctx, `select coalesce(max(aggregate_version),$3)
		from media_cabin_owner_inbox where consumer_name=$1 and aggregate_id=$2
		  and outcome='QUARANTINED'`, CabinOwnerConsumerGroup, aggregateID,
		observedVersion).Scan(&quarantinedTail); err != nil {
		return err
	}
	if messages[len(messages)-1].AggregateVersion != quarantinedTail {
		return ErrReconciliation
	}
	for index, message := range messages {
		if message.AggregateVersion != checkpoint+1+int64(index) {
			return ErrReconciliation
		}
		result, err := repository.applyCabinOwnerMessage(ctx, tx, message, true)
		if err != nil || result.Quarantined {
			if err != nil {
				return err
			}
			return ErrReconciliation
		}
	}
	command, err := tx.Exec(ctx, `update media_quarantined_aggregate
		set reconciled_at=clock_timestamp(),resolution_reason=$3,resolved_by_subject_id=$4
		where consumer_name=$1 and aggregate_type='RENTAL_ITEM' and aggregate_id=$2
		  and reconciled_at is null and observed_version=$5`, CabinOwnerConsumerGroup,
		aggregateID, reason, reviewerID, observedVersion)
	if err != nil || command.RowsAffected() != 1 {
		return ErrReconciliation
	}
	return tx.Commit(ctx)
}

// ReconcileCabinOwnerConflict verifies the immutable bytes of an already
// applied prior event-ID claimant without advancing its checkpoint. The
// conflicting incoming aggregate still requires a replacement event ID and
// normal contiguous reconciliation.
func (repository *Repository) ReconcileCabinOwnerConflict(
	ctx context.Context,
	aggregateID uuid.UUID,
	expectedCheckpointVersion int64,
	reviewerID uuid.UUID,
	reason string,
	authoritative CabinOwnerMessage,
) error {
	reason = strings.TrimSpace(reason)
	if aggregateID == uuid.Nil || reviewerID == uuid.Nil || expectedCheckpointVersion < 0 ||
		reason == "" || len(reason) > 500 || authoritative.AggregateID != aggregateID ||
		validateCabinOwnerMessage(authoritative) != nil {
		return ErrReconciliation
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var quarantineReason string
	if err := tx.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='RENTAL_ITEM' and aggregate_id=$2
		  and reconciled_at is null for update`, CabinOwnerConsumerGroup, aggregateID).
		Scan(&quarantineReason); err != nil || quarantineReason != "EVENT_ID_CONFLICT" {
		return ErrReconciliation
	}
	checkpoint, exists, err := cabinOwnerCheckpoint(ctx, tx, aggregateID)
	if err != nil || !exists || checkpoint != expectedCheckpointVersion ||
		authoritative.AggregateVersion > checkpoint {
		return ErrReconciliation
	}
	var aggregateVersion int64
	var recordKey uuid.UUID
	var bodySHA256, outcome string
	var wireBody []byte
	if err := tx.QueryRow(ctx, `select aggregate_version,record_key,body_sha256,wire_body,outcome
		from media_cabin_owner_inbox where consumer_name=$1 and event_id=$2
		  and aggregate_id=$3 for update`, CabinOwnerConsumerGroup, authoritative.EventID,
		aggregateID).Scan(&aggregateVersion, &recordKey, &bodySHA256, &wireBody,
		&outcome); err != nil {
		return ErrReconciliation
	}
	if aggregateVersion != authoritative.AggregateVersion || recordKey != authoritative.RecordKey ||
		bodySHA256 != authoritative.BodySHA256 || !bytes.Equal(wireBody, authoritative.WireBody) ||
		outcome != "APPLIED" {
		return ErrReconciliation
	}
	command, err := tx.Exec(ctx, `update media_cabin_owner_event_conflict
		set prior_reconciled_at=clock_timestamp(),prior_resolution_reason=$4,
			prior_resolved_by_subject_id=$5
		where consumer_name=$1 and event_id=$2 and prior_aggregate_id=$3
		  and prior_body_sha256=$6 and prior_aggregate_version=$7
		  and prior_record_key=$3 and prior_reconciled_at is null`, CabinOwnerConsumerGroup,
		authoritative.EventID, aggregateID, reason, reviewerID,
		authoritative.BodySHA256, authoritative.AggregateVersion)
	if err != nil || command.RowsAffected() == 0 {
		return ErrReconciliation
	}
	command, err = tx.Exec(ctx, `update media_quarantined_aggregate
		set reconciled_at=clock_timestamp(),resolution_reason=$3,resolved_by_subject_id=$4
		where consumer_name=$1 and aggregate_type='RENTAL_ITEM' and aggregate_id=$2
		  and reconciled_at is null and reason_code='EVENT_ID_CONFLICT'`,
		CabinOwnerConsumerGroup, aggregateID, reason, reviewerID)
	if err != nil || command.RowsAffected() != 1 {
		return ErrReconciliation
	}
	return tx.Commit(ctx)
}
