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

func TestParseTaskBoardEntryOwnerProofRecordAcceptsExactWorkerAndSourceScope(t *testing.T) {
	fixture := newTaskBoardOwnerProofRecord(t, 0)
	message, err := parseTaskBoardEntryOwnerProofRecord(fixture.record)
	if err != nil {
		t.Fatalf("parseTaskBoardEntryOwnerProofRecord() error = %v", err)
	}
	if message.AggregateID != fixture.entryID || message.RecordKey != fixture.entryID ||
		message.WarehouseID != fixture.warehouseID || !message.Active || message.RouteIndex != 2 {
		t.Fatalf("parsed owner proof = %#v", message)
	}
	if len(message.AllowedWorkerIDs) != 1 || message.AllowedWorkerIDs[0] != fixture.workerID ||
		len(message.SourceMediaRefs) != 1 || message.SourceMediaRefs[0].MediaID != fixture.sourceMediaID ||
		message.SourceMediaRefs[0].Generation != 3 {
		t.Fatalf("parsed worker/source scope = %#v", message)
	}
	sum := sha256.Sum256(fixture.record.Value)
	if message.BodySHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("BodySHA256 = %s", message.BodySHA256)
	}
}

func TestParseTaskBoardEntryOwnerProofRecordRejectsNestedSourceOverreach(t *testing.T) {
	fixture := newTaskBoardOwnerProofRecord(t, 0)
	payload := fixture.payload()
	payload["sourceMediaReferences"] = []any{map[string]any{
		"mediaId": fixture.sourceMediaID.String(), "generation": 3, "ownerId": uuid.NewString(),
	}}
	fixture.remarshal(t)
	if _, err := parseTaskBoardEntryOwnerProofRecord(fixture.record); err == nil {
		t.Fatal("parseTaskBoardEntryOwnerProofRecord() accepted a source reference with an unknown field")
	}
}

func TestTaskBoardEntryOwnerProofConsumerDoesNotApplyMalformedRecord(t *testing.T) {
	store := &taskBoardOwnerProofPersistenceStub{}
	consumer := newTaskBoardEntryOwnerProofConsumer(store, nil, nil, persistence.TaskBoardEntryOwnerProofTopic)
	record := &kgo.Record{Topic: "attacker.topic", Key: []byte("bad"), Value: []byte(`{"secret":"forbidden"}`)}
	if err := consumer.handle(context.Background(), record); err != nil {
		t.Fatalf("handle(malformed) error = %v", err)
	}
	if store.applyCalls != 0 || len(store.deadLetterHashes) != 1 {
		t.Fatalf("malformed calls apply=%d DLT=%d", store.applyCalls, len(store.deadLetterHashes))
	}
	sum := sha256.Sum256(record.Value)
	if store.deadLetterHashes[0] != hex.EncodeToString(sum[:]) {
		t.Fatalf("malformed DLT hash = %q", store.deadLetterHashes[0])
	}
}

func TestTaskBoardEntryOwnerProofConsumerReturnsProjectionFailure(t *testing.T) {
	fixture := newTaskBoardOwnerProofRecord(t, 0)
	wantErr := errors.New("postgres unavailable")
	store := &taskBoardOwnerProofPersistenceStub{applyErr: wantErr}
	consumer := newTaskBoardEntryOwnerProofConsumer(store, nil, nil, persistence.TaskBoardEntryOwnerProofTopic)
	if err := consumer.handle(context.Background(), fixture.record); !errors.Is(err, wantErr) {
		t.Fatalf("handle() error = %v, want %v", err, wantErr)
	}
	if store.applyCalls != 1 || len(store.deadLetterHashes) != 0 {
		t.Fatalf("dependency calls apply=%d DLT=%d", store.applyCalls, len(store.deadLetterHashes))
	}
}

type taskBoardOwnerProofPersistenceStub struct {
	applyCalls       int
	applyErr         error
	deadLetterHashes []string
}

func (stub *taskBoardOwnerProofPersistenceStub) ApplyTaskBoardEntryOwnerProofMessage(
	context.Context,
	persistence.TaskBoardEntryOwnerProofMessage,
) (persistence.TaskBoardEntryOwnerProofApplyResult, error) {
	stub.applyCalls++
	return persistence.TaskBoardEntryOwnerProofApplyResult{}, stub.applyErr
}

func (stub *taskBoardOwnerProofPersistenceStub) RecordTaskBoardEntryOwnerProofDLT(_ context.Context, bodySHA256 string) error {
	stub.deadLetterHashes = append(stub.deadLetterHashes, bodySHA256)
	return nil
}

type taskBoardOwnerProofRecordFixture struct {
	record        *kgo.Record
	envelope      map[string]any
	entryID       uuid.UUID
	warehouseID   uuid.UUID
	workerID      uuid.UUID
	sourceMediaID uuid.UUID
}

func newTaskBoardOwnerProofRecord(t *testing.T, version int64) *taskBoardOwnerProofRecordFixture {
	t.Helper()
	fixture := &taskBoardOwnerProofRecordFixture{
		entryID: uuid.New(), warehouseID: uuid.New(), workerID: uuid.New(), sourceMediaID: uuid.New(),
	}
	fixture.envelope = map[string]any{
		"envelopeVersion":  2,
		"eventId":          uuid.NewString(),
		"eventType":        "task-board.entry-owner-proof.changed.v1",
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       time.Now().UTC().Format(time.RFC3339Nano),
		"producer":         "task-board-service",
		"aggregateType":    persistence.TaskBoardEntryOwnerProofAggregate,
		"aggregateId":      fixture.entryID.String(),
		"aggregateVersion": version,
		"correlation": map[string]any{
			"correlationId": uuid.NewString(), "causationId": nil,
		},
		"actorRef": nil,
		"payload": map[string]any{
			"ownerType": persistence.OwnerTypeTaskBoardEntry,
			"ownerId":   fixture.entryID.String(), "warehouseId": fixture.warehouseID.String(),
			"routeIndex": 2, "active": true,
			"allowedWorkerIds": []string{fixture.workerID.String()},
			"sourceMediaReferences": []any{map[string]any{
				"mediaId": fixture.sourceMediaID.String(), "generation": 3,
			}},
		},
	}
	fixture.record = &kgo.Record{Topic: persistence.TaskBoardEntryOwnerProofTopic, Key: []byte(fixture.entryID.String())}
	fixture.remarshal(t)
	return fixture
}

func (fixture *taskBoardOwnerProofRecordFixture) payload() map[string]any {
	return fixture.envelope["payload"].(map[string]any)
}

func (fixture *taskBoardOwnerProofRecordFixture) remarshal(t *testing.T) {
	t.Helper()
	body, err := json.Marshal(fixture.envelope)
	if err != nil {
		t.Fatal(err)
	}
	fixture.record.Value = body
}
