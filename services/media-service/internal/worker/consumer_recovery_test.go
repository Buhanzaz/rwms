package worker

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/storage"
	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

func TestProcessingConsumerRecoversTransientDependencyThroughCircuitProbe(t *testing.T) {
	started := time.Date(2026, 8, 9, 10, 0, 0, 0, time.UTC)
	now := started
	jobID := uuid.New()
	repository := &processingRecoveryRepositoryStub{}
	for attempt := 1; attempt <= 3; attempt++ {
		repository.claims = append(repository.claims, persistence.ClaimResult{Job: persistence.WorkerJob{
			JobID: jobID, Attempt: attempt, AttemptInCycle: attempt, CreatedAt: started,
		}})
	}
	processorCalls := 0
	consumer := &Consumer{
		repository: repository,
		owner:      "recovery-worker",
		timeout:    time.Minute,
		now:        func() time.Time { return now },
		logger:     slog.New(slog.NewTextHandler(io.Discard, nil)),
		processJob: func(context.Context, persistence.WorkerJob) ([]media.ProcessedVariant, error) {
			processorCalls++
			if processorCalls <= 2 {
				return nil, storage.ErrDependency
			}
			return []media.ProcessedVariant{}, nil
		},
	}
	consumer.sleep = func(_ context.Context, delay time.Duration) error {
		repository.sleeps = append(repository.sleeps, delay)
		now = now.Add(delay)
		return nil
	}
	consumer.prepare()

	if err := consumer.handle(context.Background(), newProcessingRecord(t).record); err != nil {
		t.Fatalf("handle transient recovery: %v", err)
	}
	if processorCalls != 3 || repository.completeCalls != 1 {
		t.Fatalf("processor calls=%d complete=%d, want 3 and 1", processorCalls, repository.completeCalls)
	}
	if len(repository.failureCodes) != 2 ||
		repository.failureCodes[0] != "PROCESSING_DEPENDENCY_UNAVAILABLE" ||
		repository.failureCodes[1] != "PROCESSING_DEPENDENCY_UNAVAILABLE" {
		t.Fatalf("failure codes = %v", repository.failureCodes)
	}
	wantSleeps := []time.Duration{time.Second, 2 * time.Second, 3 * time.Second}
	if !equalDurations(repository.sleeps, wantSleeps) {
		t.Fatalf("sleeps = %v, want %v", repository.sleeps, wantSleeps)
	}
	if state := consumer.circuit.state(); state != "CLOSED" {
		t.Fatalf("circuit state = %s, want CLOSED", state)
	}
}

func TestClassifyProcessingFailureSeparatesDependencyAndTerminal(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want processingFailureClass
	}{
		{name: "shutdown cancellation", err: context.Canceled, want: processingFailureCanceled},
		{name: "processor timeout", err: context.DeadlineExceeded, want: processingFailureTransient},
		{name: "object dependency outage", err: storage.ErrDependency, want: processingFailureTransient},
		{name: "missing pinned version", err: storage.ErrObjectVersionMismatch, want: processingFailureTerminal},
		{name: "validation failure", err: errors.New("unsupported image encoding"), want: processingFailureTerminal},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := classifyProcessingFailure(test.err); got != test.want {
				t.Fatalf("classifyProcessingFailure(%v) = %s, want %s", test.err, got, test.want)
			}
		})
	}
}

func TestProcessingConsumerTerminalizesExhaustedReclaimWithoutProcessor(t *testing.T) {
	repository := &processingRecoveryRepositoryStub{claims: []persistence.ClaimResult{{
		Job:                    persistence.WorkerJob{Attempt: 4, AttemptInCycle: 4},
		AttemptBudgetExhausted: true,
	}}}
	processorCalls := 0
	consumer := &Consumer{
		repository: repository,
		logger:     slog.New(slog.NewTextHandler(io.Discard, nil)),
		processJob: func(context.Context, persistence.WorkerJob) ([]media.ProcessedVariant, error) {
			processorCalls++
			return nil, errors.New("processor must not receive exhausted work")
		},
	}
	if err := consumer.handle(context.Background(), newProcessingRecord(t).record); err != nil {
		t.Fatalf("handle exhausted reclaim: %v", err)
	}
	if processorCalls != 0 || repository.exhaustionCalls != 1 || repository.completeCalls != 0 {
		t.Fatalf("exhausted reclaim processor=%d exhaustion=%d complete=%d",
			processorCalls, repository.exhaustionCalls, repository.completeCalls)
	}
}

