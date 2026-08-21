package worker

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

const taskBoardOwnerProofRecordLimit = 1 << 20

// TaskBoardEntryOwnerProofConsumer materializes task-board ownership and
// worker-access evidence for worker-scoped media routes.
type TaskBoardEntryOwnerProofConsumer struct {
	repository  taskBoardEntryOwnerProofPersistence
	client      kafkaConsumerClient
	logger      *slog.Logger
	sourceTopic string
	pollTimeout time.Duration
}

type taskBoardEntryOwnerProofPersistence interface {
	ApplyTaskBoardEntryOwnerProofMessage(context.Context, persistence.TaskBoardEntryOwnerProofMessage) (persistence.TaskBoardEntryOwnerProofApplyResult, error)
	RecordTaskBoardEntryOwnerProofDLT(context.Context, string) error
}

// NewTaskBoardEntryOwnerProofKafkaConsumer creates a manual-commit client only
// for the canonical task-board entry owner-proof topic and group.
func NewTaskBoardEntryOwnerProofKafkaConsumer(brokers []string, group, topic string) (*kgo.Client, error) {
	if len(brokers) == 0 || group != persistence.TaskBoardEntryOwnerProofConsumer ||
		topic != persistence.TaskBoardEntryOwnerProofTopic {
		return nil, fmt.Errorf("task-board entry owner proof Kafka configuration is not canonical")
	}
	return newTaskBoardEntryOwnerProofKafkaClient(brokers, group, topic)
}

// NewTaskBoardEntryOwnerProofKafkaConsumerForIsolatedTest binds unique physical
// Kafka resources while keeping the parsed contract canonical.
func NewTaskBoardEntryOwnerProofKafkaConsumerForIsolatedTest(brokers []string, group, physicalTopic string) (*kgo.Client, error) {
	if len(brokers) == 0 || strings.TrimSpace(group) == "" || strings.TrimSpace(physicalTopic) == "" ||
		group == persistence.TaskBoardEntryOwnerProofConsumer || physicalTopic == persistence.TaskBoardEntryOwnerProofTopic {
		return nil, fmt.Errorf("isolated task-board owner proof Kafka resources are required")
	}
	return newTaskBoardEntryOwnerProofKafkaClient(brokers, group, physicalTopic)
}

func newTaskBoardEntryOwnerProofKafkaClient(brokers []string, group, topic string) (*kgo.Client, error) {
	return kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topic),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
		kgo.FetchMaxBytes(2<<20),
		kgo.FetchMaxPartitionBytes(taskBoardOwnerProofRecordLimit),
	)
}

// NewTaskBoardEntryOwnerProofConsumer constructs the production task-board
// entry owner-proof consumer.
func NewTaskBoardEntryOwnerProofConsumer(repository *persistence.Repository, client *kgo.Client, logger *slog.Logger) *TaskBoardEntryOwnerProofConsumer {
	return newTaskBoardEntryOwnerProofConsumer(repository, client, logger, persistence.TaskBoardEntryOwnerProofTopic)
}

func newTaskBoardEntryOwnerProofConsumer(repository taskBoardEntryOwnerProofPersistence, client *kgo.Client, logger *slog.Logger, sourceTopic string) *TaskBoardEntryOwnerProofConsumer {
	if strings.TrimSpace(sourceTopic) == "" {
		sourceTopic = persistence.TaskBoardEntryOwnerProofTopic
	}
	return &TaskBoardEntryOwnerProofConsumer{
		repository: repository, client: client, logger: logger, sourceTopic: sourceTopic,
		pollTimeout: kafkaConsumerPollTimeout,
	}
}

// Run validates, persists, and acknowledges task-board owner facts until ctx
// is canceled or a dependency failure stops this runtime process.
func (consumer *TaskBoardEntryOwnerProofConsumer) Run(ctx context.Context) error {
	for {
		fetches, pollTimedOut := pollKafkaFetches(ctx, consumer.client, consumer.pollTimeout)
		if ctx.Err() != nil {
			consumer.client.AllowRebalance()
			return nil
		}
		if pollTimedOut {
			consumer.client.AllowRebalance()
			continue
		}
		if errs := fetches.Errors(); len(errs) > 0 {
			consumer.client.AllowRebalance()
			consumer.logger.Error("poll task-board entry owner proof topic", "errorType", "BROKER_UNAVAILABLE")
			continue
		}
		for iterator := fetches.RecordIter(); !iterator.Done(); {
			record := iterator.Next()
			if err := consumer.handle(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				if ctx.Err() != nil {
					return nil
				}
				consumer.logger.Error("process task-board entry owner proof", "eventOffset", record.Offset,
					"errorType", "DEPENDENCY_ERROR")
				return err
			}
			if err := consumer.client.CommitRecords(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				return err
			}
		}
		consumer.client.AllowRebalance()
	}
}

