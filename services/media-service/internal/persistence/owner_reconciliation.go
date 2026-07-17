package persistence

import (
	"bytes"
	"context"
	"errors"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// ReconcileInventoryFindingConflict verifies the authoritative bytes of an
// already-applied event without moving its checkpoint. Only the prior side of
// a cross-aggregate event-ID conflict is released; the incoming aggregate must
// still be repaired through the normal contiguous reconciliation flow with a
// different authoritative event ID.
func (repository *Repository) ReconcileInventoryFindingConflict(
	ctx context.Context,
	aggregateID uuid.UUID,
	expectedCheckpointVersion int64,
	reviewerID uuid.UUID,
	reason string,
	authoritative InventoryFindingMessage,
) error {
	reason = strings.TrimSpace(reason)
	if aggregateID == uuid.Nil || reviewerID == uuid.Nil || expectedCheckpointVersion < 0 ||
		reason == "" || len(reason) > 500 || authoritative.AggregateID != aggregateID ||
		validateInventoryFindingMessage(authoritative) != nil {
		return ErrReconciliation
	}
	tx, err := repository.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var quarantineReason string
	if err := tx.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2
		  and reconciled_at is null for update`, InventoryOwnerConsumerGroup, aggregateID).
		Scan(&quarantineReason); err != nil || quarantineReason != "EVENT_ID_CONFLICT" {
		return ErrReconciliation
	}
	checkpoint, exists, err := inventoryCheckpoint(ctx, tx, aggregateID)
	if err != nil || !exists || checkpoint != expectedCheckpointVersion ||
		authoritative.AggregateVersion > checkpoint {
		return ErrReconciliation
	}
	var eventType string
	var aggregateVersion int64
	var recordKey uuid.UUID
	var warehouseID uuid.UUID
	var bodySHA256 string
	var wireBody []byte
	var outcome string
	if err := tx.QueryRow(ctx, `select event_type,aggregate_version,record_key,warehouse_id,
		body_sha256,wire_body,outcome from media_inventory_finding_inbox
		where consumer_name=$1 and event_id=$2 and aggregate_id=$3 for update`,
		InventoryOwnerConsumerGroup, authoritative.EventID, aggregateID).
		Scan(&eventType, &aggregateVersion, &recordKey, &warehouseID, &bodySHA256,
			&wireBody, &outcome); err != nil {
		return ErrReconciliation
	}
	if eventType != authoritative.EventType || aggregateVersion != authoritative.AggregateVersion ||
		recordKey != authoritative.RecordKey || warehouseID != authoritative.WarehouseID ||
		bodySHA256 != authoritative.BodySHA256 || !bytes.Equal(wireBody, authoritative.WireBody) ||
		outcome != "APPLIED" {
		return ErrReconciliation
	}
	command, err := tx.Exec(ctx, `update media_inventory_finding_event_conflict
		set prior_reconciled_at=clock_timestamp(),prior_resolution_reason=$4,
			prior_resolved_by_subject_id=$5
		where consumer_name=$1 and event_id=$2 and prior_aggregate_id=$3
		  and prior_body_sha256=$6 and prior_aggregate_version=$7
		  and prior_record_key=$3 and prior_reconciled_at is null`,
		InventoryOwnerConsumerGroup, authoritative.EventID, aggregateID, reason, reviewerID,
		authoritative.BodySHA256, authoritative.AggregateVersion)
	if err != nil || command.RowsAffected() == 0 {
		return ErrReconciliation
	}
	command, err = tx.Exec(ctx, `update media_quarantined_aggregate
		set reconciled_at=clock_timestamp(),resolution_reason=$3,resolved_by_subject_id=$4
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2
		  and reconciled_at is null and reason_code='EVENT_ID_CONFLICT'`,
		InventoryOwnerConsumerGroup, aggregateID, reason, reviewerID)
	if err != nil || command.RowsAffected() != 1 {
		return ErrReconciliation
	}
	return tx.Commit(ctx)
}

// ReconcileInventoryFindingAggregate applies a reviewed, fully validated,
// contiguous batch from the inventory event-store authority. The open
// quarantine keeps public media operations fail-closed until this transaction
// has applied the missing records and the previously quarantined tail.
func (repository *Repository) ReconcileInventoryFindingAggregate(
	ctx context.Context,
	aggregateID uuid.UUID,
	expectedCheckpointVersion int64,
	reviewerID uuid.UUID,
	reason string,
	messages []InventoryFindingMessage,
) error {
	reason = strings.TrimSpace(reason)
	if aggregateID == uuid.Nil || reviewerID == uuid.Nil || expectedCheckpointVersion < -1 ||
		reason == "" || len(reason) > 500 || len(messages) == 0 {
		return ErrReconciliation
	}
	for _, message := range messages {
		if err := validateInventoryFindingMessage(message); err != nil || message.AggregateID != aggregateID {
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
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2
		  and reconciled_at is null for update`, InventoryOwnerConsumerGroup, aggregateID).
		Scan(&observedVersion)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrReconciliation
	}
	if err != nil {
		return err
	}
	checkpoint, exists, err := inventoryCheckpoint(ctx, tx, aggregateID)
	if err != nil {
		return err
	}
	if !exists {
		checkpoint = -1
	}
	if checkpoint != expectedCheckpointVersion || messages[0].AggregateVersion != checkpoint+1 {
		return ErrReconciliation
	}
	var quarantinedTail int64
	if err := tx.QueryRow(ctx, `select coalesce(max(aggregate_version),$3)
		from media_inventory_finding_inbox where consumer_name=$1 and aggregate_id=$2
		  and outcome='QUARANTINED'`, InventoryOwnerConsumerGroup, aggregateID, observedVersion).
		Scan(&quarantinedTail); err != nil {
		return err
	}
	if messages[len(messages)-1].AggregateVersion != quarantinedTail {
		return ErrReconciliation
	}
	for index, message := range messages {
		if message.AggregateVersion != checkpoint+1+int64(index) {
			return ErrReconciliation
		}
		result, err := repository.applyInventoryFindingMessage(ctx, tx, message, true)
		if err != nil {
			return err
		}
		if result.Quarantined {
			return ErrReconciliation
		}
	}
	command, err := tx.Exec(ctx, `update media_quarantined_aggregate
		set reconciled_at=clock_timestamp(),resolution_reason=$3,resolved_by_subject_id=$4
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2
		  and reconciled_at is null and observed_version=$5`, InventoryOwnerConsumerGroup,
		aggregateID, reason, reviewerID, observedVersion)
	if err != nil {
		return err
	}
	if command.RowsAffected() != 1 {
		return ErrReconciliation
	}
	return tx.Commit(ctx)
}
