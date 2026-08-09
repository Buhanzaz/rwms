// Package worker runs the durable Kafka consumers, processing workers, and
// operator-only reconciliation commands used by media-service.
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
	"sort"
	"strings"
	"sync"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/realtime"
	"dev.buhanzaz.rwms/media-service/internal/storage"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

// Processor dispatches a claimed media generation to its image or video
// transformation implementation.
type Processor struct {
	Image media.ImageProcessor
	Video media.VideoProcessor
}

const (
	processingPersistenceAttemptLimit = 4
	processingCommitAttemptLimit      = 3
	processingCommitAttemptTimeout    = 3 * time.Second
	processingCircuitFailureThreshold = 2
	processingCircuitOpenDuration     = 5 * time.Second
	processingTelemetryInterval       = 30 * time.Second
)

// ProcessingPartitionTelemetry reports progress for one bounded Kafka
// partition dimension. It intentionally contains no event or media identity.
type ProcessingPartitionTelemetry struct {
	Partition           int32
	LastHandledOffset   int64
	LastCommittedOffset int64
	HasCommittedOffset  bool
	CommitFailures      int64
}

// ProcessingConsumerTelemetry combines durable recovery state with local
// offset and circuit-breaker progress for one processing consumer instance.
type ProcessingConsumerTelemetry struct {
	Recovery     persistence.ProcessingRecoveryTelemetry
	BreakerState string
	Partitions   []ProcessingPartitionTelemetry
}

// processingFailureClass is the closed worker-side distinction between
// cancellation, dependency outage, and a terminal processor result.
type processingFailureClass string

const (
	processingFailureCanceled  processingFailureClass = "CANCELED"
	processingFailureTransient processingFailureClass = "TRANSIENT_DEPENDENCY"
	processingFailureTerminal  processingFailureClass = "TERMINAL_PROCESSOR"
)

// ProcessingRecoveryObserver receives fixed-cardinality recovery snapshots.
// Implementations must not enrich observations with record or payload data.
type ProcessingRecoveryObserver interface {
	Observe(ProcessingConsumerTelemetry)
}

// processingRepository is the service-owned persistence boundary required by
// the Kafka processing consumer.
type processingRepository interface {
	ClaimProcessingJob(context.Context, persistence.ProcessingMessage, string, time.Duration) (persistence.ClaimResult, error)
	ResolveProcessingClaimConflict(context.Context, persistence.ProcessingMessage) (persistence.ProcessingConflictResolution, error)
	CompleteProcessingJob(context.Context, persistence.WorkerJob, []media.ProcessedVariant) error
	RecordProcessingFailure(context.Context, persistence.WorkerJob, string) (time.Duration, bool, error)
	RecordProcessingAttemptExhaustion(context.Context, persistence.WorkerJob) error
	RecordInvalidProcessingMessage(context.Context, persistence.ProcessingMessage, string) error
	ProcessingRecoveryTelemetry(context.Context) (persistence.ProcessingRecoveryTelemetry, error)
	ReleaseProcessingLeases(context.Context, string) error
}

// processingDependencyCircuit prevents a failing object dependency from being
// hammered across adjacent processing records while retaining durable retries.
type processingDependencyCircuit struct {
	failureThreshold int
	openDuration     time.Duration
	consecutive      int
	openUntil        time.Time
	probe            bool
	now              func() time.Time
}

// slogProcessingRecoveryObserver emits the typed telemetry through the
// service's existing structured operational log surface.
type slogProcessingRecoveryObserver struct {
	logger *slog.Logger
}

