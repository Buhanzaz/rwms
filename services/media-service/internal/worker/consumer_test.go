package worker

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

func TestParseProcessingRequestAcceptsExactEnvelope(t *testing.T) {
	fixture := newProcessingRecord(t)

	message, err := parseProcessingRequest(fixture.record)
	if err != nil {
		t.Fatalf("parseProcessingRequest() error = %v", err)
	}
	sum := sha256.Sum256(fixture.record.Value)
	if message.EventID != fixture.eventID || message.BodySHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("message identity/hash = %s/%s", message.EventID, message.BodySHA256)
	}
	if message.Topic != persistence.ProcessingTopic || message.EventType != "media.processing.request.v1" ||
		message.AggregateType != "PROCESSING_JOB" || message.AggregateID != fixture.jobID ||
		message.RecordKey != fixture.jobID || message.AggregateVersion != 1 {
		t.Fatalf("message routing identity is wrong: %#v", message)
	}
	if message.ExpectedMediaID != fixture.mediaID || message.ExpectedWarehouseID != fixture.warehouseID ||
		message.ExpectedKind != media.KindImage || message.ExpectedProcessingKind != media.ProcessingInitial ||
		message.ExpectedGeneration != 1 || message.ExpectedRotation != media.Rotation0 ||
		message.ExpectedSourceVersionID != "minio-version-1" {
		t.Fatalf("message expected job values are wrong: %#v", message)
	}
}

func TestParseProcessingRequestRejectsContractViolations(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*processingRecordFixture)
	}{
		{name: "wrong topic", mutate: func(fixture *processingRecordFixture) {
			fixture.record.Topic = "rwms.media.other.v1"
		}},
		{name: "wrong aggregate key", mutate: func(fixture *processingRecordFixture) {
			fixture.record.Key = []byte(uuid.NewString())
		}},
		{name: "wrong event type", mutate: func(fixture *processingRecordFixture) {
			fixture.envelope["eventType"] = "media.media.uploaded.v1"
		}},
		{name: "wrong aggregate type", mutate: func(fixture *processingRecordFixture) {
			fixture.envelope["aggregateType"] = "MEDIA"
		}},
		{name: "wrong aggregate id", mutate: func(fixture *processingRecordFixture) {
			fixture.envelope["aggregateId"] = uuid.NewString()
		}},
		{name: "wrong aggregate version", mutate: func(fixture *processingRecordFixture) {
			fixture.envelope["aggregateVersion"] = 2
		}},
		{name: "unknown envelope field", mutate: func(fixture *processingRecordFixture) {
			fixture.envelope["secret"] = "must-not-pass"
		}},
		{name: "unknown payload field", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["secret"] = "must-not-pass"
		}},
		{name: "non null actor", mutate: func(fixture *processingRecordFixture) {
			fixture.envelope["actorRef"] = map[string]any{"subjectId": uuid.NewString()}
		}},
		{name: "invalid recorded time", mutate: func(fixture *processingRecordFixture) {
			fixture.envelope["recordedAt"] = "yesterday"
		}},
		{name: "invalid occurred time", mutate: func(fixture *processingRecordFixture) {
			fixture.envelope["occurredAt"] = map[string]any{"raw": true}
		}},
		{name: "missing occurred time", mutate: func(fixture *processingRecordFixture) {
			delete(fixture.envelope, "occurredAt")
		}},
		{name: "invalid causation id", mutate: func(fixture *processingRecordFixture) {
			fixture.correlation()["causationId"] = "not-a-uuid"
		}},
		{name: "missing causation id", mutate: func(fixture *processingRecordFixture) {
			delete(fixture.correlation(), "causationId")
		}},
		{name: "invalid kind", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["kind"] = "DOCUMENT"
		}},
		{name: "invalid processing kind", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["processingKind"] = "RETRY"
		}},
		{name: "zero generation", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["generation"] = 0
		}},
		{name: "invalid rotation", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["rotationDegrees"] = 45
		}},
		{name: "blank source version", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["sourceVersionId"] = "  "
		}},
		{name: "oversized source version", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["sourceVersionId"] = strings.Repeat("v", 256)
		}},
		{name: "trailing JSON", mutate: func(fixture *processingRecordFixture) {
			fixture.trailing = []byte(` {"extra":true}`)
		}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fixture := newProcessingRecord(t)
			test.mutate(fixture)
			fixture.remarshal(t)
			if _, err := parseProcessingRequest(fixture.record); err == nil {
				t.Fatal("parseProcessingRequest() error = nil, want strict contract rejection")
			}
		})
	}
}

