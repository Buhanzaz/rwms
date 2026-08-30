package worker

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

func TestParseDriverShiftOwnerProofRecordAcceptsExactAudiences(t *testing.T) {
	fixture := newDriverShiftOwnerProofRecord(t, 0)
	message, err := parseDriverShiftOwnerProofRecord(fixture.record)
	if err != nil {
		t.Fatalf("parseDriverShiftOwnerProofRecord() error = %v", err)
	}
	if message.AggregateID != fixture.shiftID || message.RecordKey != fixture.shiftID ||
		message.WarehouseID != fixture.warehouseID || !message.Active ||
		len(message.AllowedWorkerIDs) != 1 || message.AllowedWorkerIDs[0] != fixture.workerID ||
		len(message.ReaderWorkerIDs) != 1 || message.ReaderWorkerIDs[0] != fixture.workerID {
		t.Fatalf("parsed driver-shift proof = %#v", message)
	}
	sum := sha256.Sum256(fixture.record.Value)
	if message.BodySHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("BodySHA256 = %s", message.BodySHA256)
	}
}

func TestParseDriverShiftOwnerProofRecordAcceptsInactiveEmptyAudiences(t *testing.T) {
	fixture := newDriverShiftOwnerProofRecord(t, 1)
	fixture.payload()["active"] = false
	fixture.payload()["allowedWorkerIds"] = []string{}
	fixture.payload()["readerWorkerIds"] = []string{}
	fixture.remarshal(t)
	message, err := parseDriverShiftOwnerProofRecord(fixture.record)
	if err != nil {
		t.Fatalf("parse inactive driver-shift proof: %v", err)
	}
	if message.Active || len(message.AllowedWorkerIDs) != 0 || len(message.ReaderWorkerIDs) != 0 {
		t.Fatalf("inactive driver-shift proof = %#v", message)
	}
}

func TestParseDriverShiftOwnerProofRecordRejectsOverreachAndDuplicateAudience(t *testing.T) {
	for name, mutate := range map[string]func(*driverShiftOwnerProofRecordFixture){
		"unknown field": func(fixture *driverShiftOwnerProofRecordFixture) {
			fixture.payload()["routeIndex"] = 0
		},
		"duplicate audience": func(fixture *driverShiftOwnerProofRecordFixture) {
			fixture.payload()["allowedWorkerIds"] = []string{fixture.workerID.String(), fixture.workerID.String()}
		},
		"oversized audience": func(fixture *driverShiftOwnerProofRecordFixture) {
			fixture.payload()["readerWorkerIds"] = []string{fixture.workerID.String(), uuid.NewString()}
		},
		"wrong owner": func(fixture *driverShiftOwnerProofRecordFixture) {
			fixture.payload()["ownerType"] = persistence.OwnerTypeTaskBoardEntry
		},
	} {
		t.Run(name, func(t *testing.T) {
			fixture := newDriverShiftOwnerProofRecord(t, 0)
			mutate(fixture)
			fixture.remarshal(t)
			if _, err := parseDriverShiftOwnerProofRecord(fixture.record); err == nil {
				t.Fatal("parseDriverShiftOwnerProofRecord() accepted invalid payload")
			}
		})
	}
}

func TestDriverShiftOwnerProofConsumerDLTsMalformedAndReturnsProjectionFailure(t *testing.T) {
	store := &driverShiftOwnerProofPersistenceStub{}
	consumer := newDriverShiftOwnerProofConsumer(store, nil, nil, persistence.DriverShiftOwnerProofTopic)
	malformed := &kgo.Record{Topic: "attacker.topic", Key: []byte("bad"), Value: []byte(`{"secret":"forbidden"}`)}
	if err := consumer.handle(context.Background(), malformed); err != nil {
		t.Fatalf("handle(malformed) error = %v", err)
	}
	if store.applyCalls != 0 || len(store.deadLetterHashes) != 1 {
		t.Fatalf("malformed calls apply=%d DLT=%d", store.applyCalls, len(store.deadLetterHashes))
	}

	wantErr := errors.New("postgres unavailable")
	store.applyErr = wantErr
	fixture := newDriverShiftOwnerProofRecord(t, 0)
	if err := consumer.handle(context.Background(), fixture.record); !errors.Is(err, wantErr) {
		t.Fatalf("handle(valid) error = %v, want %v", err, wantErr)
	}
	if store.applyCalls != 1 || len(store.deadLetterHashes) != 1 {
		t.Fatalf("valid dependency calls apply=%d DLT=%d", store.applyCalls, len(store.deadLetterHashes))
	}
}

// driverShiftOwnerProofPersistenceStub records consumer persistence calls.
type driverShiftOwnerProofPersistenceStub struct {
	applyCalls       int
	applyErr         error
	deadLetterHashes []string
}

func (stub *driverShiftOwnerProofPersistenceStub) ApplyDriverShiftOwnerProofMessage(
	context.Context,
	persistence.DriverShiftOwnerProofMessage,
) (persistence.DriverShiftOwnerProofApplyResult, error) {
	stub.applyCalls++
	return persistence.DriverShiftOwnerProofApplyResult{}, stub.applyErr
}

func (stub *driverShiftOwnerProofPersistenceStub) RecordDriverShiftOwnerProofDLT(_ context.Context, bodySHA256 string) error {
	stub.deadLetterHashes = append(stub.deadLetterHashes, bodySHA256)
	return nil
}

// driverShiftOwnerProofRecordFixture owns one mutable exact-envelope fixture.
type driverShiftOwnerProofRecordFixture struct {
	record      *kgo.Record
	envelope    map[string]any
	shiftID     uuid.UUID
	warehouseID uuid.UUID
	workerID    uuid.UUID
}

func newDriverShiftOwnerProofRecord(t *testing.T, version int64) *driverShiftOwnerProofRecordFixture {
	t.Helper()
	fixture := &driverShiftOwnerProofRecordFixture{
		shiftID: uuid.New(), warehouseID: uuid.New(), workerID: uuid.New(),
	}
	fixture.envelope = map[string]any{
		"envelopeVersion":  2,
		"eventId":          uuid.NewString(),
		"eventType":        "task-board.driver-shift-owner-proof.changed.v1",
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       time.Now().UTC().Format(time.RFC3339Nano),
		"producer":         "task-board-service",
		"aggregateType":    persistence.DriverShiftOwnerProofAggregate,
		"aggregateId":      fixture.shiftID.String(),
		"aggregateVersion": version,
		"correlation": map[string]any{
			"correlationId": uuid.NewString(), "causationId": nil,
		},
		"actorRef": nil,
		"payload": map[string]any{
			"ownerType": persistence.OwnerTypeDriverShift,
			"ownerId":   fixture.shiftID.String(), "warehouseId": fixture.warehouseID.String(),
			"active": true, "allowedWorkerIds": []string{fixture.workerID.String()},
			"readerWorkerIds": []string{fixture.workerID.String()},
		},
	}
	fixture.record = &kgo.Record{Topic: persistence.DriverShiftOwnerProofTopic, Key: []byte(fixture.shiftID.String())}
	fixture.remarshal(t)
	return fixture
}

func (fixture *driverShiftOwnerProofRecordFixture) payload() map[string]any {
	return fixture.envelope["payload"].(map[string]any)
}

func (fixture *driverShiftOwnerProofRecordFixture) remarshal(t *testing.T) {
	t.Helper()
	body, err := json.Marshal(fixture.envelope)
	if err != nil {
		t.Fatal(err)
	}
	fixture.record.Value = body
}