// Observe writes one bounded snapshot without record or domain identifiers.
func (observer slogProcessingRecoveryObserver) Observe(snapshot ProcessingConsumerTelemetry) {
	observer.logger.Info("media processing recovery telemetry",
		"breakerState", snapshot.BreakerState,
		"pendingJobs", snapshot.Recovery.PendingJobs,
		"runningJobs", snapshot.Recovery.RunningJobs,
		"pendingValidationReviews", snapshot.Recovery.PendingValidationReviews,
		"pendingDependencyReviews", snapshot.Recovery.PendingDependencyReviews,
		"pendingExhaustedReviews", snapshot.Recovery.PendingExhaustedReviews,
		"pendingLegacyReviews", snapshot.Recovery.PendingLegacyReviews,
		"oldestActiveJobAgeSeconds", snapshot.Recovery.OldestActiveJobAge.Seconds(),
		"maximumActiveAttemptInCycle", snapshot.Recovery.MaximumActiveAttemptInCycle,
		"partitionOffsets", snapshot.Partitions)
}

// processingRecoveryObserverFanout delivers one bounded snapshot to the
// retained structured-log observer and any explicitly registered observers.
// Registration is synchronized because management setup and a scrape-capable
// observer must never race an in-flight telemetry emission.
type processingRecoveryObserverFanout struct {
	mutex     sync.RWMutex
	observers []ProcessingRecoveryObserver
}

// newProcessingRecoveryObserverFanout creates a fanout after removing nil
// observers, which keeps telemetry emission independent from optional wiring.
func newProcessingRecoveryObserverFanout(
	observers ...ProcessingRecoveryObserver,
) *processingRecoveryObserverFanout {
	fanout := &processingRecoveryObserverFanout{}
	for _, observer := range observers {
		fanout.Add(observer)
	}
	return fanout
}

// Add registers one observer while preserving every existing observer.
func (fanout *processingRecoveryObserverFanout) Add(observer ProcessingRecoveryObserver) {
	if fanout == nil || observer == nil {
		return
	}
	fanout.mutex.Lock()
	fanout.observers = append(fanout.observers, observer)
	fanout.mutex.Unlock()
}

// Observe copies registrations before invoking them so a slow observer cannot
// block a concurrent metrics registration while preserving observation order.
func (fanout *processingRecoveryObserverFanout) Observe(snapshot ProcessingConsumerTelemetry) {
	if fanout == nil {
		return
	}
	fanout.mutex.RLock()
	observers := append([]ProcessingRecoveryObserver(nil), fanout.observers...)
	fanout.mutex.RUnlock()
	for _, observer := range observers {
		observer.Observe(snapshot)
	}
}

