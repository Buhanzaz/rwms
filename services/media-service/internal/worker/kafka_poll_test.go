package worker

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/twmb/franz-go/pkg/kgo"
)

func TestProcessingConsumerRecoversAfterInterruptedPoll(t *testing.T) {
	record := newProcessingRecord(t).record
	client, ctx, cancel := newInterruptedPollClient(record)
	defer cancel()
	consumer := &Consumer{
		client:      client,
		logger:      discardedWorkerLogger(),
		pollTimeout: 10 * time.Millisecond,
		handleRecord: func(_ context.Context, received *kgo.Record) error {
			if received != record {
				t.Fatal("processing consumer handled an unexpected record")
			}
			return nil
		},
	}

	if err := consumer.Run(ctx); err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	assertInterruptedPollRecovery(t, client, record)
}

func TestInventoryOwnerConsumerRecoversAfterInterruptedPoll(t *testing.T) {
	record := newInventoryRecord(t, persistence.InventoryOwnerProofEvent, 1, true).record
	client, ctx, cancel := newInterruptedPollClient(record)
	defer cancel()
	consumer := &InventoryOwnerConsumer{
		repository:  &inventoryOwnerPersistenceStub{applyErrors: []error{nil}},
		client:      client,
		logger:      discardedWorkerLogger(),
		sourceTopic: persistence.InventorySessionTopic,
		delays:      []time.Duration{time.Millisecond},
		sleep:       waitInventoryRetry,
		pollTimeout: 10 * time.Millisecond,
	}

	if err := consumer.Run(ctx); err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	assertInterruptedPollRecovery(t, client, record)
}

func TestCabinOwnerConsumerRecoversAfterInterruptedPoll(t *testing.T) {
	record := newCabinOwnerRecord(t, "asset.rental-item.created.v1", 0).record
	client, ctx, cancel := newInterruptedPollClient(record)
	defer cancel()
	consumer := &CabinOwnerConsumer{
		repository:  &cabinOwnerPersistenceStub{},
		client:      client,
		logger:      discardedWorkerLogger(),
		sourceTopic: persistence.AssetRentalItemTopic,
		pollTimeout: 10 * time.Millisecond,
	}

	if err := consumer.Run(ctx); err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	assertInterruptedPollRecovery(t, client, record)
}

func TestTaskBoardOwnerProofConsumerRecoversAfterInterruptedPoll(t *testing.T) {
	record := newTaskBoardOwnerProofRecord(t, 0).record
	client, ctx, cancel := newInterruptedPollClient(record)
	defer cancel()
	consumer := &TaskBoardEntryOwnerProofConsumer{
		repository:  &taskBoardOwnerProofPersistenceStub{},
		client:      client,
		logger:      discardedWorkerLogger(),
		sourceTopic: persistence.TaskBoardEntryOwnerProofTopic,
		pollTimeout: 10 * time.Millisecond,
	}

	if err := consumer.Run(ctx); err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	assertInterruptedPollRecovery(t, client, record)
}

func assertInterruptedPollRecovery(t *testing.T, client *interruptedPollClient, record *kgo.Record) {
	t.Helper()
	if client.pollCount() < 3 {
		t.Fatalf("polls = %d, want stalled poll, broker error, then record retry", client.pollCount())
	}
	if client.allowCount() < 3 {
		t.Fatalf("AllowRebalance calls = %d, want release after every interrupted poll", client.allowCount())
	}
	committed := client.committedRecords()
	if len(committed) != 1 || committed[0] != record {
		t.Fatalf("committed records = %v, want only the recovered record", committed)
	}
}

func discardedWorkerLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

type interruptedPollClient struct {
	mu        sync.Mutex
	record    *kgo.Record
	polls     int
	allows    int
	committed []*kgo.Record
	cancel    context.CancelFunc
}

func newInterruptedPollClient(record *kgo.Record) (*interruptedPollClient, context.Context, context.CancelFunc) {
	ctx, cancel := context.WithCancel(context.Background())
	return &interruptedPollClient{record: record, cancel: cancel}, ctx, cancel
}

func (client *interruptedPollClient) PollFetches(ctx context.Context) kgo.Fetches {
	client.mu.Lock()
	poll := client.polls
	client.polls++
	record := client.record
	client.mu.Unlock()

	switch poll {
	case 0:
		<-ctx.Done()
		return kgo.NewErrFetch(ctx.Err())
	case 1:
		return kgo.NewErrFetch(errors.New("broker temporarily unavailable"))
	case 2:
		return kgo.Fetches{{Topics: []kgo.FetchTopic{{
			Topic: record.Topic,
			Partitions: []kgo.FetchPartition{{
				Partition: record.Partition,
				Records:   []*kgo.Record{record},
			}},
		}}}}
	default:
		<-ctx.Done()
		return kgo.NewErrFetch(ctx.Err())
	}
}

func (client *interruptedPollClient) CommitRecords(_ context.Context, records ...*kgo.Record) error {
	client.mu.Lock()
	client.committed = append(client.committed, records...)
	client.mu.Unlock()
	client.cancel()
	return nil
}

func (client *interruptedPollClient) AllowRebalance() {
	client.mu.Lock()
	client.allows++
	client.mu.Unlock()
}

func (*interruptedPollClient) Close() {}

func (client *interruptedPollClient) pollCount() int {
	client.mu.Lock()
	defer client.mu.Unlock()
	return client.polls
}

func (client *interruptedPollClient) allowCount() int {
	client.mu.Lock()
	defer client.mu.Unlock()
	return client.allows
}

func (client *interruptedPollClient) committedRecords() []*kgo.Record {
	client.mu.Lock()
	defer client.mu.Unlock()
	return append([]*kgo.Record(nil), client.committed...)
}
