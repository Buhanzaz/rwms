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
	"github.com/twmb/franz-go/pkg/kgo"
)

const cabinOwnerRecordLimit = 1 << 20

type CabinOwnerConsumer struct {
	repository  cabinOwnerPersistence
	client      *kgo.Client
	logger      *slog.Logger
	sourceTopic string
}

type cabinOwnerPersistence interface {
	ApplyCabinOwnerMessage(context.Context, persistence.CabinOwnerMessage) (persistence.CabinOwnerApplyResult, error)
	RecordCabinOwnerDLT(context.Context, persistence.CabinOwnerDeadLetter) error
}

func NewCabinOwnerKafkaConsumer(brokers []string, group, topic string) (*kgo.Client, error) {
	if len(brokers) == 0 || group != persistence.CabinOwnerConsumerGroup ||
		topic != persistence.AssetRentalItemTopic {
		return nil, fmt.Errorf("cabin owner Kafka configuration is not canonical")
	}
	return newCabinOwnerKafkaClient(brokers, group, topic)
}

func NewCabinOwnerKafkaConsumerForIsolatedTest(
	brokers []string,
	group string,
	physicalTopic string,
) (*kgo.Client, error) {
	if len(brokers) == 0 || strings.TrimSpace(group) == "" ||
		strings.TrimSpace(physicalTopic) == "" || group == persistence.CabinOwnerConsumerGroup ||
		physicalTopic == persistence.AssetRentalItemTopic {
		return nil, fmt.Errorf("isolated cabin owner Kafka resources are required")
	}
	return newCabinOwnerKafkaClient(brokers, group, physicalTopic)
}

func newCabinOwnerKafkaClient(brokers []string, group, topic string) (*kgo.Client, error) {
	return kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topic),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
		kgo.FetchMaxBytes(2<<20),
		kgo.FetchMaxPartitionBytes(cabinOwnerRecordLimit),
	)
}

func NewCabinOwnerConsumer(
	repository *persistence.Repository,
	client *kgo.Client,
	logger *slog.Logger,
) *CabinOwnerConsumer {
	return newCabinOwnerConsumer(repository, client, logger, persistence.AssetRentalItemTopic)
}

func newCabinOwnerConsumer(
	repository cabinOwnerPersistence,
	client *kgo.Client,
	logger *slog.Logger,
	sourceTopic string,
) *CabinOwnerConsumer {
	if strings.TrimSpace(sourceTopic) == "" {
		sourceTopic = persistence.AssetRentalItemTopic
	}
	return &CabinOwnerConsumer{
		repository: repository, client: client, logger: logger, sourceTopic: sourceTopic,
	}
}

