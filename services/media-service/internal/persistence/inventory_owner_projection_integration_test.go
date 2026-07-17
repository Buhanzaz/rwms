package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestInventoryOwnerProjectionLifecycleQuarantineAndReconciliationIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	ownerID, warehouseID := uuid.New(), uuid.New()
	marker0 := inventoryMarker(ownerID, warehouseID, 0)
	proof1 := inventoryProof(ownerID, warehouseID, 1, 0, true)
	marker2 := inventoryMarker(ownerID, warehouseID, 2)
	inactive3 := inventoryProof(ownerID, warehouseID, 3, 1, false)
	for _, message := range []InventoryFindingMessage{marker0, proof1, marker2, inactive3} {
		if result, err := repository.ApplyInventoryFindingMessage(ctx, message); err != nil || result.Quarantined {
			t.Fatalf("apply ordered message v%d = %#v, %v", message.AggregateVersion, result, err)
		}
	}
	if result, err := repository.ApplyInventoryFindingMessage(ctx, proof1); err != nil || !result.Duplicate {
		t.Fatalf("proof duplicate = %#v, %v", result, err)
	}
	if _, _, err := repository.CreateUpload(ctx, createCommand(ownerID, warehouseID, media.KindImage, 0)); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("inactive owner create error = %v", err)
	}
	reactivated4 := inventoryProof(ownerID, warehouseID, 4, 2, true)
	if result, err := repository.ApplyInventoryFindingMessage(ctx, reactivated4); err != nil || result.Quarantined {
		t.Fatalf("reactivate = %#v, %v", result, err)
	}
	if _, _, err := repository.CreateUpload(ctx, createCommand(ownerID, warehouseID, media.KindImage, 0)); err != nil {
		t.Fatalf("reactivated owner create error = %v", err)
	}

	gapOwner, gapWarehouse := uuid.New(), uuid.New()
	gapMarker := inventoryMarker(gapOwner, gapWarehouse, 0)
	if _, err := repository.ApplyInventoryFindingMessage(ctx, gapMarker); err != nil {
		t.Fatal(err)
	}
	missing1 := inventoryProof(gapOwner, gapWarehouse, 1, 0, true)
	gap2 := inventoryProof(gapOwner, gapWarehouse, 2, 1, true)
	blocked3 := inventoryProof(gapOwner, gapWarehouse, 3, 2, true)
	if result, err := repository.ApplyInventoryFindingMessage(ctx, gap2); err != nil || !result.Quarantined {
		t.Fatalf("gap = %#v, %v", result, err)
	}
	if result, err := repository.ApplyInventoryFindingMessage(ctx, blocked3); err != nil || !result.Quarantined {
		t.Fatalf("blocked later = %#v, %v", result, err)
	}
	if _, _, err := repository.CreateUpload(ctx, createCommand(gapOwner, gapWarehouse, media.KindImage, 0)); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("quarantined owner create error = %v", err)
	}
	if err := repository.ReconcileInventoryFindingAggregate(ctx, gapOwner, 0, uuid.New(),
		"reviewed inventory event-store replay", []InventoryFindingMessage{missing1, gap2, blocked3}); err != nil {
		t.Fatalf("reconcile gap: %v", err)
	}
	if _, _, err := repository.CreateUpload(ctx, createCommand(gapOwner, gapWarehouse, media.KindImage, 0)); err != nil {
		t.Fatalf("reconciled owner create error = %v", err)
	}

	regressionOwner, regressionWarehouse := uuid.New(), uuid.New()
	for _, message := range []InventoryFindingMessage{
		inventoryMarker(regressionOwner, regressionWarehouse, 0),
		inventoryProof(regressionOwner, regressionWarehouse, 1, 2, true),
	} {
		if _, err := repository.ApplyInventoryFindingMessage(ctx, message); err != nil {
			t.Fatal(err)
		}
	}
	regression := inventoryProof(regressionOwner, regressionWarehouse, 2, 1, true)
	if result, err := repository.ApplyInventoryFindingMessage(ctx, regression); err != nil || !result.Quarantined {
		t.Fatalf("owner regression = %#v, %v", result, err)
	}
	var reason string
	if err := database.Pool.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2
		  and reconciled_at is null`, InventoryOwnerConsumerGroup, regressionOwner).Scan(&reason); err != nil {
		t.Fatal(err)
	}
	if reason != "OWNER_REVISION_REGRESSION" {
		t.Fatalf("regression quarantine reason = %s", reason)
	}

	conflictOwner, conflictWarehouse := uuid.New(), uuid.New()
	for _, message := range []InventoryFindingMessage{
		inventoryMarker(conflictOwner, conflictWarehouse, 0),
		inventoryProof(conflictOwner, conflictWarehouse, 1, 4, true),
	} {
		if _, err := repository.ApplyInventoryFindingMessage(ctx, message); err != nil {
			t.Fatal(err)
		}
	}
	conflict := inventoryProof(conflictOwner, uuid.New(), 2, 4, true)
	if result, err := repository.ApplyInventoryFindingMessage(ctx, conflict); err != nil || !result.Quarantined {
		t.Fatalf("equal revision conflict = %#v, %v", result, err)
	}
	var appliedCount int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_inventory_finding_inbox
		where consumer_name=$1 and aggregate_id=$2 and outcome='APPLIED'`,
		InventoryOwnerConsumerGroup, ownerID).Scan(&appliedCount); err != nil {
		t.Fatal(err)
	}
	if appliedCount != 5 {
		t.Fatalf("ordered applied inbox rows = %d, want 5", appliedCount)
	}
	invalidSum := sha256.Sum256([]byte("invalid-owner-fact"))
	invalidHash := hex.EncodeToString(invalidSum[:])
	invalidKey := uuid.NewSHA1(uuid.NameSpaceOID, []byte(invalidHash))
	if err := repository.RecordInventoryOwnerDLT(ctx, InventoryOwnerDLTMessage{
		BodySHA256: invalidHash, RecordKey: invalidKey,
		FailureCode: "INVALID_INVENTORY_OWNER_FACT", AttemptCount: 0,
	}); err != nil {
		t.Fatalf("record invalid owner DLT: %v", err)
	}
	var topic string
	var body []byte
	if err := database.Pool.QueryRow(ctx, `select topic,wire_body from media_transport_outbox
		where aggregate_type='INVENTORY_OWNER_DLT' and record_key=$1`, invalidKey).
		Scan(&topic, &body); err != nil {
		t.Fatalf("read owner DLT outbox: %v", err)
	}
	if topic != InventoryOwnerDLTTopic || strings.Contains(string(body), "ownerId") ||
		strings.Contains(string(body), "warehouseId") || strings.Contains(string(body), "payload") {
		t.Fatalf("unsanitized owner DLT = topic:%s body:%s", topic, body)
	}
}