func TestInvalidMessageIsDeterministicAndSanitized(t *testing.T) {
	raw := []byte(`{"eventId":"operator@example.test","payload":{"secret":"do-not-store"}}`)
	record := &kgo.Record{Topic: "attacker.topic", Key: []byte("operator@example.test"), Value: raw}

	first := invalidMessage(record)
	second := invalidMessage(record)
	if first != second {
		t.Fatalf("invalidMessage() is not deterministic: %#v != %#v", first, second)
	}
	sum := sha256.Sum256(raw)
	if first.BodySHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("BodySHA256 = %s, want exact input digest", first.BodySHA256)
	}
	if first.Topic != persistence.ProcessingTopic || first.EventType != "media.processing.request.v1" ||
		first.AggregateType != "PROCESSING_JOB" || first.AggregateVersion != 1 ||
		first.EventID == uuid.Nil || first.AggregateID != first.EventID || first.RecordKey != first.EventID {
		t.Fatalf("sanitized identity/routing is wrong: %#v", first)
	}
	encoded, err := json.Marshal(first)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}
	if strings.Contains(string(encoded), "operator@example.test") || strings.Contains(string(encoded), "do-not-store") ||
		strings.Contains(string(encoded), "attacker.topic") {
		t.Fatalf("sanitized message leaked source content: %s", encoded)
	}
	changed := invalidMessage(&kgo.Record{Value: append(append([]byte{}, raw...), '!')})
	if changed.EventID == first.EventID || changed.BodySHA256 == first.BodySHA256 {
		t.Fatal("different invalid bodies collapsed to one sanitized identity")
	}
}

type processingRecordFixture struct {
	record      *kgo.Record
	envelope    map[string]any
	eventID     uuid.UUID
	jobID       uuid.UUID
	mediaID     uuid.UUID
	warehouseID uuid.UUID
	trailing    []byte
}

func newProcessingRecord(t *testing.T) *processingRecordFixture {
	t.Helper()
	fixture := &processingRecordFixture{
		eventID:     uuid.New(),
		jobID:       uuid.New(),
		mediaID:     uuid.New(),
		warehouseID: uuid.New(),
	}
	fixture.envelope = map[string]any{
		"envelopeVersion":  2,
		"eventId":          fixture.eventID.String(),
		"eventType":        "media.processing.request.v1",
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       time.Now().UTC().Format(time.RFC3339Nano),
		"producer":         "media-service",
		"aggregateType":    "PROCESSING_JOB",
		"aggregateId":      fixture.jobID.String(),
		"aggregateVersion": 1,
		"correlation": map[string]any{
			"correlationId": uuid.NewString(),
			"causationId":   nil,
		},
		"actorRef": nil,
		"payload": map[string]any{
			"processingJobId": fixture.jobID.String(),
			"mediaId":         fixture.mediaID.String(),
			"warehouseId":     fixture.warehouseID.String(),
			"kind":            "IMAGE",
			"processingKind":  "INITIAL",
			"generation":      1,
			"rotationDegrees": 0,
			"sourceVersionId": "minio-version-1",
		},
	}
	fixture.record = &kgo.Record{Topic: persistence.ProcessingTopic, Key: []byte(fixture.jobID.String())}
	fixture.remarshal(t)
	return fixture
}

func (fixture *processingRecordFixture) payload() map[string]any {
	return fixture.envelope["payload"].(map[string]any)
}

func (fixture *processingRecordFixture) correlation() map[string]any {
	return fixture.envelope["correlation"].(map[string]any)
}

func (fixture *processingRecordFixture) remarshal(t *testing.T) {
	t.Helper()
	value, err := json.Marshal(fixture.envelope)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}
	fixture.record.Value = append(value, fixture.trailing...)
}