func (consumer *CabinOwnerConsumer) Run(ctx context.Context) error {
	for {
		fetches := consumer.client.PollFetches(ctx)
		if ctx.Err() != nil {
			return nil
		}
		if errs := fetches.Errors(); len(errs) > 0 {
			consumer.logger.Error("poll cabin owner topic", "errorType", "BROKER_UNAVAILABLE")
			continue
		}
		for iterator := fetches.RecordIter(); !iterator.Done(); {
			record := iterator.Next()
			if err := consumer.handle(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				if ctx.Err() != nil {
					return nil
				}
				consumer.logger.Error("process cabin owner fact", "eventOffset", record.Offset,
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

func (consumer *CabinOwnerConsumer) handle(ctx context.Context, record *kgo.Record) error {
	logicalRecord := record
	if record != nil && record.Topic == consumer.sourceTopic &&
		consumer.sourceTopic != persistence.AssetRentalItemTopic {
		copyRecord := *record
		copyRecord.Topic = persistence.AssetRentalItemTopic
		logicalRecord = &copyRecord
	}
	message, err := parseCabinOwnerRecord(logicalRecord)
	if err != nil {
		return consumer.repository.RecordCabinOwnerDLT(ctx, invalidCabinOwnerDeadLetter(record))
	}
	_, err = consumer.repository.ApplyCabinOwnerMessage(ctx, message)
	return err
}

func (consumer *CabinOwnerConsumer) Close() {
	consumer.client.Close()
}

type assetRentalEnvelope struct {
	EnvelopeVersion  int             `json:"envelopeVersion"`
	EventID          string          `json:"eventId"`
	EventType        string          `json:"eventType"`
	EventVersion     int             `json:"eventVersion"`
	OccurredAt       json.RawMessage `json:"occurredAt"`
	RecordedAt       string          `json:"recordedAt"`
	Producer         string          `json:"producer"`
	AggregateType    string          `json:"aggregateType"`
	AggregateID      string          `json:"aggregateId"`
	AggregateVersion int64           `json:"aggregateVersion"`
	Correlation      struct {
		CorrelationID string          `json:"correlationId"`
		CausationID   json.RawMessage `json:"causationId"`
	} `json:"correlation"`
	ActorRef json.RawMessage `json:"actorRef"`
	Payload  json.RawMessage `json:"payload"`
}

type assetRentalProofPayload struct {
	RentalItemID string `json:"rentalItemId"`
	WarehouseID  string `json:"warehouseId"`
	Status       string `json:"status"`
	NumberSHA256 string `json:"numberSha256"`
}

type assetRentalCommentPayload struct {
	RentalItemID    string `json:"rentalItemId"`
	CommentRevision int64  `json:"commentRevision"`
}

type assetRentalNotePayload struct {
	RentalItemID string `json:"rentalItemId"`
	NoteID       string `json:"noteId"`
}

func parseCabinOwnerRecord(record *kgo.Record) (persistence.CabinOwnerMessage, error) {
	if record == nil || record.Topic != persistence.AssetRentalItemTopic || len(record.Value) == 0 ||
		len(record.Value) > cabinOwnerRecordLimit {
		return persistence.CabinOwnerMessage{}, errors.New("invalid asset rental-item record")
	}
	decoder := json.NewDecoder(bytes.NewReader(record.Value))
	decoder.DisallowUnknownFields()
	var envelope assetRentalEnvelope
	if err := decoder.Decode(&envelope); err != nil {
		return persistence.CabinOwnerMessage{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return persistence.CabinOwnerMessage{}, errors.New("trailing asset rental-item data")
	}
	eventID, eventErr := strictUUID(envelope.EventID)
	aggregateID, aggregateErr := strictUUID(envelope.AggregateID)
	recordKey, keyErr := strictUUID(string(record.Key))
	_, correlationErr := strictUUID(envelope.Correlation.CorrelationID)
	recordedAt, recordedErr := time.Parse(time.RFC3339Nano, envelope.RecordedAt)
	if eventErr != nil || aggregateErr != nil || keyErr != nil || correlationErr != nil ||
		recordedErr != nil || envelope.EnvelopeVersion != 2 || envelope.EventVersion != 1 ||
		envelope.Producer != "asset-service" || envelope.AggregateType != persistence.CabinOwnerAggregate ||
		envelope.AggregateVersion < 0 || recordKey != aggregateID ||
		!nullableInventoryDateTime(envelope.OccurredAt) ||
		!nullableInventoryUUID(envelope.Correlation.CausationID) ||
		!validInventoryActor(envelope.ActorRef) {
		return persistence.CabinOwnerMessage{}, errors.New("invalid asset rental-item envelope")
	}
	sum := sha256.Sum256(record.Value)
	message := persistence.CabinOwnerMessage{
		EventID: eventID, BodySHA256: hex.EncodeToString(sum[:]),
		WireBody: append([]byte(nil), record.Value...), Topic: record.Topic,
		EventType: envelope.EventType, AggregateType: envelope.AggregateType,
		AggregateID: aggregateID, AggregateVersion: envelope.AggregateVersion,
		RecordKey: recordKey, RecordedAt: recordedAt.UTC(),
	}
	switch envelope.EventType {
	case "asset.rental-item.created.v1", "asset.rental-item.passport-changed.v1",
		"asset.rental-item.status-changed.v1", "asset.rental-item.warehouse-changed.v1",
		"asset.rental-item.logistics-effect-applied.v1":
		if err := exactJSONFields(envelope.Payload, "rentalItemId", "warehouseId", "status",
			"numberSha256"); err != nil {
			return persistence.CabinOwnerMessage{}, err
		}
		var payload assetRentalProofPayload
		if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
			return persistence.CabinOwnerMessage{}, err
		}
		ownerID, ownerErr := strictUUID(payload.RentalItemID)
		warehouseID, warehouseErr := strictUUID(payload.WarehouseID)
		if ownerErr != nil || warehouseErr != nil || !validLowerSHA256(payload.NumberSHA256) {
			return persistence.CabinOwnerMessage{}, errors.New("invalid asset rental-item owner proof")
		}
		message.PayloadOwnerID = ownerID
		message.Proof = &persistence.CabinOwnerProof{
			WarehouseID: warehouseID, Status: payload.Status,
			OwnerRevision: envelope.AggregateVersion, Active: payload.Status != "WRITTEN_OFF",
		}
	case "asset.rental-item.general-comment-changed.v1":
		if err := exactJSONFields(envelope.Payload, "rentalItemId", "commentRevision"); err != nil {
			return persistence.CabinOwnerMessage{}, err
		}
		var payload assetRentalCommentPayload
		if err := json.Unmarshal(envelope.Payload, &payload); err != nil || payload.CommentRevision < 0 {
			return persistence.CabinOwnerMessage{}, errors.New("invalid asset rental-item comment marker")
		}
		ownerID, err := strictUUID(payload.RentalItemID)
		if err != nil {
			return persistence.CabinOwnerMessage{}, err
		}
		message.PayloadOwnerID = ownerID
	case "asset.rental-item.manual-note-added.v1":
		if err := exactJSONFields(envelope.Payload, "rentalItemId", "noteId"); err != nil {
			return persistence.CabinOwnerMessage{}, err
		}
		var payload assetRentalNotePayload
		if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
			return persistence.CabinOwnerMessage{}, err
		}
		ownerID, ownerErr := strictUUID(payload.RentalItemID)
		_, noteErr := strictUUID(payload.NoteID)
		if ownerErr != nil || noteErr != nil {
			return persistence.CabinOwnerMessage{}, errors.New("invalid asset rental-item note marker")
		}
		message.PayloadOwnerID = ownerID
	default:
		return persistence.CabinOwnerMessage{}, errors.New("unsupported asset rental-item event")
	}
	return message, nil
}

func invalidCabinOwnerDeadLetter(record *kgo.Record) persistence.CabinOwnerDeadLetter {
	var body []byte
	if record != nil {
		body = record.Value
	}
	sum := sha256.Sum256(body)
	return persistence.CabinOwnerDeadLetter{BodySHA256: hex.EncodeToString(sum[:])}
}
