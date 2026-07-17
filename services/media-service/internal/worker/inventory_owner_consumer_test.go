package worker

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

func TestParseInventoryFindingRecordAcceptsMarkerProofAndIgnoresSession(t *testing.T) {
	marker := newInventoryRecord(t, "inventory.finding.added.v1", 0, false)
	message, ignored, err := parseInventoryFindingRecord(marker.record)
	if err != nil || ignored || message.Proof != nil || message.AggregateVersion != 0 {
		t.Fatalf("marker parse = %#v, ignored=%v, error=%v", message, ignored, err)
	}
	if message.WarehouseID != marker.warehouseID {
		t.Fatalf("marker warehouse = %s, want %s", message.WarehouseID, marker.warehouseID)
	}
	proof := newInventoryRecord(t, persistence.InventoryOwnerProofEvent, 1, true)
	message, ignored, err = parseInventoryFindingRecord(proof.record)
	if err != nil || ignored || message.Proof == nil {
		t.Fatalf("proof parse = %#v, ignored=%v, error=%v", message, ignored, err)
	}
	if message.Proof.OwnerID != proof.aggregateID || message.Proof.WarehouseID != proof.warehouseID ||
		message.Proof.OwnerRevision != 0 || !message.Proof.Active {
		t.Fatalf("proof payload = %#v", message.Proof)
	}
	sum := sha256.Sum256(proof.record.Value)
	if message.BodySHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("proof hash = %s", message.BodySHA256)
	}

	session := newInventoryRecord(t, persistence.InventoryOwnerProofEvent, 0, true)
	session.envelope["aggregateType"] = "SESSION"
	session.envelope["eventType"] = "inventory.session.started.v1"
	session.envelope["payload"] = map[string]any{"inventoryId": uuid.NewString()}
	session.remarshal(t)
	_, ignored, err = parseInventoryFindingRecord(session.record)
	if err != nil || !ignored {
		t.Fatalf("session ignored=%v, error=%v", ignored, err)
	}
}

func TestParseInventoryFindingRecordRejectsContractViolations(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*inventoryRecordFixture)
	}{
		{name: "wrong topic", mutate: func(value *inventoryRecordFixture) { value.record.Topic = "rwms.inventory.other.v1" }},
		{name: "wrong key", mutate: func(value *inventoryRecordFixture) { value.record.Key = []byte(uuid.NewString()) }},
		{name: "wrong aggregate family", mutate: func(value *inventoryRecordFixture) { value.envelope["aggregateType"] = "INVENTORY_FINDING" }},
		{name: "negative version", mutate: func(value *inventoryRecordFixture) { value.envelope["aggregateVersion"] = -1 }},
		{name: "wrong owner uuid", mutate: func(value *inventoryRecordFixture) { value.payload()["ownerId"] = uuid.NewString() }},
		{name: "invalid warehouse uuid", mutate: func(value *inventoryRecordFixture) { value.payload()["warehouseId"] = "warehouse" }},
		{name: "wrong owner type", mutate: func(value *inventoryRecordFixture) { value.payload()["ownerType"] = "FINDING" }},
		{name: "negative owner revision", mutate: func(value *inventoryRecordFixture) { value.payload()["ownerRevision"] = -1 }},
		{name: "unknown root", mutate: func(value *inventoryRecordFixture) { value.envelope["secret"] = "forbidden" }},
		{name: "unknown payload", mutate: func(value *inventoryRecordFixture) { value.payload()["owner"] = "forbidden" }},
		{name: "unknown actor", mutate: func(value *inventoryRecordFixture) {
			value.envelope["actorRef"] = map[string]any{
				"subjectId": uuid.NewString(), "principalType": "USER", "profileRevision": nil,
				"email": "forbidden@example.test",
			}
		}},
		{name: "invalid event", mutate: func(value *inventoryRecordFixture) { value.envelope["eventType"] = "inventory.finding.deleted.v1" }},
		{name: "trailing JSON", mutate: func(value *inventoryRecordFixture) { value.trailing = []byte(` {"extra":true}`) }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fixture := newInventoryRecord(t, persistence.InventoryOwnerProofEvent, 1, true)
			test.mutate(fixture)
			fixture.remarshal(t)
			if _, _, err := parseInventoryFindingRecord(fixture.record); err == nil {
				t.Fatal("parseInventoryFindingRecord() error = nil")
			}
		})
	}

	oversized := newInventoryRecord(t, persistence.InventoryOwnerProofEvent, 1, true)
	oversized.record.Value = []byte(strings.Repeat("x", inventoryRecordLimit+1))
	if _, _, err := parseInventoryFindingRecord(oversized.record); err == nil {
		t.Fatal("oversized record was accepted")
	}
}