func TestExhaustedDeactivationAtomicallyInvalidatesOwnerUntilReviewedReconciliationIntegration(
	t *testing.T,
) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	ownerID, warehouseID := uuid.New(), uuid.New()
	for _, message := range []InventoryFindingMessage{
		inventoryMarker(ownerID, warehouseID, 0),
		inventoryProof(ownerID, warehouseID, 1, 0, true),
	} {
		if _, err := repository.ApplyInventoryFindingMessage(ctx, message); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, err := repository.CreateUpload(
		ctx, createCommand(ownerID, warehouseID, media.KindImage, 0)); err != nil {
		t.Fatalf("active owner create error = %v", err)
	}

	deactivation := inventoryProof(ownerID, warehouseID, 2, 1, false)
	if err := repository.QuarantineInventoryOwnerProcessingFailure(ctx, deactivation, 4); err != nil {
		t.Fatalf("terminal quarantine: %v", err)
	}
	var active bool
	var reason, outcome string
	var dltCount, retryCount int
	if err := database.Pool.QueryRow(ctx, `select
		binding.active,quarantine.reason_code,inbox.outcome,
		(select count(*) from media_dead_letter where consumer_name=$1 and event_id=$2),
		(select count(*) from media_retry_schedule where consumer_name=$1 and event_id=$2)
		from media_owner_binding binding
		join media_quarantined_aggregate quarantine on quarantine.consumer_name=$1
		 and quarantine.aggregate_type='FINDING' and quarantine.aggregate_id=$3
		 and quarantine.reconciled_at is null
		join media_inventory_finding_inbox inbox on inbox.consumer_name=$1
		 and inbox.event_id=$2
		where binding.owner_type=$4 and binding.owner_id=$5`,
		InventoryOwnerConsumerGroup, deactivation.EventID, ownerID,
		OwnerTypeInventoryFinding, ownerID.String()).
		Scan(&active, &reason, &outcome, &dltCount, &retryCount); err != nil {
		t.Fatal(err)
	}
	if active || reason != "OWNER_PROOF_PROCESSING_FAILED" || outcome != "QUARANTINED" ||
		dltCount != 1 || retryCount != 0 {
		t.Fatalf("terminal state active=%v reason=%s outcome=%s DLT=%d retry=%d",
			active, reason, outcome, dltCount, retryCount)
	}
	if _, _, err := repository.CreateUpload(
		ctx, createCommand(ownerID, warehouseID, media.KindImage, 0)); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("quarantined stale owner create error = %v", err)
	}
	if err := repository.ReconcileInventoryFindingAggregate(ctx, ownerID, 1, uuid.New(),
		"reviewed authoritative deactivation", []InventoryFindingMessage{deactivation}); err != nil {
		t.Fatalf("review deactivation: %v", err)
	}
	if _, _, err := repository.CreateUpload(
		ctx, createCommand(ownerID, warehouseID, media.KindImage, 0)); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("reviewed inactive owner create error = %v", err)
	}
}

