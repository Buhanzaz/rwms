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
	"net"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

type Processor struct {
	Image media.ImageProcessor
	Video media.VideoProcessor
}

func (processor Processor) Process(ctx context.Context, job persistence.WorkerJob) ([]media.ProcessedVariant, error) {
	switch job.MediaKind {
	case media.KindImage:
		result, err := processor.Image.Process(ctx, media.ImageProcessRequest{
			MediaID: job.MediaID.String(), SourceObjectKey: job.SourceObjectKey,
			SourceVersionID: job.SourceVersionID, Generation: job.Generation, Rotation: job.Rotation,
		})
		if err != nil {
			return nil, err
		}
		return append([]media.ProcessedVariant{result.Original}, result.Variants...), nil
	case media.KindVideo:
		result, err := processor.Video.Process(ctx, media.VideoProcessRequest{
			MediaID: job.MediaID.String(), SourceObjectKey: job.SourceObjectKey,
			SourceVersionID: job.SourceVersionID, ContentType: job.ContentType,
			Generation: job.Generation, Rotation: job.Rotation,
		})
		if err != nil {
			return nil, err
		}
		// Unproved video dimensions remain zero in Go and are persisted as SQL NULL.
		return []media.ProcessedVariant{result.Original}, nil
	default:
		return nil, fmt.Errorf("unsupported media kind")
	}
}

type Consumer struct {
	repository   *persistence.Repository
	client       processingKafkaClient
	processor    Processor
	owner        string
	lease        time.Duration
	timeout      time.Duration
	logger       *slog.Logger
	handleRecord func(context.Context, *kgo.Record) error
	sleep        func(context.Context, time.Duration) error
}

type processingKafkaClient interface {
	PollFetches(context.Context) kgo.Fetches
	CommitRecords(context.Context, ...*kgo.Record) error
	AllowRebalance()
	Close()
}

func NewKafkaConsumer(brokers []string, group, topic string) (*kgo.Client, error) {
	return kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topic),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
		kgo.FetchMaxBytes(2<<20),
		kgo.FetchMaxPartitionBytes(1<<20),
	)
}

func NewConsumer(repository *persistence.Repository, client *kgo.Client, processor Processor, owner string, processingTimeout time.Duration, logger *slog.Logger) *Consumer {
	consumer := &Consumer{repository: repository, client: client, processor: processor, owner: owner,
		lease: processingTimeout + 30*time.Second, timeout: processingTimeout, logger: logger,
		sleep: sleepProcessingConsumer}
	consumer.handleRecord = consumer.handle
	return consumer
}

func (consumer *Consumer) Run(ctx context.Context) error {
	for {
		fetches := consumer.client.PollFetches(ctx)
		if ctx.Err() != nil {
			return nil
		}
		if errs := fetches.Errors(); len(errs) > 0 {
			consumer.logger.Error("poll media processing topic", "errorType", "BROKER_UNAVAILABLE")
			continue
		}
		for iterator := fetches.RecordIter(); !iterator.Done(); {
			record := iterator.Next()
			if err := consumer.handleUntilPersisted(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				return nil
			}
			if err := consumer.commitUntilAcknowledged(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				return nil
			}
		}
		consumer.client.AllowRebalance()
	}
}

func (consumer *Consumer) handleUntilPersisted(ctx context.Context, record *kgo.Record) error {
	delay := time.Second
	for {
		err := consumer.handleRecord(ctx, record)
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		consumer.logger.Error("process media request", "eventOffset", record.Offset,
			"errorType", "DEPENDENCY_ERROR")
		if err := consumer.sleep(ctx, delay); err != nil {
			return err
		}
		if delay < 4*time.Second {
			delay *= 2
		}
	}
}

func (consumer *Consumer) commitUntilAcknowledged(ctx context.Context, record *kgo.Record) error {
	delay := time.Second
	for {
		if err := consumer.client.CommitRecords(ctx, record); err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		consumer.logger.Error("commit media request", "eventOffset", record.Offset,
			"errorType", "BROKER_UNAVAILABLE")
		if err := consumer.sleep(ctx, delay); err != nil {
			return err
		}
		if delay < 4*time.Second {
			delay *= 2
		}
	}
}

