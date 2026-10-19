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

func TestParseCabinOwnerRecordAcceptsLifecycleProofsAndOrderingMarkers(t *testing.T) {
	created := newCabinOwnerRecord(t, "asset.rental-item.created.v1", 0)
	message, err := parseCabinOwnerRecord(created.record)
	if err != nil || message.Proof == nil {
		t.Fatalf("created parse = %#v, %v", message, err)
	}
	if message.AggregateID != created.aggregateID ||
		message.PayloadOwnerID != created.aggregateID ||
		message.Proof.WarehouseID != created.warehouseID ||
		message.Proof.OwnerRevision != 0 || !message.Proof.Active ||
		message.Proof.Status != "FREE" {
		t.Fatalf("created owner proof = %#v", message)
	}
	sum := sha256.Sum256(created.record.Value)
	if message.BodySHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("created hash = %s", message.BodySHA256)
	}

	writtenOff := newCabinOwnerRecord(t, "asset.rental-item.status-changed.v1", 1)
	writtenOff.payload()["status"] = "WRITTEN_OFF"
	writtenOff.remarshal(t)
	message, err = parseCabinOwnerRecord(writtenOff.record)
	if err != nil || message.Proof == nil || message.Proof.Active {
		t.Fatalf("written-off parse = %#v, %v", message, err)
	}

	comment := newCabinOwnerRecord(t, "asset.rental-item.general-comment-changed.v1", 2)
	message, err = parseCabinOwnerRecord(comment.record)
	if err != nil || message.Proof != nil || message.PayloadOwnerID != comment.aggregateID {
		t.Fatalf("comment marker parse = %#v, %v", message, err)
	}
	note := newCabinOwnerRecord(t, "asset.rental-item.manual-note-added.v1", 3)
	message, err = parseCabinOwnerRecord(note.record)
	if err != nil || message.Proof != nil || message.PayloadOwnerID != note.aggregateID {
		t.Fatalf("note marker parse = %#v, %v", message, err)
	}
	visibility := newCabinOwnerRecord(t, "asset.rental-item.inventory-visibility-changed.v1", 4)
	message, err = parseCabinOwnerRecord(visibility.record)
	if err != nil || message.Proof != nil || message.PayloadOwnerID != visibility.aggregateID {
		t.Fatalf("inventory visibility marker parse = %#v, %v", message, err)
	}
}

func TestParseCabinOwnerRecordPreservesOwnerMismatchForDurableQuarantine(t *testing.T) {
	fixture := newCabinOwnerRecord(t, "asset.rental-item.created.v1", 0)
	mismatchedOwnerID := uuid.New()
	fixture.payload()["rentalItemId"] = mismatchedOwnerID.String()
	fixture.remarshal(t)

	message, err := parseCabinOwnerRecord(fixture.record)
	if err != nil {
		t.Fatalf("parseCabinOwnerRecord(owner mismatch) error = %v", err)
	}
	if message.AggregateID != fixture.aggregateID || message.PayloadOwnerID != mismatchedOwnerID {
		t.Fatalf("owner mismatch was not preserved = %#v", message)
	}
}

