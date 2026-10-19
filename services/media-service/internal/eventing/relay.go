// Package eventing relays exact persisted media facts from the transactional
// outbox to Kafka without making Kafka the source of truth.
package eventing

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"log/slog"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/twmb/franz-go/pkg/kgo"
)

var errPermanentValidation = errors.New("permanent outbox validation failure")

// Relay claims, validates, and publishes durable media outbox records.
type Relay struct {
	repository     *persistence.Repository
	producer       *kgo.Client
	owner          string
	logger         *slog.Logger
	wake           chan struct{}
	topicOverrides map[string]string
}

// NewRelay creates the production outbox relay for one lease owner.
func NewRelay(repository *persistence.Repository, producer *kgo.Client, owner string, logger *slog.Logger) *Relay {
	return &Relay{repository: repository, producer: producer, owner: owner, logger: logger, wake: make(chan struct{}, 1)}
}

// NewRelayForIsolatedTest maps canonical outbox topics to unique physical test
// topics without mutating the persisted transport contract.
func NewRelayForIsolatedTest(
	repository *persistence.Repository,
	producer *kgo.Client,
	owner string,
	logger *slog.Logger,
	topicOverrides map[string]string,
) *Relay {
	overrides := make(map[string]string, len(topicOverrides))
	for canonical, physical := range topicOverrides {
		if canonical != "" && physical != "" && canonical != physical {
			overrides[canonical] = physical
		}
	}
	return &Relay{repository: repository, producer: producer, owner: owner, logger: logger,
		wake: make(chan struct{}, 1), topicOverrides: overrides}
}

// NewProducer creates the Kafka producer with all-ISR acknowledgement and no
// client-side retries, leaving durable retry ownership to the outbox.
func NewProducer(brokers []string) (*kgo.Client, error) {
	return kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.RequiredAcks(kgo.AllISRAcks()),
		kgo.RecordRetries(0),
		kgo.RecordDeliveryTimeout(10*time.Second),
		kgo.ProducerBatchCompression(kgo.ZstdCompression()),
	)
}

// Wake asks the relay to check the outbox immediately without queuing
// unbounded wake-up signals.
func (relay *Relay) Wake() {
	select {
	case relay.wake <- struct{}{}:
	default:
	}
}

// Run continuously relays claimed outbox facts until ctx is canceled.
func (relay *Relay) Run(ctx context.Context) error {
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		case <-relay.wake:
		}
		for {
			claim, err := relay.repository.ClaimOutbox(ctx, relay.owner, 30*time.Second)
			if err != nil {
				if ctx.Err() != nil {
					return nil
				}
				relay.logger.Error("claim media outbox", "errorType", errorType(err))
				break
			}
			if claim == nil {
				break
			}
			if err := relay.publish(ctx, *claim); err != nil {
				mark := relay.repository.MarkOutboxFailed
				code := "BROKER_UNAVAILABLE"
				if errors.Is(err, errPermanentValidation) {
					mark = func(ctx context.Context, claim persistence.OutboxClaim, code string) error {
						return relay.repository.MarkOutboxPermanent(ctx, claim, code)
					}
					code = "CHECKSUM_MISMATCH"
				}
				if markErr := mark(ctx, *claim, code); markErr != nil && !errors.Is(markErr, persistence.ErrLeaseLost) {
					relay.logger.Error("reschedule media outbox", "eventId", claim.EventID, "errorType", errorType(markErr))
				}
				break
			}
			if err := relay.repository.MarkOutboxPublished(ctx, *claim); err != nil {
				if !errors.Is(err, persistence.ErrLeaseLost) {
					relay.logger.Error("ack media outbox", "eventId", claim.EventID, "errorType", errorType(err))
				}
				break
			}
		}
	}
}

func (relay *Relay) publish(ctx context.Context, claim persistence.OutboxClaim) error {
	sum := sha256.Sum256(claim.Body)
	if hex.EncodeToString(sum[:]) != claim.BodySHA256 {
		return errPermanentValidation
	}
	topic := claim.Topic
	if override := relay.topicOverrides[topic]; override != "" {
		topic = override
	}
	record := &kgo.Record{Topic: topic, Key: []byte(claim.RecordKey.String()), Value: claim.Body}
	return relay.producer.ProduceSync(ctx, record).FirstErr()
}

// Close flushes and closes the producer, then releases this relay's outbox
// leases for safe recovery by another instance.
func (relay *Relay) Close(ctx context.Context) error {
	relay.producer.Flush(ctx)
	relay.producer.Close()
	return relay.repository.ReleaseOutboxLeases(ctx, relay.owner)
}

func errorType(err error) string {
	if err == nil {
		return ""
	}
	return "DEPENDENCY_ERROR"
}