func (consumer *Consumer) handle(ctx context.Context, record *kgo.Record) error {
	message, err := parseProcessingRequest(record)
	if err != nil {
		sanitized := invalidMessage(record)
		return consumer.repository.RecordInvalidProcessingMessage(ctx, sanitized, "INVALID_PROCESSING_REQUEST")
	}
	for {
		claim, err := consumer.repository.ClaimProcessingJob(ctx, message, consumer.owner, consumer.lease)
		if errors.Is(err, persistence.ErrVersionGap) {
			return nil
		}
		if errors.Is(err, persistence.ErrConflict) {
			resolution, resolveErr := consumer.repository.ResolveProcessingClaimConflict(ctx, message)
			if resolveErr != nil {
				return resolveErr
			}
			switch resolution.Disposition {
			case persistence.ProcessingConflictTerminalConflict:
				return nil
			case persistence.ProcessingConflictRetryAt:
				delay := time.Until(resolution.RetryAt)
				if delay <= 0 {
					delay = 100 * time.Millisecond
				}
				if err := consumer.sleep(ctx, delay); err != nil {
					return err
				}
				continue
			default:
				return persistence.ErrConflict
			}
		}
		if err != nil {
			return err
		}
		if claim.Duplicate {
			return nil
		}
		processingContext, cancel := context.WithTimeout(ctx, consumer.timeout)
		variants, processErr := consumer.processor.Process(processingContext, claim.Job)
		cancel()
		if processErr == nil {
			return consumer.repository.CompleteProcessingJob(ctx, claim.Job, variants)
		}
		job := claim.Job
		if !transient(processErr) {
			_, _, err = consumer.repository.RecordProcessingFailure(ctx, job, "VALIDATION_FAILED")
			return err
		}
		delay, terminal, err := consumer.repository.RecordProcessingFailure(ctx, job, "PROCESSING_DEPENDENCY_UNAVAILABLE")
		if err != nil || terminal {
			return err
		}
		if err := consumer.sleep(ctx, delay); err != nil {
			return err
		}
	}
}