func TestInvalidInventoryOwnerDLTIsDeterministicAndSanitized(t *testing.T) {
	raw := []byte(`{"eventId":"operator@example.test","payload":{"secret":"forbidden"}}`)
	record := &kgo.Record{Topic: "attacker.topic", Key: []byte("bad"), Value: raw}
	first := invalidInventoryOwnerDLT(record, "INVALID_INVENTORY_OWNER_FACT", 0)
	second := invalidInventoryOwnerDLT(record, "INVALID_INVENTORY_OWNER_FACT", 0)
	if first != second || first.RecordKey == uuid.Nil || first.AggregateID != nil {
		t.Fatalf("invalid DLT identity = %#v / %#v", first, second)
	}
	sum := sha256.Sum256(raw)
	expectedKey := uuid.NewSHA1(uuid.NameSpaceOID, []byte(hex.EncodeToString(sum[:])))
	if first.RecordKey != expectedKey || first.SourceEventID != expectedKey {
		t.Fatalf("invalid DLT key = %s/%s, want exact %s", first.RecordKey, first.SourceEventID, expectedKey)
	}
	encoded, err := json.Marshal(first)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "operator@example.test") || strings.Contains(string(encoded), "forbidden") {
		t.Fatalf("invalid DLT leaked source: %s", encoded)
	}
}

func TestInventoryOwnerConsumerDoesNotRetryDeterministicFailure(t *testing.T) {
	fixture := newInventoryRecord(t, persistence.InventoryOwnerProofEvent, 1, true)
	store := &inventoryOwnerPersistenceStub{applyErrors: []error{persistence.ErrConflict}}
	sleeps := 0
	consumer := NewInventoryOwnerConsumerWithOptions(store, nil, nil, InventoryOwnerConsumerOptions{
		RetryDelays: []time.Duration{time.Millisecond, 2 * time.Millisecond, 4 * time.Millisecond},
		Sleep: func(context.Context, time.Duration) error {
			sleeps++
			return nil
		},
	})

	if err := consumer.handle(context.Background(), fixture.record); err != nil {
		t.Fatal(err)
	}
	if store.applyCalls != 1 || store.scheduleCalls != 0 || store.quarantineCalls != 0 || sleeps != 0 {
		t.Fatalf("deterministic calls apply=%d schedule=%d quarantine=%d sleeps=%d",
			store.applyCalls, store.scheduleCalls, store.quarantineCalls, sleeps)
	}
	if len(store.dlts) != 1 || store.dlts[0].FailureCode != "INVALID_INVENTORY_OWNER_FACT" ||
		store.dlts[0].AttemptCount != 1 {
		t.Fatalf("deterministic DLT = %#v", store.dlts)
	}
}

func TestInventoryOwnerConsumerBoundsTransientRetriesThenAtomicallyQuarantines(t *testing.T) {
	fixture := newInventoryRecord(t, persistence.InventoryOwnerProofEvent, 1, false)
	dependencyError := errors.New("postgres unavailable")
	store := &inventoryOwnerPersistenceStub{applyErrors: []error{
		dependencyError, dependencyError, dependencyError, dependencyError,
	}}
	var slept []time.Duration
	delays := []time.Duration{time.Millisecond, 2 * time.Millisecond, 4 * time.Millisecond}
	consumer := NewInventoryOwnerConsumerWithOptions(store, nil, nil, InventoryOwnerConsumerOptions{
		RetryDelays: delays,
		Sleep: func(_ context.Context, delay time.Duration) error {
			slept = append(slept, delay)
			return nil
		},
	})

	if err := consumer.handle(context.Background(), fixture.record); err != nil {
		t.Fatal(err)
	}
	if store.applyCalls != 4 || store.scheduleCalls != 3 || store.quarantineCalls != 1 ||
		store.quarantineAttempt != 4 || len(store.dlts) != 0 {
		t.Fatalf("transient calls apply=%d schedule=%d quarantine=%d attempt=%d DLT=%d",
			store.applyCalls, store.scheduleCalls, store.quarantineCalls,
			store.quarantineAttempt, len(store.dlts))
	}
	if len(slept) != len(delays) {
		t.Fatalf("sleep count = %d, want %d", len(slept), len(delays))
	}
	for index := range delays {
		if slept[index] != delays[index] {
			t.Fatalf("sleep[%d] = %s, want %s", index, slept[index], delays[index])
		}
	}
}

func TestInventoryOwnerConsumerAtomicallyQuarantinesDurableRetryIdentityMismatch(t *testing.T) {
	fixture := newInventoryRecord(t, persistence.InventoryOwnerProofEvent, 1, true)
	store := &inventoryOwnerPersistenceStub{retryStateErr: persistence.ErrIdempotencyMismatch}
	consumer := NewInventoryOwnerConsumerWithOptions(store, nil, nil, InventoryOwnerConsumerOptions{
		Sleep: func(context.Context, time.Duration) error {
			t.Fatal("retry identity conflict must not sleep")
			return nil
		},
	})

	if err := consumer.handle(context.Background(), fixture.record); err != nil {
		t.Fatal(err)
	}
	if store.retryConflictCalls != 1 || store.applyCalls != 0 || store.scheduleCalls != 0 ||
		store.quarantineCalls != 0 || len(store.dlts) != 0 {
		t.Fatalf("retry conflict calls=%d apply=%d schedule=%d terminal=%d DLT=%d",
			store.retryConflictCalls, store.applyCalls, store.scheduleCalls,
			store.quarantineCalls, len(store.dlts))
	}
}