func TestParseCabinOwnerRecordRejectsContractViolations(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*cabinOwnerRecordFixture)
	}{
		{name: "wrong topic", mutate: func(value *cabinOwnerRecordFixture) {
			value.record.Topic = "rwms.asset.other.v1"
		}},
		{name: "wrong key", mutate: func(value *cabinOwnerRecordFixture) {
			value.record.Key = []byte(uuid.NewString())
		}},
		{name: "wrong aggregate", mutate: func(value *cabinOwnerRecordFixture) {
			value.envelope["aggregateType"] = "CABIN"
		}},
		{name: "negative version", mutate: func(value *cabinOwnerRecordFixture) {
			value.envelope["aggregateVersion"] = -1
		}},
		{name: "invalid warehouse", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["warehouseId"] = "warehouse"
		}},
		{name: "invalid number hash", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["numberSha256"] = "secret-number"
		}},
		{name: "unknown root", mutate: func(value *cabinOwnerRecordFixture) {
			value.envelope["secret"] = "forbidden"
		}},
		{name: "unknown payload", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["number"] = "БЫТ-001"
		}},
		{name: "unsupported event", mutate: func(value *cabinOwnerRecordFixture) {
			value.envelope["eventType"] = "asset.rental-item.deleted.v1"
		}},
		{name: "trailing JSON", mutate: func(value *cabinOwnerRecordFixture) {
			value.trailing = []byte(` {"extra":true}`)
		}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fixture := newCabinOwnerRecord(t, "asset.rental-item.created.v1", 0)
			test.mutate(fixture)
			fixture.remarshal(t)
			if _, err := parseCabinOwnerRecord(fixture.record); err == nil {
				t.Fatal("parseCabinOwnerRecord() error = nil")
			}
		})
	}

	oversized := newCabinOwnerRecord(t, "asset.rental-item.created.v1", 0)
	oversized.record.Value = []byte(strings.Repeat("x", cabinOwnerRecordLimit+1))
	if _, err := parseCabinOwnerRecord(oversized.record); err == nil {
		t.Fatal("oversized cabin owner record was accepted")
	}
}

func TestParseCabinOwnerRecordRejectsInvalidInventoryVisibilityMarker(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*cabinOwnerRecordFixture)
	}{
		{name: "mismatched rental item", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["rentalItemId"] = uuid.NewString()
		}},
		{name: "invalid warehouse", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["warehouseId"] = "warehouse"
		}},
		{name: "invalid inventory", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["inventoryId"] = "inventory"
		}},
		{name: "blank status", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["status"] = "  "
		}},
		{name: "invalid number hash", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["numberSha256"] = "secret-number"
		}},
		{name: "non boolean isolated", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["isolated"] = "false"
		}},
		{name: "null isolated", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["isolated"] = nil
		}},
		{name: "unexpected field", mutate: func(value *cabinOwnerRecordFixture) {
			value.payload()["private"] = true
		}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fixture := newCabinOwnerRecord(t, "asset.rental-item.inventory-visibility-changed.v1", 1)
			test.mutate(fixture)
			fixture.remarshal(t)
			if _, err := parseCabinOwnerRecord(fixture.record); err == nil {
				t.Fatal("parseCabinOwnerRecord() error = nil")
			}
		})
	}
}

func TestCabinOwnerConsumerStoresOnlyHashForMalformedRecord(t *testing.T) {
	raw := []byte(`{"eventId":"operator@example.test","payload":{"secret":"forbidden"}}`)
	record := &kgo.Record{Topic: "attacker.topic", Key: []byte("bad"), Value: raw}
	store := &cabinOwnerPersistenceStub{}
	consumer := newCabinOwnerConsumer(store, nil, nil, persistence.AssetRentalItemTopic)

	if err := consumer.handle(context.Background(), record); err != nil {
		t.Fatalf("handle(malformed) error = %v", err)
	}
	if store.applyCalls != 0 || len(store.deadLetters) != 1 {
		t.Fatalf("malformed calls apply=%d DLT=%d", store.applyCalls, len(store.deadLetters))
	}
	sum := sha256.Sum256(raw)
	if store.deadLetters[0].EventID != uuid.Nil ||
		store.deadLetters[0].BodySHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("sanitized malformed DLT = %#v", store.deadLetters[0])
	}
	encoded, err := json.Marshal(store.deadLetters[0])
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "operator@example.test") ||
		strings.Contains(string(encoded), "forbidden") {
		t.Fatalf("malformed DLT leaked source: %s", encoded)
	}
}