func sleepProcessingConsumer(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func (consumer *Consumer) Close(ctx context.Context) error {
	consumer.client.Close()
	return consumer.repository.ReleaseProcessingLeases(ctx, consumer.owner)
}

type processingEnvelope struct {
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
	Payload  struct {
		ProcessingJobID string               `json:"processingJobId"`
		MediaID         string               `json:"mediaId"`
		WarehouseID     string               `json:"warehouseId"`
		Kind            media.Kind           `json:"kind"`
		ProcessingKind  media.ProcessingKind `json:"processingKind"`
		Generation      int                  `json:"generation"`
		Rotation        media.Rotation       `json:"rotationDegrees"`
		SourceVersionID string               `json:"sourceVersionId"`
	} `json:"payload"`
}

func parseProcessingRequest(record *kgo.Record) (persistence.ProcessingMessage, error) {
	if record == nil || record.Topic != persistence.ProcessingTopic || len(record.Value) == 0 || len(record.Value) > 1<<20 {
		return persistence.ProcessingMessage{}, errors.New("invalid processing record")
	}
	decoder := json.NewDecoder(bytes.NewReader(record.Value))
	decoder.DisallowUnknownFields()
	var envelope processingEnvelope
	if err := decoder.Decode(&envelope); err != nil {
		return persistence.ProcessingMessage{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return persistence.ProcessingMessage{}, errors.New("trailing processing data")
	}
	eventID, eventErr := uuid.Parse(envelope.EventID)
	aggregateID, aggregateErr := uuid.Parse(envelope.AggregateID)
	jobID, jobErr := uuid.Parse(envelope.Payload.ProcessingJobID)
	mediaID, mediaErr := uuid.Parse(envelope.Payload.MediaID)
	warehouseID, warehouseErr := uuid.Parse(envelope.Payload.WarehouseID)
	key, keyErr := uuid.Parse(string(record.Key))
	correlationID, correlationErr := uuid.Parse(envelope.Correlation.CorrelationID)
	_, recordedErr := time.Parse(time.RFC3339Nano, envelope.RecordedAt)
	if eventErr != nil || aggregateErr != nil || jobErr != nil || mediaErr != nil || warehouseErr != nil ||
		keyErr != nil || correlationErr != nil || recordedErr != nil || envelope.EnvelopeVersion != 2 ||
		envelope.EventType != "media.processing.request.v1" || envelope.EventVersion != 1 ||
		envelope.Producer != "media-service" || envelope.AggregateType != "PROCESSING_JOB" ||
		envelope.AggregateVersion != 1 || aggregateID != jobID || key != jobID || envelope.Payload.Generation <= 0 ||
		strings.TrimSpace(envelope.Payload.SourceVersionID) == "" || len(envelope.Payload.SourceVersionID) > 255 ||
		!nullableDateTime(envelope.OccurredAt) || !nullableUUID(envelope.Correlation.CausationID) ||
		!isNull(envelope.ActorRef) {
		return persistence.ProcessingMessage{}, errors.New("invalid processing envelope")
	}
	if envelope.Payload.Kind != media.KindImage && envelope.Payload.Kind != media.KindVideo {
		return persistence.ProcessingMessage{}, errors.New("invalid media kind")
	}
	if envelope.Payload.ProcessingKind != media.ProcessingInitial && envelope.Payload.ProcessingKind != media.ProcessingRotation {
		return persistence.ProcessingMessage{}, errors.New("invalid processing kind")
	}
	if _, err := media.ParseRotation(int16(envelope.Payload.Rotation)); err != nil {
		return persistence.ProcessingMessage{}, err
	}
	sum := sha256.Sum256(record.Value)
	return persistence.ProcessingMessage{
		EventID: eventID, BodySHA256: hex.EncodeToString(sum[:]), Topic: record.Topic,
		EventType: envelope.EventType, AggregateType: envelope.AggregateType,
		AggregateID: aggregateID, AggregateVersion: envelope.AggregateVersion,
		RecordKey: key, CorrelationID: correlationID, ExpectedMediaID: mediaID,
		ExpectedWarehouseID: warehouseID, ExpectedKind: envelope.Payload.Kind,
		ExpectedProcessingKind: envelope.Payload.ProcessingKind,
		ExpectedGeneration:     envelope.Payload.Generation, ExpectedRotation: envelope.Payload.Rotation,
		ExpectedSourceVersionID: envelope.Payload.SourceVersionID,
	}, nil
}

func invalidMessage(record *kgo.Record) persistence.ProcessingMessage {
	sum := sha256.Sum256(record.Value)
	identifier := uuid.NewSHA1(uuid.NameSpaceOID, sum[:])
	return persistence.ProcessingMessage{
		EventID: identifier, BodySHA256: hex.EncodeToString(sum[:]), Topic: persistence.ProcessingTopic,
		EventType: "media.processing.request.v1", AggregateType: "PROCESSING_JOB",
		AggregateID: identifier, AggregateVersion: 1, RecordKey: identifier,
	}
}

func isNull(value json.RawMessage) bool {
	return len(value) > 0 && string(bytes.TrimSpace(value)) == "null"
}

func nullableDateTime(value json.RawMessage) bool {
	if isNull(value) {
		return true
	}
	var dateTime string
	if err := json.Unmarshal(value, &dateTime); err != nil {
		return false
	}
	_, err := time.Parse(time.RFC3339Nano, dateTime)
	return err == nil
}

func nullableUUID(value json.RawMessage) bool {
	if isNull(value) {
		return true
	}
	var identifier string
	if err := json.Unmarshal(value, &identifier); err != nil {
		return false
	}
	_, err := uuid.Parse(identifier)
	return err == nil
}

func transient(err error) bool {
	var networkError net.Error
	return errors.As(err, &networkError) || strings.Contains(err.Error(), "MinIO")
}