func (consumer *TaskBoardEntryOwnerProofConsumer) handle(ctx context.Context, record *kgo.Record) error {
	logicalRecord := record
	if record != nil && record.Topic == consumer.sourceTopic && consumer.sourceTopic != persistence.TaskBoardEntryOwnerProofTopic {
		copyRecord := *record
		copyRecord.Topic = persistence.TaskBoardEntryOwnerProofTopic
		logicalRecord = &copyRecord
	}
	message, err := parseTaskBoardEntryOwnerProofRecord(logicalRecord)
	if err != nil {
		return consumer.repository.RecordTaskBoardEntryOwnerProofDLT(ctx, taskBoardOwnerProofBodySHA256(record))
	}
	_, err = consumer.repository.ApplyTaskBoardEntryOwnerProofMessage(ctx, message)
	return err
}

// Close stops the underlying task-board owner-proof Kafka client.
func (consumer *TaskBoardEntryOwnerProofConsumer) Close() { consumer.client.Close() }

type taskBoardOwnerProofPayload struct {
	OwnerType             string            `json:"ownerType"`
	OwnerID               string            `json:"ownerId"`
	WarehouseID           string            `json:"warehouseId"`
	RouteIndex            int               `json:"routeIndex"`
	Active                bool              `json:"active"`
	AllowedWorkerIDs      []string          `json:"allowedWorkerIds"`
	ReaderWorkerIDs       []string          `json:"readerWorkerIds"`
	SourceMediaReferences []json.RawMessage `json:"sourceMediaReferences"`
}

type taskBoardSourceMediaReferencePayload struct {
	MediaID    string `json:"mediaId"`
	Generation int    `json:"generation"`
}