func TestCabinOwnerConsumerReturnsDependencyFailureWithoutAcknowledging(t *testing.T) {
	fixture := newCabinOwnerRecord(t, "asset.rental-item.created.v1", 0)
	wantErr := errors.New("postgres unavailable")
	store := &cabinOwnerPersistenceStub{applyErr: wantErr}
	consumer := newCabinOwnerConsumer(store, nil, nil, persistence.AssetRentalItemTopic)

	if err := consumer.handle(context.Background(), fixture.record); !errors.Is(err, wantErr) {
		t.Fatalf("handle(dependency failure) error = %v, want %v", err, wantErr)
	}
	if store.applyCalls != 1 || len(store.deadLetters) != 0 {
		t.Fatalf("dependency calls apply=%d DLT=%d", store.applyCalls, len(store.deadLetters))
	}
}

type cabinOwnerPersistenceStub struct {
	applyCalls  int
	applyErr    error
	deadLetters []persistence.CabinOwnerDeadLetter
}

func (store *cabinOwnerPersistenceStub) ApplyCabinOwnerMessage(
	context.Context,
	persistence.CabinOwnerMessage,
) (persistence.CabinOwnerApplyResult, error) {
	store.applyCalls++
	return persistence.CabinOwnerApplyResult{}, store.applyErr
}

func (store *cabinOwnerPersistenceStub) RecordCabinOwnerDLT(
	_ context.Context,
	message persistence.CabinOwnerDeadLetter,
) error {
	store.deadLetters = append(store.deadLetters, message)
	return nil
}

type cabinOwnerRecordFixture struct {
	record      *kgo.Record
	envelope    map[string]any
	aggregateID uuid.UUID
	warehouseID uuid.UUID
	trailing    []byte
}

func newCabinOwnerRecord(
	t *testing.T,
	eventType string,
	version int64,
) *cabinOwnerRecordFixture {
	t.Helper()
	fixture := &cabinOwnerRecordFixture{aggregateID: uuid.New(), warehouseID: uuid.New()}
	payload := map[string]any{
		"rentalItemId": fixture.aggregateID.String(),
		"warehouseId":  fixture.warehouseID.String(),
		"status":       "FREE",
		"numberSha256": strings.Repeat("a", 64),
	}
	switch eventType {
	case "asset.rental-item.inventory-visibility-changed.v1":
		payload = map[string]any{
			"rentalItemId": fixture.aggregateID.String(),
			"warehouseId":  fixture.warehouseID.String(),
			"status":       "FREE",
			"numberSha256": strings.Repeat("a", 64),
			"inventoryId":  uuid.NewString(),
			"isolated":     true,
		}
	case "asset.rental-item.general-comment-changed.v1":
		payload = map[string]any{
			"rentalItemId": fixture.aggregateID.String(), "commentRevision": 1,
		}
	case "asset.rental-item.manual-note-added.v1":
		payload = map[string]any{
			"rentalItemId": fixture.aggregateID.String(), "noteId": uuid.NewString(),
		}
	}
	fixture.envelope = map[string]any{
		"envelopeVersion":  2,
		"eventId":          uuid.NewString(),
		"eventType":        eventType,
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       time.Now().UTC().Format(time.RFC3339Nano),
		"producer":         "asset-service",
		"aggregateType":    persistence.CabinOwnerAggregate,
		"aggregateId":      fixture.aggregateID.String(),
		"aggregateVersion": version,
		"correlation": map[string]any{
			"correlationId": uuid.NewString(), "causationId": nil,
		},
		"actorRef": nil,
		"payload":  payload,
	}
	fixture.record = &kgo.Record{
		Topic: persistence.AssetRentalItemTopic,
		Key:   []byte(fixture.aggregateID.String()),
	}
	fixture.remarshal(t)
	return fixture
}

func (fixture *cabinOwnerRecordFixture) payload() map[string]any {
	return fixture.envelope["payload"].(map[string]any)
}

func (fixture *cabinOwnerRecordFixture) remarshal(t *testing.T) {
	t.Helper()
	raw, err := json.Marshal(fixture.envelope)
	if err != nil {
		t.Fatal(err)
	}
	fixture.record.Value = append(raw, fixture.trailing...)
}