func TestDurableRetryEventIDBodyReuseAtomicallyFailsClosedBothKnownOwnersIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	priorOwner, priorWarehouse := uuid.New(), uuid.New()
	incomingOwner, incomingWarehouse := uuid.New(), uuid.New()
	for _, identity := range []struct {
		ownerID     uuid.UUID
		warehouseID uuid.UUID
	}{
		{priorOwner, priorWarehouse},
		{incomingOwner, incomingWarehouse},
	} {
		for _, message := range []InventoryFindingMessage{
			inventoryMarker(identity.ownerID, identity.warehouseID, 0),
			inventoryProof(identity.ownerID, identity.warehouseID, 1, 0, true),
		} {
			if result, applyErr := repository.ApplyInventoryFindingMessage(ctx, message); applyErr != nil || result.Quarantined {
				t.Fatalf("activate owner %s: result=%#v error=%v", identity.ownerID, result, applyErr)
			}
		}
	}

	prior := inventoryProof(priorOwner, priorWarehouse, 2, 1, false)
	if err := repository.ScheduleInventoryOwnerRetry(ctx, prior, 1, time.Second); err != nil {
		t.Fatalf("schedule prior retry identity: %v", err)
	}
	incoming := inventoryProof(incomingOwner, incomingWarehouse, 2, 1, false)
	incoming.EventID = prior.EventID
	incoming.WireBody = []byte(incoming.EventID.String() + ":" + incomingOwner.String() + ":" +
		incomingWarehouse.String() + ":conflicting-durable-retry-body")
	incomingSum := sha256.Sum256(incoming.WireBody)
	incoming.BodySHA256 = hex.EncodeToString(incomingSum[:])
	if state, found, stateErr := repository.InventoryOwnerRetryState(ctx,
		incoming.EventID, incoming.BodySHA256); !found || !errors.Is(stateErr, ErrIdempotencyMismatch) ||
		state.AggregateID != priorOwner || state.AggregateVersion != 2 ||
		state.RecordKey != priorOwner || state.WarehouseID != priorWarehouse {
		t.Fatalf("retry identity mismatch state=%#v found=%v error=%v", state, found, stateErr)
	}
	if err := repository.QuarantineInventoryOwnerRetryIdentityConflict(ctx, incoming); err != nil {
		t.Fatalf("quarantine retry identity conflict: %v", err)
	}

	var inactiveBindings, openQuarantines, conflicts, deadLetters, retries, quarantinedInbox int
	if err := database.Pool.QueryRow(ctx, `select
		(select count(*) from media_owner_binding where owner_type=$1
		 and owner_id in ($2,$3) and not active),
		(select count(*) from media_quarantined_aggregate where consumer_name=$4
		 and aggregate_type='FINDING' and aggregate_id in ($5,$6)
		 and reason_code='EVENT_ID_CONFLICT' and reconciled_at is null),
		(select count(*) from media_inventory_finding_event_conflict where consumer_name=$4
		 and event_id=$7 and prior_aggregate_id=$5 and incoming_aggregate_id=$6),
		(select count(*) from media_dead_letter where consumer_name=$4 and event_id=$7
		 and failure_code='OWNER_EVENT_ID_CONFLICT'),
		(select count(*) from media_retry_schedule where consumer_name=$4 and event_id=$7),
		(select count(*) from media_inventory_finding_inbox where consumer_name=$4
		 and event_id=$7 and aggregate_id=$6 and outcome='QUARANTINED'
		 and failure_code='EVENT_ID_CONFLICT')`, OwnerTypeInventoryFinding,
		priorOwner.String(), incomingOwner.String(), InventoryOwnerConsumerGroup,
		priorOwner, incomingOwner, prior.EventID).Scan(&inactiveBindings, &openQuarantines,
		&conflicts, &deadLetters, &retries, &quarantinedInbox); err != nil {
		t.Fatal(err)
	}
	if inactiveBindings != 2 || openQuarantines != 2 || conflicts != 1 || deadLetters != 1 ||
		retries != 0 || quarantinedInbox != 1 {
		t.Fatalf("retry conflict state inactive=%d quarantine=%d evidence=%d DLT=%d retry=%d inbox=%d",
			inactiveBindings, openQuarantines, conflicts, deadLetters, retries, quarantinedInbox)
	}
	for _, identity := range []struct {
		ownerID     uuid.UUID
		warehouseID uuid.UUID
	}{{priorOwner, priorWarehouse}, {incomingOwner, incomingWarehouse}} {
		if _, _, createErr := repository.CreateUpload(ctx,
			createCommand(identity.ownerID, identity.warehouseID, media.KindImage, 0)); !errors.Is(createErr, ErrOwnerProofMissing) {
			t.Fatalf("conflicted owner %s authorization error=%v", identity.ownerID, createErr)
		}
	}
}

