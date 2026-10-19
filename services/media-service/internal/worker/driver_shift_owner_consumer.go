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

const driverShiftOwnerProofRecordLimit = 1 << 20

// DriverShiftOwnerProofConsumer materializes the dedicated driver-shift owner
// and driver audiences used by shift-evidence media routes.
type DriverShiftOwnerProofConsumer struct {
	repository  driverShiftOwnerProofPersistence
	client      kafkaConsumerClient
	logger      *slog.Logger
	sourceTopic string
	pollTimeout time.Duration
}

// driverShiftOwnerProofPersistence is the durable projection boundary used by
// the driver-shift Kafka consumer.
type driverShiftOwnerProofPersistence interface {
	ApplyDriverShiftOwnerProofMessage(context.Context, persistence.DriverShiftOwnerProofMessage) (persistence.DriverShiftOwnerProofApplyResult, error)
	RecordDriverShiftOwnerProofDLT(context.Context, string) error
}

// NewDriverShiftOwnerProofKafkaConsumer creates a manual-commit client only
// for the canonical driver-shift proof topic and consumer group.
func NewDriverShiftOwnerProofKafkaConsumer(brokers []string, group, topic string) (*kgo.Client, error) {
	if len(brokers) == 0 || group != persistence.DriverShiftOwnerProofConsumer ||
		topic != persistence.DriverShiftOwnerProofTopic {
		return nil, fmt.Errorf("driver-shift owner proof Kafka configuration is not canonical")
	}
	return newDriverShiftOwnerProofKafkaClient(brokers, group, topic)
}

// NewDriverShiftOwnerProofKafkaConsumerForIsolatedTest binds isolated Kafka
// resources while keeping logical record validation canonical.
func NewDriverShiftOwnerProofKafkaConsumerForIsolatedTest(brokers []string, group, physicalTopic string) (*kgo.Client, error) {
	if len(brokers) == 0 || strings.TrimSpace(group) == "" || strings.TrimSpace(physicalTopic) == "" ||
		group == persistence.DriverShiftOwnerProofConsumer || physicalTopic == persistence.DriverShiftOwnerProofTopic {
		return nil, fmt.Errorf("isolated driver-shift owner proof Kafka resources are required")
	}
	return newDriverShiftOwnerProofKafkaClient(brokers, group, physicalTopic)
}

func newDriverShiftOwnerProofKafkaClient(brokers []string, group, topic string) (*kgo.Client, error) {
	return kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topic),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
		kgo.FetchMaxBytes(2<<20),
		kgo.FetchMaxPartitionBytes(driverShiftOwnerProofRecordLimit),
	)
}

// NewDriverShiftOwnerProofConsumer constructs the production driver-shift
// owner-proof consumer.
func NewDriverShiftOwnerProofConsumer(repository *persistence.Repository, client *kgo.Client, logger *slog.Logger) *DriverShiftOwnerProofConsumer {
	return newDriverShiftOwnerProofConsumer(repository, client, logger, persistence.DriverShiftOwnerProofTopic)
}

func newDriverShiftOwnerProofConsumer(repository driverShiftOwnerProofPersistence, client *kgo.Client, logger *slog.Logger, sourceTopic string) *DriverShiftOwnerProofConsumer {
	if strings.TrimSpace(sourceTopic) == "" {
		sourceTopic = persistence.DriverShiftOwnerProofTopic
	}
	return &DriverShiftOwnerProofConsumer{
		repository: repository, client: client, logger: logger, sourceTopic: sourceTopic,
		pollTimeout: kafkaConsumerPollTimeout,
	}
}