type inventoryOwnerPersistenceStub struct {
	applyErrors        []error
	applyCalls         int
	scheduleCalls      int
	quarantineCalls    int
	quarantineAttempt  int
	retryConflictCalls int
	retryStateErr      error
	dlts               []persistence.InventoryOwnerDLTMessage
}

func (store *inventoryOwnerPersistenceStub) ApplyInventoryFindingMessage(
	context.Context,
	persistence.InventoryFindingMessage,
) (persistence.InventoryFindingApplyResult, error) {
	err := store.applyErrors[store.applyCalls]
	store.applyCalls++
	return persistence.InventoryFindingApplyResult{}, err
}

func (store *inventoryOwnerPersistenceStub) RecordInventoryOwnerDLT(
	_ context.Context,
	message persistence.InventoryOwnerDLTMessage,
) error {
	store.dlts = append(store.dlts, message)
	return nil
}

func (store *inventoryOwnerPersistenceStub) QuarantineInventoryOwnerProcessingFailure(
	_ context.Context,
	_ persistence.InventoryFindingMessage,
	attemptCount int,
) error {
	store.quarantineCalls++
	store.quarantineAttempt = attemptCount
	return nil
}

func (store *inventoryOwnerPersistenceStub) ScheduleInventoryOwnerRetry(
	context.Context,
	persistence.InventoryFindingMessage,
	int,
	time.Duration,
) error {
	store.scheduleCalls++
	return nil
}

func (store *inventoryOwnerPersistenceStub) InventoryOwnerRetryState(
	context.Context,
	uuid.UUID,
	string,
) (persistence.InventoryOwnerRetryState, bool, error) {
	return persistence.InventoryOwnerRetryState{}, false, store.retryStateErr
}

func (store *inventoryOwnerPersistenceStub) QuarantineInventoryOwnerRetryIdentityConflict(
	context.Context,
	persistence.InventoryFindingMessage,
) error {
	store.retryConflictCalls++
	return nil
}

func (*inventoryOwnerPersistenceStub) ClearInventoryOwnerRetry(context.Context, uuid.UUID) error {
	return nil
}

type inventoryRecordFixture struct {
	record      *kgo.Record
	envelope    map[string]any
	aggregateID uuid.UUID
	warehouseID uuid.UUID
	trailing    []byte
}

func newInventoryRecord(t *testing.T, eventType string, version int64, active bool) *inventoryRecordFixture {
	t.Helper()
	fixture := &inventoryRecordFixture{aggregateID: uuid.New(), warehouseID: uuid.New()}
	payload := map[string]any{
		"ownerType": "INVENTORY_FINDING", "ownerId": fixture.aggregateID.String(),
		"warehouseId": fixture.warehouseID.String(), "ownerRevision": 0, "active": active,
	}
	if eventType != persistence.InventoryOwnerProofEvent {
		payload = map[string]any{
			"inventoryId": uuid.NewString(), "findingId": fixture.aggregateID.String(),
			"warehouseId": fixture.warehouseID.String(), "sessionRevision": 0,
			"findingRevision": 0, "origin": "EXPECTED", "inspection": "NOT_INSPECTED",
			"reconciliation": "MISSING", "assetId": uuid.NewString(), "sourceAttached": false,
			"mediaCount": 0, "planFingerprintSha256": nil,
		}
	}
	fixture.envelope = map[string]any{
		"envelopeVersion": 2, "eventId": uuid.NewString(), "eventType": eventType,
		"eventVersion": 1, "occurredAt": nil, "recordedAt": time.Now().UTC().Format(time.RFC3339Nano),
		"producer": "inventory-service", "aggregateType": "FINDING",
		"aggregateId": fixture.aggregateID.String(), "aggregateVersion": version,
		"correlation": map[string]any{"correlationId": uuid.NewString(), "causationId": nil},
		"actorRef":    nil, "payload": payload,
	}
	fixture.record = &kgo.Record{Topic: persistence.InventorySessionTopic, Key: []byte(fixture.aggregateID.String())}
	fixture.remarshal(t)
	return fixture
}

func (fixture *inventoryRecordFixture) payload() map[string]any {
	return fixture.envelope["payload"].(map[string]any)
}

func (fixture *inventoryRecordFixture) remarshal(t *testing.T) {
	t.Helper()
	raw, err := json.Marshal(fixture.envelope)
	if err != nil {
		t.Fatal(err)
	}
	fixture.record.Value = append(raw, fixture.trailing...)
}