func parseTaskBoardEntryOwnerProofRecord(record *kgo.Record) (persistence.TaskBoardEntryOwnerProofMessage, error) {
	if record == nil || record.Topic != persistence.TaskBoardEntryOwnerProofTopic || len(record.Value) == 0 ||
		len(record.Value) > taskBoardOwnerProofRecordLimit {
		return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("invalid task-board entry owner proof record")
	}
	decoder := json.NewDecoder(bytes.NewReader(record.Value))
	decoder.DisallowUnknownFields()
	var envelope inventoryEnvelope
	if err := decoder.Decode(&envelope); err != nil {
		return persistence.TaskBoardEntryOwnerProofMessage{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("trailing task-board owner proof data")
	}
	eventID, eventErr := strictUUID(envelope.EventID)
	entryID, aggregateErr := strictUUID(envelope.AggregateID)
	recordKey, keyErr := strictUUID(string(record.Key))
	_, correlationErr := strictUUID(envelope.Correlation.CorrelationID)
	recordedAt, recordedErr := time.Parse(time.RFC3339Nano, envelope.RecordedAt)
	if eventErr != nil || aggregateErr != nil || keyErr != nil || correlationErr != nil || recordedErr != nil ||
		envelope.EnvelopeVersion != 2 || envelope.EventVersion != 1 || envelope.Producer != "task-board-service" ||
		envelope.EventType != "task-board.entry-owner-proof.changed.v1" ||
		envelope.AggregateType != persistence.TaskBoardEntryOwnerProofAggregate || envelope.AggregateVersion < 0 ||
		recordKey != entryID || !nullableInventoryDateTime(envelope.OccurredAt) ||
		!nullableInventoryUUID(envelope.Correlation.CausationID) || !validTaskBoardOwnerProofActor(envelope.ActorRef) {
		return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("invalid task-board owner proof envelope")
	}
	legacyReaderAudience := false
	if err := exactJSONFields(envelope.Payload, "ownerType", "ownerId", "warehouseId", "routeIndex", "active",
		"allowedWorkerIds", "sourceMediaReferences"); err == nil {
		legacyReaderAudience = true
	} else {
		if optionalErr := exactJSONFields(envelope.Payload, "ownerType", "ownerId", "warehouseId", "routeIndex", "active",
			"allowedWorkerIds", "readerWorkerIds", "sourceMediaReferences"); optionalErr != nil {
			return persistence.TaskBoardEntryOwnerProofMessage{}, optionalErr
		}
	}
	var payload taskBoardOwnerProofPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return persistence.TaskBoardEntryOwnerProofMessage{}, err
	}
	ownerID, ownerErr := strictUUID(payload.OwnerID)
	warehouseID, warehouseErr := strictUUID(payload.WarehouseID)
	if payload.OwnerType != persistence.OwnerTypeTaskBoardEntry || ownerErr != nil || warehouseErr != nil ||
		ownerID != entryID || payload.RouteIndex < 0 || len(payload.AllowedWorkerIDs) > 1000 ||
		len(payload.ReaderWorkerIDs) > 1000 || (!legacyReaderAudience && payload.ReaderWorkerIDs == nil) ||
		len(payload.SourceMediaReferences) > 1000 {
		return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("invalid task-board owner proof payload")
	}
	workers := make([]uuid.UUID, 0, len(payload.AllowedWorkerIDs))
	seenWorkers := make(map[uuid.UUID]struct{}, len(payload.AllowedWorkerIDs))
	for _, rawWorkerID := range payload.AllowedWorkerIDs {
		workerID, err := strictUUID(rawWorkerID)
		if err != nil {
			return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("invalid task-board allowed worker")
		}
		if _, duplicate := seenWorkers[workerID]; duplicate {
			return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("duplicate task-board allowed worker")
		}
		seenWorkers[workerID] = struct{}{}
		workers = append(workers, workerID)
	}
	readerWorkers := make([]uuid.UUID, 0, len(payload.ReaderWorkerIDs))
	seenReaders := make(map[uuid.UUID]struct{}, len(payload.ReaderWorkerIDs))
	for _, rawWorkerID := range payload.ReaderWorkerIDs {
		workerID, err := strictUUID(rawWorkerID)
		if err != nil {
			return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("invalid task-board reader worker")
		}
		if _, duplicate := seenReaders[workerID]; duplicate {
			return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("duplicate task-board reader worker")
		}
		seenReaders[workerID] = struct{}{}
		readerWorkers = append(readerWorkers, workerID)
	}
	if legacyReaderAudience {
		readerWorkers = append([]uuid.UUID(nil), workers...)
	}
	references := make([]persistence.TaskBoardSourceMediaReference, 0, len(payload.SourceMediaReferences))
	seenReferences := make(map[persistence.TaskBoardSourceMediaReference]struct{}, len(payload.SourceMediaReferences))
	for _, rawReference := range payload.SourceMediaReferences {
		if err := exactJSONFields(rawReference, "mediaId", "generation"); err != nil {
			return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("invalid task-board source media reference")
		}
		var decodedReference taskBoardSourceMediaReferencePayload
		if err := json.Unmarshal(rawReference, &decodedReference); err != nil {
			return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("invalid task-board source media reference")
		}
		mediaID, err := strictUUID(decodedReference.MediaID)
		reference := persistence.TaskBoardSourceMediaReference{MediaID: mediaID, Generation: decodedReference.Generation}
		if err != nil || reference.Generation <= 0 {
			return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("invalid task-board source media reference")
		}
		if _, duplicate := seenReferences[reference]; duplicate {
			return persistence.TaskBoardEntryOwnerProofMessage{}, errors.New("duplicate task-board source media reference")
		}
		seenReferences[reference] = struct{}{}
		references = append(references, reference)
	}
	sum := sha256.Sum256(record.Value)
	return persistence.TaskBoardEntryOwnerProofMessage{
		EventID: eventID, BodySHA256: hex.EncodeToString(sum[:]), WireBody: append([]byte(nil), record.Value...),
		Topic: record.Topic, EventType: envelope.EventType, AggregateType: envelope.AggregateType,
		AggregateID: entryID, AggregateVersion: envelope.AggregateVersion, RecordKey: recordKey,
		RecordedAt: recordedAt.UTC(), WarehouseID: warehouseID, RouteIndex: payload.RouteIndex,
		Active: payload.Active, AllowedWorkerIDs: workers, ReaderWorkerIDs: readerWorkers,
		SourceMediaRefs: references,
	}, nil
}

// The task-board producer may emit an administrator, service, or worker actor
// for a proof change. The projection treats it only as envelope integrity data.
func validTaskBoardOwnerProofActor(raw json.RawMessage) bool {
	if isNull(raw) {
		return true
	}
	if exactJSONFields(raw, "subjectId", "principalType", "profileRevision") != nil {
		return false
	}
	var actor struct {
		SubjectID       string          `json:"subjectId"`
		PrincipalType   string          `json:"principalType"`
		ProfileRevision json.RawMessage `json:"profileRevision"`
	}
	if json.Unmarshal(raw, &actor) != nil || !setContains(actor.PrincipalType, "USER", "SERVICE", "WORKER") {
		return false
	}
	if _, err := strictUUID(actor.SubjectID); err != nil {
		return false
	}
	if isNull(actor.ProfileRevision) {
		return true
	}
	var revision string
	if json.Unmarshal(actor.ProfileRevision, &revision) != nil {
		return false
	}
	if _, err := strictUUID(revision); err == nil {
		return true
	}
	return validLowerSHA256(revision)
}

func taskBoardOwnerProofBodySHA256(record *kgo.Record) string {
	var body []byte
	if record != nil {
		body = record.Value
	}
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}