func inventoryMarker(ownerID, warehouseID uuid.UUID, version int64) InventoryFindingMessage {
	eventType := "inventory.finding.inspection-saved.v1"
	if version == 0 {
		eventType = "inventory.finding.added.v1"
	}
	return inventoryFindingMessage(ownerID, warehouseID, version,
		eventType, nil)
}

func inventoryProof(
	ownerID, warehouseID uuid.UUID,
	version, ownerRevision int64,
	active bool,
) InventoryFindingMessage {
	proof := &InventoryOwnerProof{
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID, WarehouseID: warehouseID,
		OwnerRevision: ownerRevision, Active: active,
	}
	return inventoryFindingMessage(ownerID, warehouseID, version, InventoryOwnerProofEvent, proof)
}

func inventoryFindingMessage(
	ownerID, warehouseID uuid.UUID,
	version int64,
	eventType string,
	proof *InventoryOwnerProof,
) InventoryFindingMessage {
	eventID := uuid.New()
	body := []byte(eventID.String() + ":" + ownerID.String() + ":" + warehouseID.String() + ":" +
		eventType + ":" + time.Now().UTC().Format(time.RFC3339Nano))
	sum := sha256.Sum256(body)
	return InventoryFindingMessage{
		EventID: eventID, BodySHA256: hex.EncodeToString(sum[:]), WireBody: body,
		Topic: InventorySessionTopic, EventType: eventType, AggregateType: InventoryFindingAggregate,
		AggregateID: ownerID, AggregateVersion: version, RecordKey: ownerID, WarehouseID: warehouseID,
		RecordedAt: time.Now().UTC(), Proof: proof,
	}
}