func TestProcessingConsumerPersistsPoisonThenHandlesValidDuplicate(t *testing.T) {
	poison := &kgo.Record{Topic: persistence.ProcessingTopic, Partition: 2, Offset: 9,
		Value: []byte(`{"payload":"secret@example.test"}`)}
	valid := newProcessingRecord(t).record
	valid.Partition, valid.Offset = 2, 10
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &processingKafkaClientStub{fetches: processingFetches(poison, valid), cancel: cancel}
	repository := &processingRecoveryRepositoryStub{
		claims: []persistence.ClaimResult{{Duplicate: true}},
		telemetry: persistence.ProcessingRecoveryTelemetry{
			PendingJobs: 1, OldestActiveJobAge: 2 * time.Minute,
			MaximumActiveAttemptInCycle: 2,
		},
	}
	observer := &processingRecoveryObserverStub{}
	consumer := &Consumer{
		repository: repository, client: client,
		logger:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		observer: observer, sleep: sleepProcessingConsumer,
	}

	if err := consumer.Run(ctx); err != nil {
		t.Fatalf("Run poison followed by valid: %v", err)
	}
	if repository.invalidCalls != 1 || repository.claimCalls != 1 || repository.completeCalls != 0 {
		t.Fatalf("invalid=%d claims=%d complete=%d", repository.invalidCalls,
			repository.claimCalls, repository.completeCalls)
	}
	if len(repository.invalidMessages) != 1 {
		t.Fatalf("invalid messages = %d", len(repository.invalidMessages))
	}
	sanitized := repository.invalidMessages[0]
	if strings.Contains(sanitized.BodySHA256, "secret") || sanitized.EventID == uuid.Nil ||
		sanitized.AggregateID != sanitized.EventID {
		t.Fatalf("poison message was not sanitized: %#v", sanitized)
	}
	if !equalOffsets(client.committed, []int64{9, 10}) {
		t.Fatalf("committed offsets = %v, want [9 10]", client.committed)
	}
	if consumer.handledOffsets[2] != 10 || consumer.committedOffsets[2] != 10 {
		t.Fatalf("offset telemetry handled=%d committed=%d",
			consumer.handledOffsets[2], consumer.committedOffsets[2])
	}
	if len(observer.snapshots) != 1 || observer.snapshots[0].BreakerState != "CLOSED" ||
		len(observer.snapshots[0].Partitions) != 1 ||
		observer.snapshots[0].Partitions[0].Partition != 2 ||
		!observer.snapshots[0].Partitions[0].HasCommittedOffset ||
		observer.snapshots[0].Partitions[0].LastCommittedOffset != 9 {
		t.Fatalf("recovery telemetry = %#v", observer.snapshots)
	}
}

func TestProcessingCommitRetryHasPerAttemptDeadlineAndBound(t *testing.T) {
	client := &deadlineCommitClient{}
	consumer := &Consumer{
		client: client, logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		commitLimit: 2, commitTimeout: 10 * time.Millisecond,
		sleep: func(context.Context, time.Duration) error { return nil },
	}
	consumer.prepare()
	started := time.Now()
	err := consumer.commitUntilAcknowledged(context.Background(), &kgo.Record{Partition: 3, Offset: 41})
	if err == nil || client.attempts != 2 || time.Since(started) > 250*time.Millisecond {
		t.Fatalf("deadline commit error=%v attempts=%d elapsed=%s", err, client.attempts, time.Since(started))
	}
	if consumer.commitFailures[3] != 2 {
		t.Fatalf("commit failure telemetry = %d, want 2", consumer.commitFailures[3])
	}

	canceledContext, cancel := context.WithCancel(context.Background())
	cancel()
	client.attempts = 0
	if err := consumer.commitUntilAcknowledged(canceledContext,
		&kgo.Record{Partition: 3, Offset: 42}); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled commit error = %v", err)
	}
	if client.attempts != 1 {
		t.Fatalf("canceled commit attempts = %d, want 1", client.attempts)
	}
}