// Process creates all immutable outputs required for the claimed job's media
// kind. It leaves persistence and retry decisions to Consumer.
func (processor Processor) Process(ctx context.Context, job persistence.WorkerJob) ([]media.ProcessedVariant, error) {
	switch job.MediaKind {
	case media.KindImage:
		result, err := processor.Image.Process(ctx, media.ImageProcessRequest{
			MediaID: job.MediaID.String(), SourceObjectKey: job.SourceObjectKey,
			SourceVersionID: job.SourceVersionID, Generation: job.Generation,
		})
		if err != nil {
			return nil, err
		}
		return append([]media.ProcessedVariant{result.Original}, result.Variants...), nil
	case media.KindVideo:
		result, err := processor.Video.Process(ctx, media.VideoProcessRequest{
			MediaID: job.MediaID.String(), SourceObjectKey: job.SourceObjectKey,
			SourceVersionID: job.SourceVersionID, ContentType: job.ContentType,
			Generation: job.Generation,
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

// Consumer persistently processes media-processing Kafka facts with idempotent
// claims, bounded retries, and offset acknowledgement after durable handling.
type Consumer struct {
	repository       processingRepository
	client           kafkaConsumerClient
	processor        Processor
	owner            string
	lease            time.Duration
	timeout          time.Duration
	pollTimeout      time.Duration
	logger           *slog.Logger
	publisher        realtime.Publisher
	handleRecord     func(context.Context, *kgo.Record) error
	processJob       func(context.Context, persistence.WorkerJob) ([]media.ProcessedVariant, error)
	sleep            func(context.Context, time.Duration) error
	now              func() time.Time
	circuit          *processingDependencyCircuit
	observer         ProcessingRecoveryObserver
	persistLimit     int
	commitLimit      int
	commitTimeout    time.Duration
	telemetryEvery   time.Duration
	lastTelemetry    time.Time
	handledOffsets   map[int32]int64
	committedOffsets map[int32]int64
	commitFailures   map[int32]int64
}

// NewKafkaConsumer creates the manual-commit Kafka client for the canonical
// media-processing topic and consumer group supplied by configuration.
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

// NewConsumer builds the durable media-processing worker for one lease owner.
func NewConsumer(repository *persistence.Repository, client *kgo.Client, processor Processor, owner string, processingTimeout time.Duration, logger *slog.Logger) *Consumer {
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	consumer := &Consumer{repository: repository, client: client, processor: processor, owner: owner,
		lease: processingTimeout + 30*time.Second, timeout: processingTimeout,
		pollTimeout: kafkaConsumerPollTimeout, logger: logger,
		sleep: sleepProcessingConsumer, now: time.Now,
		persistLimit: processingPersistenceAttemptLimit,
		commitLimit:  processingCommitAttemptLimit, commitTimeout: processingCommitAttemptTimeout,
		telemetryEvery: processingTelemetryInterval}
	consumer.handleRecord = consumer.handle
	consumer.processJob = consumer.processor.Process
	consumer.circuit = newProcessingDependencyCircuit(consumer.now)
	consumer.observer = newProcessingRecoveryObserverFanout(slogProcessingRecoveryObserver{logger: logger})
	return consumer
}

// SetInvalidationPublisher attaches the optional process-local signal emitted
// after a processing job reaches a visible terminal state.
func (consumer *Consumer) SetInvalidationPublisher(publisher realtime.Publisher) {
	consumer.publisher = publisher
}

// SetRecoveryObserver replaces optional observers while retaining the
// structured-log observer. New production integrations should use
// AddRecoveryObserver so independently owned observers fan out together.
func (consumer *Consumer) SetRecoveryObserver(observer ProcessingRecoveryObserver) {
	if observer == nil {
		return
	}
	consumer.observer = newProcessingRecoveryObserverFanout(
		slogProcessingRecoveryObserver{logger: processingRecoveryLogger(consumer.logger)}, observer)
}

// AddRecoveryObserver registers an additional bounded telemetry observer
// without replacing the existing structured operational log observer.
func (consumer *Consumer) AddRecoveryObserver(observer ProcessingRecoveryObserver) {
	if observer == nil {
		return
	}
	if fanout, ok := consumer.observer.(*processingRecoveryObserverFanout); ok {
		fanout.Add(observer)
		return
	}
	if consumer.observer != nil {
		consumer.observer = newProcessingRecoveryObserverFanout(consumer.observer, observer)
		return
	}
	consumer.observer = newProcessingRecoveryObserverFanout(
		slogProcessingRecoveryObserver{logger: processingRecoveryLogger(consumer.logger)}, observer)
}

// newProcessingDependencyCircuit creates the fixed worker-side breaker policy.
func newProcessingDependencyCircuit(now func() time.Time) *processingDependencyCircuit {
	return &processingDependencyCircuit{
		failureThreshold: processingCircuitFailureThreshold,
		openDuration:     processingCircuitOpenDuration,
		now:              now,
	}
}

// wait blocks only until an open circuit permits one half-open probe.
func (circuit *processingDependencyCircuit) wait(
	ctx context.Context,
	sleep func(context.Context, time.Duration) error,
) error {
	if circuit == nil || circuit.openUntil.IsZero() {
		return nil
	}
	delay := circuit.openUntil.Sub(circuit.now())
	if delay > 0 {
		if err := sleep(ctx, delay); err != nil {
			return err
		}
	}
	circuit.openUntil = time.Time{}
	circuit.probe = true
	return nil
}

// failure advances the breaker without retaining the underlying error.
func (circuit *processingDependencyCircuit) failure() {
	if circuit == nil {
		return
	}
	circuit.consecutive++
	if circuit.probe || circuit.consecutive >= circuit.failureThreshold {
		circuit.openUntil = circuit.now().Add(circuit.openDuration)
		circuit.probe = false
	}
}

// success closes the breaker after a dependency-safe processor outcome.
func (circuit *processingDependencyCircuit) success() {
	if circuit == nil {
		return
	}
	circuit.consecutive = 0
	circuit.openUntil = time.Time{}
	circuit.probe = false
}

// state returns one of the fixed CLOSED, OPEN, or HALF_OPEN telemetry values.
func (circuit *processingDependencyCircuit) state() string {
	if circuit == nil {
		return "CLOSED"
	}
	if !circuit.openUntil.IsZero() && circuit.openUntil.After(circuit.now()) {
		return "OPEN"
	}
	if circuit.probe {
		return "HALF_OPEN"
	}
	return "CLOSED"
}

// prepare fills runtime defaults for constructed and focused-test consumers.
func (consumer *Consumer) prepare() {
	if consumer.logger == nil {
		consumer.logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if consumer.handleRecord == nil {
		consumer.handleRecord = consumer.handle
	}
	if consumer.processJob == nil {
		consumer.processJob = consumer.processor.Process
	}
	if consumer.sleep == nil {
		consumer.sleep = sleepProcessingConsumer
	}
	if consumer.now == nil {
		consumer.now = time.Now
	}
	if consumer.circuit == nil {
		consumer.circuit = newProcessingDependencyCircuit(consumer.now)
	}
	if consumer.persistLimit <= 0 {
		consumer.persistLimit = processingPersistenceAttemptLimit
	}
	if consumer.commitLimit <= 0 {
		consumer.commitLimit = processingCommitAttemptLimit
	}
	if consumer.commitTimeout <= 0 {
		consumer.commitTimeout = processingCommitAttemptTimeout
	}
	if consumer.telemetryEvery <= 0 {
		consumer.telemetryEvery = processingTelemetryInterval
	}
	if consumer.timeout <= 0 {
		consumer.timeout = 30 * time.Second
	}
	if consumer.lease <= 0 {
		consumer.lease = consumer.timeout + 30*time.Second
	}
	if consumer.handledOffsets == nil {
		consumer.handledOffsets = make(map[int32]int64)
	}
	if consumer.committedOffsets == nil {
		consumer.committedOffsets = make(map[int32]int64)
	}
	if consumer.commitFailures == nil {
		consumer.commitFailures = make(map[int32]int64)
	}
	if consumer.observer == nil {
		consumer.observer = newProcessingRecoveryObserverFanout(
			slogProcessingRecoveryObserver{logger: processingRecoveryLogger(consumer.logger)})
	}
}

// processingRecoveryLogger returns a safe logger for observer construction in
// focused tests and normal worker initialization alike.
func processingRecoveryLogger(logger *slog.Logger) *slog.Logger {
	if logger != nil {
		return logger
	}
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// Run polls, persists, and then acknowledges media-processing records until
// ctx is canceled or a dependency failure requires the runtime to restart it.
func (consumer *Consumer) Run(ctx context.Context) error {
	consumer.prepare()
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
			// A fetch error is still a completed poll under
			// BlockRebalanceOnPoll. Release it before retrying so the group
			// heartbeat can rejoin once the broker or coordinator recovers.
			consumer.client.AllowRebalance()
			consumer.logger.Error("poll media processing topic", "errorType", "BROKER_UNAVAILABLE")
			continue
		}
		for iterator := fetches.RecordIter(); !iterator.Done(); {
			record := iterator.Next()
			if err := consumer.handleUntilPersisted(ctx, record); err != nil {
				consumer.client.AllowRebalance()
				if ctx.Err() != nil {
					return nil
				}
				return err
			}
			consumer.handledOffsets[record.Partition] = record.Offset
			if err := consumer.commitUntilAcknowledged(ctx, record); err != nil {
				consumer.emitRecoveryTelemetry(ctx)
				consumer.client.AllowRebalance()
				if ctx.Err() != nil {
					return nil
				}
				return err
			}
			consumer.committedOffsets[record.Partition] = record.Offset
			consumer.emitRecoveryTelemetry(ctx)
		}
		consumer.client.AllowRebalance()
	}
}

// handleUntilPersisted bounds transient persistence retries for one ordered
// record and returns an explicit restart error without acknowledging it.
func (consumer *Consumer) handleUntilPersisted(ctx context.Context, record *kgo.Record) error {
	delay := 250 * time.Millisecond
	for attempt := 1; attempt <= consumer.persistLimit; attempt++ {
		err := consumer.handleRecord(ctx, record)
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		failureType := "PERSISTENCE_UNAVAILABLE"
		if errors.Is(err, persistence.ErrConflict) || errors.Is(err, persistence.ErrIdempotencyMismatch) ||
			errors.Is(err, persistence.ErrVersionGap) {
			failureType = "PROCESSING_STATE_UNRESOLVED"
		}
		consumer.logger.Error("persist media processing outcome", "partition", record.Partition,
			"offset", record.Offset, "failureType", failureType,
			"attempt", attempt)
		if attempt == consumer.persistLimit {
			return fmt.Errorf("media processing persistence retry exhausted: %w", err)
		}
		if err := consumer.sleep(ctx, delay); err != nil {
			return err
		}
		if delay < time.Second {
			delay *= 2
		}
	}
	return errors.New("media processing persistence retry exhausted")
}

// commitUntilAcknowledged applies a per-call deadline and a total attempt bound
// so shutdown or rebalance cannot be held by one unavailable broker commit.
func (consumer *Consumer) commitUntilAcknowledged(ctx context.Context, record *kgo.Record) error {
	delay := 250 * time.Millisecond
	for attempt := 1; attempt <= consumer.commitLimit; attempt++ {
		commitContext, cancel := context.WithTimeout(ctx, consumer.commitTimeout)
		err := consumer.client.CommitRecords(commitContext, record)
		cancel()
		if err == nil {
			return nil
		}
		consumer.commitFailures[record.Partition]++
		if ctx.Err() != nil {
			return ctx.Err()
		}
		consumer.logger.Error("commit media processing offset", "partition", record.Partition,
			"offset", record.Offset, "failureType", "BROKER_COMMIT_UNAVAILABLE",
			"attempt", attempt)
		if attempt == consumer.commitLimit {
			return fmt.Errorf("media processing offset commit retry exhausted: %w", err)
		}
		if err := consumer.sleep(ctx, delay); err != nil {
			return err
		}
		if delay < time.Second {
			delay *= 2
		}
	}
	return errors.New("media processing offset commit retry exhausted")
}

// emitRecoveryTelemetry samples durable recovery state and local offsets at a
// bounded cadence without changing record handling on observer failure.
func (consumer *Consumer) emitRecoveryTelemetry(ctx context.Context) {
	if consumer.repository == nil || consumer.observer == nil || ctx.Err() != nil {
		return
	}
	now := consumer.now()
	if !consumer.lastTelemetry.IsZero() && now.Sub(consumer.lastTelemetry) < consumer.telemetryEvery {
		return
	}
	consumer.lastTelemetry = now
	telemetryContext, cancel := context.WithTimeout(ctx, time.Second)
	defer cancel()
	recovery, err := consumer.repository.ProcessingRecoveryTelemetry(telemetryContext)
	if err != nil {
		consumer.logger.Warn("read media processing recovery telemetry",
			"failureType", "PERSISTENCE_UNAVAILABLE")
		return
	}
	partitions := make([]int, 0, len(consumer.handledOffsets))
	for partition := range consumer.handledOffsets {
		partitions = append(partitions, int(partition))
	}
	sort.Ints(partitions)
	offsets := make([]ProcessingPartitionTelemetry, 0, len(partitions))
	for _, partitionValue := range partitions {
		partition := int32(partitionValue)
		committedOffset, hasCommittedOffset := consumer.committedOffsets[partition]
		offsets = append(offsets, ProcessingPartitionTelemetry{
			Partition: partition, LastHandledOffset: consumer.handledOffsets[partition],
			LastCommittedOffset: committedOffset, HasCommittedOffset: hasCommittedOffset,
			CommitFailures: consumer.commitFailures[partition],
		})
	}
	consumer.observer.Observe(ProcessingConsumerTelemetry{
		Recovery: recovery, BreakerState: consumer.circuit.state(), Partitions: offsets,
	})
}

// handle maps one record to a durable poison, retry, terminal, success, or
// exact-duplicate outcome before offset acknowledgement is attempted.
func (consumer *Consumer) handle(ctx context.Context, record *kgo.Record) error {
	consumer.prepare()
	message, err := parseProcessingRequest(record)
	if err != nil {
		sanitized := invalidMessage(record)
		return consumer.repository.RecordInvalidProcessingMessage(ctx, sanitized, "INVALID_PROCESSING_REQUEST")
	}
	conflictRetries := 0
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
				conflictRetries++
				if conflictRetries > processingPersistenceAttemptLimit {
					return persistence.ErrConflict
				}
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
		if claim.AttemptBudgetExhausted {
			err := consumer.repository.RecordProcessingAttemptExhaustion(ctx, claim.Job)
			if err == nil {
				consumer.publish(claim.Job, "MEDIA_CHANGED")
			}
			return err
		}
		if err := consumer.circuit.wait(ctx, consumer.sleep); err != nil {
			return err
		}
		processingContext, cancel := context.WithTimeout(ctx, consumer.timeout)
		variants, processErr := consumer.processJob(processingContext, claim.Job)
		cancel()
		if processErr == nil {
			consumer.circuit.success()
			err := consumer.repository.CompleteProcessingJob(ctx, claim.Job, variants)
			if err == nil {
				consumer.publish(claim.Job, "MEDIA_CHANGED")
			}
			return err
		}
		job := claim.Job
		switch classifyProcessingFailure(processErr) {
		case processingFailureCanceled:
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return processErr
		case processingFailureTerminal:
			consumer.circuit.success()
			_, _, err = consumer.repository.RecordProcessingFailure(ctx, job, "VALIDATION_FAILED")
			if err == nil {
				consumer.publish(job, "MEDIA_CHANGED")
			}
			return err
		}
		consumer.circuit.failure()
		delay, terminal, err := consumer.repository.RecordProcessingFailure(ctx, job, "PROCESSING_DEPENDENCY_UNAVAILABLE")
		if err != nil || terminal {
			if err == nil && terminal {
				consumer.publish(job, "MEDIA_CHANGED")
			}
			return err
		}
		if err := consumer.sleep(ctx, delay); err != nil {
			return err
		}
	}
}

func (consumer *Consumer) publish(job persistence.WorkerJob, scope string) {
	if consumer.publisher == nil || job.WarehouseID == uuid.Nil || job.MediaID == uuid.Nil {
		return
	}
	ownerType, ownerID := "", ""
	if job.OwnerType == persistence.OwnerTypeCabin {
		ownerType, ownerID = job.OwnerType, job.OwnerID
	}
	consumer.publisher.Publish(realtime.Event{
		EventID: uuid.New(), WarehouseID: job.WarehouseID, Scope: scope,
		MediaID: job.MediaID, OwnerType: ownerType, OwnerID: ownerID, Generation: job.Generation,
		OccurredAt: time.Now().UTC(),
	})
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

// Close stops Kafka consumption and releases this worker's processing leases.
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
	if envelope.Payload.ProcessingKind != media.ProcessingInitial {
		return persistence.ProcessingMessage{}, errors.New("invalid processing kind")
	}
	if envelope.Payload.Rotation != media.Rotation0 {
		return persistence.ProcessingMessage{}, errors.New("invalid processing rotation")
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

func classifyProcessingFailure(err error) processingFailureClass {
	if errors.Is(err, context.Canceled) {
		return processingFailureCanceled
	}
	if errors.Is(err, storage.ErrObjectVersionMismatch) {
		return processingFailureTerminal
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, storage.ErrDependency) {
		return processingFailureTransient
	}
	var networkError net.Error
	if errors.As(err, &networkError) || strings.Contains(err.Error(), "MinIO") {
		return processingFailureTransient
	}
	return processingFailureTerminal
}
