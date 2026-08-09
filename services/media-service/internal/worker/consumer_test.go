package worker

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/realtime"
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
		{name: "removed rotation processing kind", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["processingKind"] = "ROTATION"
		}},
		{name: "zero generation", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["generation"] = 0
		}},
		{name: "nonzero rotation", mutate: func(fixture *processingRecordFixture) {
			fixture.payload()["rotationDegrees"] = 90
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

func TestProcessingInvalidationCarriesCabinOwnerContext(t *testing.T) {
	warehouseID, cabinID, mediaID := uuid.New(), uuid.New(), uuid.New()
	publisher := &recordingPublisher{}
	consumer := &Consumer{publisher: publisher}

	consumer.publish(persistence.WorkerJob{
		MediaID: mediaID, WarehouseID: warehouseID,
		OwnerType: persistence.OwnerTypeCabin, OwnerID: cabinID.String(), Generation: 4,
	}, "MEDIA_CHANGED")

	if len(publisher.events) != 1 {
		t.Fatalf("published events = %d, want one", len(publisher.events))
	}
	event := publisher.events[0]
	if event.EventID == uuid.Nil || event.WarehouseID != warehouseID || event.MediaID != mediaID ||
		event.Scope != "MEDIA_CHANGED" || event.OwnerType != persistence.OwnerTypeCabin ||
		event.OwnerID != cabinID.String() || event.Generation != 4 || event.OccurredAt.IsZero() {
		t.Fatalf("processing invalidation = %#v", event)
	}
}

func TestProcessingConsumerKeepsRuntimeAliveAndCommitsOnlyPersistedTerminalOutcome(t *testing.T) {
	first := newProcessingRecord(t).record
	first.Partition, first.Offset = 0, 9
	second := newProcessingRecord(t).record
	second.Partition, second.Offset = 0, 10
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &processingKafkaClientStub{
		fetches: processingFetches(first, second), commitFailures: map[int64]int{9: 2},
		cancel: cancel,
	}
	dependencyFailure := errors.New("processing dependency unavailable")
	firstFailure := make(chan struct{})
	releaseRetry := make(chan struct{})
	var firstFailureOnce, firstSleepOnce sync.Once
	var handledOffsets []int64
	terminalPersisted := false
	orderingViolation := false
	firstCalls := 0
	consumer := &Consumer{
		client: client,
		logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		handleRecord: func(_ context.Context, record *kgo.Record) error {
			handledOffsets = append(handledOffsets, record.Offset)
			switch record.Offset {
			case 9:
				firstCalls++
				if firstCalls <= 3 {
					firstFailureOnce.Do(func() { close(firstFailure) })
					return dependencyFailure
				}
				terminalPersisted = true
				return nil
			case 10:
				if !terminalPersisted {
					orderingViolation = true
				}
				return nil
			default:
				return errors.New("unexpected processing offset")
			}
		},
		sleep: func(ctx context.Context, _ time.Duration) error {
			var waitErr error
			firstSleepOnce.Do(func() {
				select {
				case <-ctx.Done():
					waitErr = ctx.Err()
				case <-releaseRetry:
				}
			})
			return waitErr
		},
	}
	done := make(chan error, 1)
	go func() { done <- consumer.Run(ctx) }()

	select {
	case <-firstFailure:
	case <-time.After(time.Second):
		t.Fatal("processing consumer did not observe dependency failure")
	}
	select {
	case err := <-done:
		t.Fatalf("processing consumer exited on dependency failure: %v", err)
	default:
	}
	close(releaseRetry)
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("processing consumer shutdown error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("processing consumer did not complete ordered terminal handling")
	}

	if orderingViolation || !terminalPersisted {
		t.Fatalf("terminal/order state = persisted:%v violation:%v", terminalPersisted, orderingViolation)
	}
	wantHandled := []int64{9, 9, 9, 9, 10}
	if !equalOffsets(handledOffsets, wantHandled) {
		t.Fatalf("handled offsets = %v, want %v", handledOffsets, wantHandled)
	}
	if !equalOffsets(client.committed, []int64{9, 10}) {
		t.Fatalf("committed offsets = %v, want [9 10]", client.committed)
	}
	if !equalOffsets(client.commitAttempts, []int64{9, 9, 9, 10}) {
		t.Fatalf("commit attempts = %v, want [9 9 9 10]", client.commitAttempts)
	}
	if client.allowRebalanceCalls == 0 {
		t.Fatal("consumer never released the blocked rebalance after ordered batch")
	}
}

type processingKafkaClientStub struct {
	mu                  sync.Mutex
	fetches             kgo.Fetches
	polled              bool
	commitFailures      map[int64]int
	commitAttempts      []int64
	committed           []int64
	allowRebalanceCalls int
	cancel              context.CancelFunc
}

type recordingPublisher struct {
	events []realtime.Event
}

func (publisher *recordingPublisher) Publish(event realtime.Event) {
	publisher.events = append(publisher.events, event)
}

func (client *processingKafkaClientStub) PollFetches(ctx context.Context) kgo.Fetches {
	client.mu.Lock()
	if !client.polled {
		client.polled = true
		fetches := client.fetches
		client.mu.Unlock()
		return fetches
	}
	client.mu.Unlock()
	<-ctx.Done()
	return nil
}

func (client *processingKafkaClientStub) CommitRecords(_ context.Context, records ...*kgo.Record) error {
	client.mu.Lock()
	defer client.mu.Unlock()
	if len(records) != 1 {
		return errors.New("processing commit must contain one ordered record")
	}
	offset := records[0].Offset
	client.commitAttempts = append(client.commitAttempts, offset)
	if client.commitFailures[offset] > 0 {
		client.commitFailures[offset]--
		return errors.New("broker commit unavailable")
	}
	client.committed = append(client.committed, offset)
	if offset == 10 {
		client.cancel()
	}
	return nil
}

func (client *processingKafkaClientStub) AllowRebalance() {
	client.mu.Lock()
	client.allowRebalanceCalls++
	client.mu.Unlock()
}

func (client *processingKafkaClientStub) Close() {}

func processingFetches(records ...*kgo.Record) kgo.Fetches {
	return kgo.Fetches{{Topics: []kgo.FetchTopic{{
		Topic:      persistence.ProcessingTopic,
		Partitions: []kgo.FetchPartition{{Partition: 0, Records: records}},
	}}}}
}

func equalOffsets(actual, wanted []int64) bool {
	if len(actual) != len(wanted) {
		return false
	}
	for index := range wanted {
		if actual[index] != wanted[index] {
			return false
		}
	}
	return true
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