func TestProcessingConsumerBoundsPersistenceFailuresAndReleasesRebalance(t *testing.T) {
	first := newProcessingRecord(t).record
	first.Partition, first.Offset = 0, 30
	second := newProcessingRecord(t).record
	second.Partition, second.Offset = 0, 31
	client := &processingKafkaClientStub{fetches: processingFetches(first, second), cancel: func() {}}
	handled := 0
	consumer := &Consumer{
		client: client, logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		handleRecord: func(context.Context, *kgo.Record) error {
			handled++
			return errors.New("database unavailable")
		},
		sleep: func(context.Context, time.Duration) error { return nil },
	}

	if err := consumer.Run(context.Background()); err == nil ||
		!strings.Contains(err.Error(), "persistence retry exhausted") {
		t.Fatalf("Run bounded persistence failure error = %v", err)
	}
	if handled != processingPersistenceAttemptLimit {
		t.Fatalf("handled attempts = %d, want %d", handled, processingPersistenceAttemptLimit)
	}
	if len(client.committed) != 0 || client.allowRebalanceCalls == 0 {
		t.Fatalf("commits=%v allowRebalance=%d", client.committed, client.allowRebalanceCalls)
	}
}

func TestProcessingConsumerBrokerOutageRedeliversDBSuccessAfterRestart(t *testing.T) {
	record := newProcessingRecord(t).record
	record.Partition, record.Offset = 1, 10
	durableApplied := false
	effects, handlerCalls := 0, 0
	handle := func(context.Context, *kgo.Record) error {
		handlerCalls++
		if !durableApplied {
			durableApplied = true
			effects++
		}
		return nil
	}
	firstClient := &processingKafkaClientStub{
		fetches: processingFetches(record), commitFailures: map[int64]int{10: 10},
		cancel: func() {},
	}
	firstObserver := &processingRecoveryObserverStub{}
	first := &Consumer{
		repository: &processingRecoveryRepositoryStub{}, client: firstClient, handleRecord: handle,
		logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		sleep:  func(context.Context, time.Duration) error { return nil }, observer: firstObserver,
	}
	if err := first.Run(context.Background()); err == nil ||
		!strings.Contains(err.Error(), "offset commit retry exhausted") {
		t.Fatalf("first broker-outage run error = %v", err)
	}
	if effects != 1 || handlerCalls != 1 || len(firstClient.committed) != 0 ||
		firstClient.allowRebalanceCalls == 0 {
		t.Fatalf("first run effects=%d calls=%d commits=%v rebalance=%d",
			effects, handlerCalls, firstClient.committed, firstClient.allowRebalanceCalls)
	}
	if len(firstObserver.snapshots) != 1 || len(firstObserver.snapshots[0].Partitions) != 1 ||
		firstObserver.snapshots[0].Partitions[0].HasCommittedOffset ||
		firstObserver.snapshots[0].Partitions[0].LastHandledOffset != 10 ||
		firstObserver.snapshots[0].Partitions[0].CommitFailures != processingCommitAttemptLimit {
		t.Fatalf("commit-outage telemetry = %#v", firstObserver.snapshots)
	}

	secondContext, cancel := context.WithCancel(context.Background())
	defer cancel()
	secondClient := &processingKafkaClientStub{
		fetches: processingFetches(record), cancel: cancel,
	}
	second := &Consumer{
		client: secondClient, handleRecord: handle,
		logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	if err := second.Run(secondContext); err != nil {
		t.Fatalf("restart redelivery run: %v", err)
	}
	if effects != 1 || handlerCalls != 2 || !equalOffsets(secondClient.committed, []int64{10}) {
		t.Fatalf("restart effects=%d calls=%d commits=%v",
			effects, handlerCalls, secondClient.committed)
	}
}

func equalDurations(actual, wanted []time.Duration) bool {
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

// processingRecoveryRepositoryStub is the narrow durable consumer boundary
// used by recovery unit tests.
type processingRecoveryRepositoryStub struct {
	claims          []persistence.ClaimResult
	claimCalls      int
	invalidCalls    int
	invalidMessages []persistence.ProcessingMessage
	failureCodes    []string
	exhaustionCalls int
	completeCalls   int
	sleeps          []time.Duration
	telemetry       persistence.ProcessingRecoveryTelemetry
}

func (stub *processingRecoveryRepositoryStub) ClaimProcessingJob(
	context.Context,
	persistence.ProcessingMessage,
	string,
	time.Duration,
) (persistence.ClaimResult, error) {
	if stub.claimCalls >= len(stub.claims) {
		return persistence.ClaimResult{}, errors.New("unexpected processing claim")
	}
	claim := stub.claims[stub.claimCalls]
	stub.claimCalls++
	return claim, nil
}

func (*processingRecoveryRepositoryStub) ResolveProcessingClaimConflict(
	context.Context,
	persistence.ProcessingMessage,
) (persistence.ProcessingConflictResolution, error) {
	return persistence.ProcessingConflictResolution{}, errors.New("unexpected conflict resolution")
}

func (stub *processingRecoveryRepositoryStub) CompleteProcessingJob(
	context.Context,
	persistence.WorkerJob,
	[]media.ProcessedVariant,
) error {
	stub.completeCalls++
	return nil
}

func (stub *processingRecoveryRepositoryStub) RecordProcessingFailure(
	_ context.Context,
	job persistence.WorkerJob,
	failureCode string,
) (time.Duration, bool, error) {
	stub.failureCodes = append(stub.failureCodes, failureCode)
	if failureCode == "VALIDATION_FAILED" || job.AttemptInCycle >= 4 {
		return 0, true, nil
	}
	return time.Second * time.Duration(1<<(job.AttemptInCycle-1)), false, nil
}

func (stub *processingRecoveryRepositoryStub) RecordProcessingAttemptExhaustion(
	context.Context,
	persistence.WorkerJob,
) error {
	stub.exhaustionCalls++
	return nil
}

func (stub *processingRecoveryRepositoryStub) RecordInvalidProcessingMessage(
	_ context.Context,
	message persistence.ProcessingMessage,
	_ string,
) error {
	stub.invalidCalls++
	stub.invalidMessages = append(stub.invalidMessages, message)
	return nil
}

func (stub *processingRecoveryRepositoryStub) ProcessingRecoveryTelemetry(
	context.Context,
) (persistence.ProcessingRecoveryTelemetry, error) {
	return stub.telemetry, nil
}

func (*processingRecoveryRepositoryStub) ReleaseProcessingLeases(context.Context, string) error {
	return nil
}

// processingRecoveryObserverStub retains typed observations without external
// identifiers for unit assertions.
type processingRecoveryObserverStub struct {
	snapshots []ProcessingConsumerTelemetry
}

func (stub *processingRecoveryObserverStub) Observe(snapshot ProcessingConsumerTelemetry) {
	stub.snapshots = append(stub.snapshots, snapshot)
}

// deadlineCommitClient blocks each commit until its attempt context expires.
type deadlineCommitClient struct {
	attempts int
}

func (*deadlineCommitClient) PollFetches(context.Context) kgo.Fetches { return nil }

func (client *deadlineCommitClient) CommitRecords(ctx context.Context, _ ...*kgo.Record) error {
	client.attempts++
	<-ctx.Done()
	return ctx.Err()
}

func (*deadlineCommitClient) AllowRebalance() {}

func (*deadlineCommitClient) Close() {}