// Run validates, persists and acknowledges driver-shift proof records until
// cancellation or a durable dependency failure stops this supervised process.
func (consumer *DriverShiftOwnerProofConsumer) Run(ctx context.Context) error {
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
			consumer.logger.Error("poll driver-shift owner proof topic", "errorType", "BROKER_UNAVAILABLE")
			continue
		}
		for iterator := fetches.RecordIter(); !iterator.Done(); {
			record := iterator.Next()
			if err := consumer.handle(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				if ctx.Err() != nil {
					return nil
				}
				consumer.logger.Error("process driver-shift owner proof", "eventOffset", record.Offset,
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

func (consumer *DriverShiftOwnerProofConsumer) handle(ctx context.Context, record *kgo.Record) error {
	logicalRecord := record
	if record != nil && record.Topic == consumer.sourceTopic && consumer.sourceTopic != persistence.DriverShiftOwnerProofTopic {
		copyRecord := *record
		copyRecord.Topic = persistence.DriverShiftOwnerProofTopic
		logicalRecord = &copyRecord
	}
	message, err := parseDriverShiftOwnerProofRecord(logicalRecord)
	if err != nil {
		return consumer.repository.RecordDriverShiftOwnerProofDLT(ctx, driverShiftOwnerProofBodySHA256(record))
	}
	_, err = consumer.repository.ApplyDriverShiftOwnerProofMessage(ctx, message)
	return err
}

// Close stops the underlying driver-shift owner-proof Kafka client.
func (consumer *DriverShiftOwnerProofConsumer) Close() { consumer.client.Close() }

// driverShiftOwnerProofPayload is the exact frozen task-board payload; it has
// no route or source-media fields from the entry proof family.
type driverShiftOwnerProofPayload struct {
	OwnerType        string   `json:"ownerType"`
	OwnerID          string   `json:"ownerId"`
	WarehouseID      string   `json:"warehouseId"`
	Active           bool     `json:"active"`
	AllowedWorkerIDs []string `json:"allowedWorkerIds"`
	ReaderWorkerIDs  []string `json:"readerWorkerIds"`
}

func parseDriverShiftOwnerProofRecord(record *kgo.Record) (persistence.DriverShiftOwnerProofMessage, error) {
	if record == nil || record.Topic != persistence.DriverShiftOwnerProofTopic || len(record.Value) == 0 ||
		len(record.Value) > driverShiftOwnerProofRecordLimit {
		return persistence.DriverShiftOwnerProofMessage{}, errors.New("invalid driver-shift owner proof record")
	}
	decoder := json.NewDecoder(bytes.NewReader(record.Value))
	decoder.DisallowUnknownFields()
	var envelope inventoryEnvelope
	if err := decoder.Decode(&envelope); err != nil {
		return persistence.DriverShiftOwnerProofMessage{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return persistence.DriverShiftOwnerProofMessage{}, errors.New("trailing driver-shift owner proof data")
	}
	eventID, eventErr := strictUUID(envelope.EventID)
	shiftID, aggregateErr := strictUUID(envelope.AggregateID)
	recordKey, keyErr := strictUUID(string(record.Key))
	_, correlationErr := strictUUID(envelope.Correlation.CorrelationID)
	recordedAt, recordedErr := time.Parse(time.RFC3339Nano, envelope.RecordedAt)
	if eventErr != nil || aggregateErr != nil || keyErr != nil || correlationErr != nil || recordedErr != nil ||
		envelope.EnvelopeVersion != 2 || envelope.EventVersion != 1 || envelope.Producer != "task-board-service" ||
		envelope.EventType != "task-board.driver-shift-owner-proof.changed.v1" ||
		envelope.AggregateType != persistence.DriverShiftOwnerProofAggregate || envelope.AggregateVersion < 0 ||
		recordKey != shiftID || !nullableInventoryDateTime(envelope.OccurredAt) ||
		!nullableInventoryUUID(envelope.Correlation.CausationID) || !validTaskBoardOwnerProofActor(envelope.ActorRef) {
		return persistence.DriverShiftOwnerProofMessage{}, errors.New("invalid driver-shift owner proof envelope")
	}
	if err := exactJSONFields(envelope.Payload, "ownerType", "ownerId", "warehouseId", "active",
		"allowedWorkerIds", "readerWorkerIds"); err != nil {
		return persistence.DriverShiftOwnerProofMessage{}, err
	}
	var payload driverShiftOwnerProofPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return persistence.DriverShiftOwnerProofMessage{}, err
	}
	ownerID, ownerErr := strictUUID(payload.OwnerID)
	warehouseID, warehouseErr := strictUUID(payload.WarehouseID)
	if payload.OwnerType != persistence.OwnerTypeDriverShift || ownerErr != nil || warehouseErr != nil ||
		ownerID != shiftID || payload.AllowedWorkerIDs == nil || payload.ReaderWorkerIDs == nil ||
		len(payload.AllowedWorkerIDs) > 1 || len(payload.ReaderWorkerIDs) > 1 {
		return persistence.DriverShiftOwnerProofMessage{}, errors.New("invalid driver-shift owner proof payload")
	}
	workers, err := parseUniqueDriverShiftWorkers(payload.AllowedWorkerIDs)
	if err != nil {
		return persistence.DriverShiftOwnerProofMessage{}, err
	}
	readers, err := parseUniqueDriverShiftWorkers(payload.ReaderWorkerIDs)
	if err != nil {
		return persistence.DriverShiftOwnerProofMessage{}, err
	}
	sum := sha256.Sum256(record.Value)
	return persistence.DriverShiftOwnerProofMessage{
		EventID: eventID, BodySHA256: hex.EncodeToString(sum[:]), WireBody: append([]byte(nil), record.Value...),
		Topic: record.Topic, EventType: envelope.EventType, AggregateType: envelope.AggregateType,
		AggregateID: shiftID, AggregateVersion: envelope.AggregateVersion, RecordKey: recordKey,
		RecordedAt: recordedAt.UTC(), WarehouseID: warehouseID, Active: payload.Active,
		AllowedWorkerIDs: workers, ReaderWorkerIDs: readers,
	}, nil
}

func parseUniqueDriverShiftWorkers(values []string) ([]uuid.UUID, error) {
	workers := make([]uuid.UUID, 0, len(values))
	seen := make(map[uuid.UUID]struct{}, len(values))
	for _, rawWorkerID := range values {
		workerID, err := strictUUID(rawWorkerID)
		if err != nil {
			return nil, errors.New("invalid driver-shift worker audience")
		}
		if _, duplicate := seen[workerID]; duplicate {
			return nil, errors.New("duplicate driver-shift worker audience")
		}
		seen[workerID] = struct{}{}
		workers = append(workers, workerID)
	}
	return workers, nil
}

func driverShiftOwnerProofBodySHA256(record *kgo.Record) string {
	var body []byte
	if record != nil {
		body = record.Value
	}
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}
