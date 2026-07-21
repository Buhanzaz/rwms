package eventing

import (
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
	"sync"
	"testing"
	"time"

	mediamigration "dev.buhanzaz.rwms/media-service/db/migration"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"dev.buhanzaz.rwms/media-service/internal/worker"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/kmsg"
)

const residualKafkaSeedProduceAttempts = 4

var residualKafkaSeedRetryDelays = [...]time.Duration{
	100 * time.Millisecond,
	250 * time.Millisecond,
	500 * time.Millisecond,
}

func TestInventoryOwnerResidualKafkaOrderedDuplicateDLTRestartAndOutageReal(t *testing.T) {
	environment := testsupport.RequireRealEnvironment(t, testsupport.PostgreSQL, testsupport.Kafka)
	fixture := newResidualKafkaFixture(t, environment)
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	inputProducer, err := NewProducer(environment.KafkaBrokers)
	if err != nil {
		t.Fatalf("create isolated input producer: %v", err)
	}
	defer inputProducer.Close()

	consumer := startResidualOwnerConsumer(t, ctx, environment.KafkaBrokers,
		fixture.group, fixture.inputTopic, fixture.repository, worker.InventoryOwnerConsumerOptions{
			SourceTopic: fixture.inputTopic,
		})
	ownerID, warehouseID := uuid.New(), uuid.New()
	marker := residualKafkaRecord(t, fixture.inputTopic, ownerID, warehouseID, uuid.New(), 0,
		"inventory.finding.added.v1", 0, true)
	proof := residualKafkaRecord(t, fixture.inputTopic, ownerID, warehouseID, uuid.New(), 1,
		persistence.InventoryOwnerProofEvent, 0, true)
	duplicateProof := residualKafkaRecordCopy(proof)
	for index, record := range []*kgo.Record{marker, proof, duplicateProof} {
		produceResidualKafkaSeed(t, ctx, inputProducer, record,
			fmt.Sprintf("ordered/duplicate inventory record %d", index))
	}
	waitResidualCheckpoint(t, ctx, fixture.pool, ownerID, 1)
	assertResidualKafkaInbox(t, ctx, fixture.pool, ownerID, 2, 2)

	consumer.stop(t)
	inspection := residualKafkaRecord(t, fixture.inputTopic, ownerID, warehouseID, uuid.New(), 2,
		"inventory.finding.inspection-saved.v1", 0, true)
	produceResidualKafkaSeed(t, ctx, inputProducer, inspection,
		"record while owner consumer is stopped")
	restarted := startResidualOwnerConsumer(t, ctx, environment.KafkaBrokers,
		fixture.group, fixture.inputTopic, fixture.repository, worker.InventoryOwnerConsumerOptions{
			SourceTopic: fixture.inputTopic,
		})
	waitResidualCheckpoint(t, ctx, fixture.pool, ownerID, 2)
	assertResidualKafkaInbox(t, ctx, fixture.pool, ownerID, 3, 3)

	relayProducer, err := NewProducer(environment.KafkaBrokers)
	if err != nil {
		t.Fatal(err)
	}
	prepareResidualProducer(t, ctx, relayProducer, fixture.dltTopic)
	relay := NewRelayForIsolatedTest(fixture.repository, relayProducer,
		"task1b-relay-"+uuid.NewString(), residualLogger(), map[string]string{
			persistence.InventoryOwnerDLTTopic: fixture.dltTopic,
		})
	relayContext, stopRelay := context.WithCancel(ctx)
	relayDone := make(chan error, 1)
	go func() { relayDone <- relay.Run(relayContext) }()
	dltObserver := newResidualKafkaObserver(t, ctx, environment.KafkaBrokers,
		fixture.dltTopic, testsupport.UniqueKafkaName("task1b-dlt-observer"))
	defer dltObserver.close()

	invalidBody := []byte(`{"secret":"operator@example.test","payload":"must-not-leak"}`)
	produceResidualKafkaSeed(t, ctx, inputProducer, &kgo.Record{
		Topic: fixture.inputTopic, Key: []byte("not-a-canonical-uuid"), Value: invalidBody,
	}, "invalid inventory owner record")
	dltRecord := dltObserver.next(t, ctx)
	assertResidualInvalidDLT(t, invalidBody, dltRecord)
	invalidSum := sha256.Sum256(invalidBody)
	invalidRecordKey := uuid.NewSHA1(uuid.NameSpaceOID,
		[]byte(hex.EncodeToString(invalidSum[:])))
	waitResidualDLTAck(t, ctx, fixture.pool, invalidRecordKey)

	stopRelay()
	waitResidualRuntime(t, relayDone, "isolated DLT relay")
	closeContext, closeCancel := context.WithTimeout(context.Background(), 5*time.Second)
	if err := relay.Close(closeContext); err != nil {
		closeCancel()
		t.Fatalf("close isolated DLT relay: %v", err)
	}
	closeCancel()

	// Seed a second exact sanitized DLT while the broker-facing relay is down.
	outageBody := []byte("task1b-kafka-outage")
	outageSum := sha256.Sum256(outageBody)
	outageHash := hex.EncodeToString(outageSum[:])
	outageKey := uuid.NewSHA1(uuid.NameSpaceOID, []byte(outageHash))
	if err := fixture.repository.RecordInventoryOwnerDLT(ctx, persistence.InventoryOwnerDLTMessage{
		BodySHA256: outageHash, RecordKey: outageKey,
		FailureCode: "INVALID_INVENTORY_OWNER_FACT", AttemptCount: 0,
	}); err != nil {
		t.Fatalf("seed Kafka-outage DLT: %v", err)
	}
	claim, err := fixture.repository.ClaimOutbox(ctx, "task1b-broker-outage", 10*time.Second)
	if err != nil || claim == nil {
		t.Fatalf("claim Kafka-outage DLT = %#v, %v", claim, err)
	}
	unavailableProducer, err := NewProducer([]string{"127.0.0.1:1"})
	if err != nil {
		t.Fatal(err)
	}
	unavailableRelay := NewRelayForIsolatedTest(fixture.repository, unavailableProducer,
		"task1b-unavailable-relay", residualLogger(), map[string]string{
			persistence.InventoryOwnerDLTTopic: fixture.dltTopic,
		})
	outageContext, outageCancel := context.WithTimeout(ctx, 2*time.Second)
	publishErr := unavailableRelay.publish(outageContext, *claim)
	outageCancel()
	unavailableProducer.Close()
	if publishErr == nil {
		t.Fatal("publish through unavailable Kafka broker succeeded")
	}
	if err := fixture.repository.MarkOutboxFailed(ctx, *claim, "BROKER_UNAVAILABLE"); err != nil {
		t.Fatalf("reschedule Kafka-outage DLT: %v", err)
	}
	if _, err := fixture.pool.Exec(ctx, `update media_transport_outbox
		set next_attempt_at=clock_timestamp()-interval '1 second'
		where event_id=$1 and event_status='PENDING'`, claim.EventID); err != nil {
		t.Fatalf("make Kafka-outage DLT retry due: %v", err)
	}
	recoveryProducer, err := NewProducer(environment.KafkaBrokers)
	if err != nil {
		t.Fatal(err)
	}
	prepareResidualProducer(t, ctx, recoveryProducer, fixture.dltTopic)
	recoveryRelay := NewRelayForIsolatedTest(fixture.repository, recoveryProducer,
		"task1b-recovery-relay", residualLogger(), map[string]string{
			persistence.InventoryOwnerDLTTopic: fixture.dltTopic,
		})
	recoveryContext, stopRecovery := context.WithCancel(ctx)
	recoveryDone := make(chan error, 1)
	go func() { recoveryDone <- recoveryRelay.Run(recoveryContext) }()
	recoveryRelay.Wake()
	recoveredRecord := dltObserver.next(t, ctx)
	if string(recoveredRecord.Key) != outageKey.String() {
		t.Fatalf("recovered DLT key = %q, want %s", recoveredRecord.Key, outageKey)
	}
	waitResidualOutboxStatus(t, ctx, fixture.pool, claim.EventID, "PUBLISHED")
	stopRecovery()
	waitResidualRuntime(t, recoveryDone, "Kafka recovery relay")
	recoveryCloseContext, recoveryCloseCancel := context.WithTimeout(context.Background(), 5*time.Second)
	if err := recoveryRelay.Close(recoveryCloseContext); err != nil {
		recoveryCloseCancel()
		t.Fatalf("close Kafka recovery relay: %v", err)
	}
	recoveryCloseCancel()
	restarted.stop(t)
}

func TestInventoryOwnerResidualPostgresUnavailableRetriesUncommittedThenRecoversReal(t *testing.T) {
	environment := testsupport.RequireRealEnvironment(t, testsupport.PostgreSQL, testsupport.Kafka)
	fixture := newResidualKafkaFixture(t, environment)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	auditPool, err := pgxpool.New(ctx, fixture.databaseURL)
	if err != nil {
		t.Fatalf("open independent audit pool: %v", err)
	}
	defer auditPool.Close()
	closedRepository := fixture.repository
	fixture.pool.Close()
	switching := &residualSwitchingOwnerPersistence{repository: closedRepository}
	firstClient, err := worker.NewInventoryOwnerKafkaConsumerForIsolatedTest(
		environment.KafkaBrokers, fixture.group, fixture.inputTopic)
	if err != nil {
		t.Fatalf("create PostgreSQL-outage owner consumer: %v", err)
	}
	firstAttemptStopped := errors.New("stop after uncommitted PostgreSQL-outage attempt")
	firstConsumer := worker.NewInventoryOwnerConsumerWithOptions(switching, firstClient,
		residualLogger(), worker.InventoryOwnerConsumerOptions{
			SourceTopic: fixture.inputTopic,
			RetryDelays: []time.Duration{time.Second, 2 * time.Second, 4 * time.Second},
			Sleep: func(sleepContext context.Context, _ time.Duration) error {
				if inspectionErr := residualNoOwnerMutation(sleepContext, auditPool); inspectionErr != nil {
					return inspectionErr
				}
				if offsetErr := residualNoCommittedOwnerOffset(firstClient, fixture.inputTopic); offsetErr != nil {
					return offsetErr
				}
				return firstAttemptStopped
			},
		})
	firstConsumerDone := make(chan error, 1)
	go func() { firstConsumerDone <- firstConsumer.Run(ctx) }()
	producer, err := NewProducer(environment.KafkaBrokers)
	if err != nil {
		t.Fatal(err)
	}
	defer producer.Close()
	ownerID, warehouseID, eventID := uuid.New(), uuid.New(), uuid.New()
	produceResidualKafkaSeed(t, ctx, producer, residualKafkaRecord(t, fixture.inputTopic,
		ownerID, warehouseID, eventID, 0, "inventory.finding.added.v1", 0, true),
		"PostgreSQL-outage record")
	select {
	case runErr := <-firstConsumerDone:
		if !errors.Is(runErr, firstAttemptStopped) {
			t.Fatalf("first PostgreSQL-outage consumer error = %v, want restart sentinel", runErr)
		}
	case <-ctx.Done():
		t.Fatalf("wait for first uncommitted PostgreSQL attempt: %v", ctx.Err())
	}
	if offsetErr := residualNoCommittedOwnerOffset(firstClient, fixture.inputTopic); offsetErr != nil {
		t.Fatal(offsetErr)
	}
	firstConsumer.Close()

	client, err := worker.NewInventoryOwnerKafkaConsumerForIsolatedTest(
		environment.KafkaBrokers, fixture.group, fixture.inputTopic)
	if err != nil {
		t.Fatalf("restart PostgreSQL-outage owner consumer in same group: %v", err)
	}
	delays := make(chan time.Duration, 3)
	recoveredPools := make(chan *pgxpool.Pool, 1)
	consumer := worker.NewInventoryOwnerConsumerWithOptions(switching, client, residualLogger(),
		worker.InventoryOwnerConsumerOptions{
			SourceTopic: fixture.inputTopic,
			RetryDelays: []time.Duration{time.Second, 2 * time.Second, 4 * time.Second},
			Sleep: func(sleepContext context.Context, delay time.Duration) error {
				delays <- delay
				if inspectionErr := residualNoOwnerMutation(sleepContext, auditPool); inspectionErr != nil {
					return inspectionErr
				}
				if offsetErr := residualNoCommittedOwnerOffset(client, fixture.inputTopic); offsetErr != nil {
					return offsetErr
				}
				timer := time.NewTimer(delay)
				defer timer.Stop()
				select {
				case <-sleepContext.Done():
					return sleepContext.Err()
				case <-timer.C:
				}
				if delay == 4*time.Second {
					recoveredPool, openErr := pgxpool.New(sleepContext, fixture.databaseURL)
					if openErr != nil {
						return openErr
					}
					switching.setRepository(persistence.NewRepository(recoveredPool))
					recoveredPools <- recoveredPool
				}
				return nil
			},
		})
	consumerContext, stopConsumer := context.WithCancel(ctx)
	consumerDone := make(chan error, 1)
	go func() { consumerDone <- consumer.Run(consumerContext) }()
	for index, expected := range []time.Duration{time.Second, 2 * time.Second, 4 * time.Second} {
		select {
		case actual := <-delays:
			if actual != expected {
				t.Fatalf("retry delay %d = %v, want %v", index+1, actual, expected)
			}
		case <-ctx.Done():
			t.Fatalf("wait for retry delay %d: %v", index+1, ctx.Err())
		}
	}
	var recoveredPool *pgxpool.Pool
	select {
	case recoveredPool = <-recoveredPools:
	case <-ctx.Done():
		t.Fatalf("PostgreSQL pool was not reopened after bounded retries: %v", ctx.Err())
	}
	defer recoveredPool.Close()
	waitResidualCheckpoint(t, ctx, recoveredPool, ownerID, 0)
	var recoveredEventID uuid.UUID
	if err := recoveredPool.QueryRow(ctx, `select event_id from media_inventory_finding_inbox
		where consumer_name=$1 and aggregate_id=$2 and aggregate_version=0 and outcome='APPLIED'`,
		persistence.InventoryOwnerConsumerGroup, ownerID).Scan(&recoveredEventID); err != nil {
		t.Fatalf("read same-group recovered inventory event: %v", err)
	}
	if recoveredEventID != eventID {
		t.Fatalf("same-group restart applied event %s, want original uncommitted %s",
			recoveredEventID, eventID)
	}
	stopConsumer()
	waitResidualRuntime(t, consumerDone, "PostgreSQL recovery owner consumer")
	consumer.Close()
}

type residualKafkaFixture struct {
	databaseURL string
	pool        *pgxpool.Pool
	repository  *persistence.Repository
	inputTopic  string
	dltTopic    string
	group       string
}

func newResidualKafkaFixture(t *testing.T,
	environment testsupport.RealEnvironment) residualKafkaFixture {
	t.Helper()
	databaseURL := testsupport.NewIsolatedPostgresDatabase(t, environment.DatabaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open isolated Kafka PostgreSQL database: %v", err)
	}
	t.Cleanup(pool.Close)
	if _, err := pool.Exec(ctx, `create extension if not exists pgcrypto`); err != nil {
		t.Fatalf("enable isolated Kafka pgcrypto: %v", err)
	}
	for _, migration := range [][]byte{
		mediamigration.V1,
		mediamigration.V2,
		mediamigration.V3,
		mediamigration.V4,
		mediamigration.V4_1,
		mediamigration.V5,
		mediamigration.V5_1,
	} {
		if _, err := pool.Exec(ctx, string(migration)); err != nil {
			t.Fatalf("apply isolated Kafka migration: %v", err)
		}
	}
	fixture := residualKafkaFixture{
		databaseURL: databaseURL, pool: pool, repository: persistence.NewRepository(pool),
		inputTopic: testsupport.UniqueKafkaName("task1b-inventory-input"),
		dltTopic:   testsupport.UniqueKafkaName("task1b-inventory-dlt"),
		group:      testsupport.UniqueKafkaName("task1b-inventory-owner-group"),
	}
	admin, err := NewProducer(environment.KafkaBrokers)
	if err != nil {
		t.Fatalf("create Kafka topic admin: %v", err)
	}
	createResidualTopics(t, ctx, admin, fixture.inputTopic, fixture.dltTopic)
	t.Cleanup(func() {
		if !residualKafkaResourceIsUnique(fixture.inputTopic) ||
			!residualKafkaResourceIsUnique(fixture.dltTopic) {
			t.Errorf("refusing to delete non-unique Task 1B Kafka topics %q and %q",
				fixture.inputTopic, fixture.dltTopic)
			admin.Close()
			return
		}
		cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cleanupCancel()
		request := kmsg.NewPtrDeleteTopicsRequest()
		request.TopicNames = []string{fixture.inputTopic, fixture.dltTopic}
		request.TimeoutMillis = int32((10 * time.Second) / time.Millisecond)
		if _, cleanupErr := request.RequestWith(cleanupContext, admin); cleanupErr != nil {
			t.Errorf("delete isolated Task 1B Kafka topics: %v", cleanupErr)
		}
		admin.Close()
	})
	return fixture
}

func createResidualTopics(t testing.TB, ctx context.Context, client *kgo.Client, topics ...string) {
	t.Helper()
	for _, topic := range topics {
		if !residualKafkaResourceIsUnique(topic) {
			t.Fatalf("refusing non-unique Task 1B Kafka topic %q", topic)
		}
	}
	request := kmsg.NewPtrCreateTopicsRequest()
	request.TimeoutMillis = int32((10 * time.Second) / time.Millisecond)
	for _, topic := range topics {
		request.Topics = append(request.Topics, kmsg.CreateTopicsRequestTopic{
			Topic: topic, NumPartitions: 3, ReplicationFactor: 1,
		})
	}
	response, err := request.RequestWith(ctx, client)
	if err != nil {
		t.Fatalf("create isolated Task 1B Kafka topics: %v", err)
	}
	for _, topic := range response.Topics {
		if topic.ErrorCode != 0 {
			t.Fatalf("create isolated topic %s: code=%d message=%v",
				topic.Topic, topic.ErrorCode, topic.ErrorMessage)
		}
	}
	waitKafkaTopicsReady(t, ctx, client, topics)
	assertResidualTopicPartitions(t, ctx, client, topics, 3)
}

func assertResidualTopicPartitions(t testing.TB, ctx context.Context, client *kgo.Client,
	topics []string, expected int) {
	t.Helper()
	request := kmsg.NewPtrMetadataRequest()
	request.AllowAutoTopicCreation = false
	for _, topic := range topics {
		topic := topic
		request.Topics = append(request.Topics, kmsg.MetadataRequestTopic{Topic: &topic})
	}
	response, err := request.RequestWith(ctx, client)
	if err != nil {
		t.Fatalf("read isolated Task 1B Kafka metadata: %v", err)
	}
	if len(response.Topics) != len(topics) {
		t.Fatalf("isolated Task 1B Kafka metadata topics = %d, want %d",
			len(response.Topics), len(topics))
	}
	for _, topic := range response.Topics {
		if topic.Topic == nil || !residualKafkaResourceIsUnique(*topic.Topic) ||
			topic.ErrorCode != 0 || len(topic.Partitions) != expected {
			t.Fatalf("isolated Kafka topic metadata = name:%v code:%d partitions:%d, want unique/0/%d",
				topic.Topic, topic.ErrorCode, len(topic.Partitions), expected)
		}
		for _, partition := range topic.Partitions {
			if partition.ErrorCode != 0 || partition.Leader < 0 || len(partition.ISR) == 0 {
				t.Fatalf("isolated Kafka partition %d is not leader-ready: code=%d leader=%d isr=%v",
					partition.Partition, partition.ErrorCode, partition.Leader, partition.ISR)
			}
		}
	}
}

func residualKafkaResourceIsUnique(name string) bool {
	return strings.HasPrefix(name, "task1b-") &&
		name != persistence.InventorySessionTopic && name != persistence.InventoryOwnerDLTTopic
}

func prepareResidualProducer(t testing.TB, ctx context.Context, client *kgo.Client,
	topics ...string) {
	t.Helper()
	if err := client.Ping(ctx); err != nil {
		t.Fatalf("ping residual Kafka producer: %s", residualKafkaClientError(err))
	}
	waitKafkaTopicsReady(t, ctx, client, topics)
	assertResidualTopicPartitions(t, ctx, client, topics, 3)
	client.ForceMetadataRefresh()
}

func produceResidualKafkaSeed(t testing.TB, ctx context.Context, client *kgo.Client,
	record *kgo.Record, description string) {
	t.Helper()
	if record == nil || record.Topic == "" {
		t.Fatalf("produce %s: residual Kafka seed record has no topic", description)
	}

	failures := make([]string, 0, residualKafkaSeedProduceAttempts)
	for attempt := 1; attempt <= residualKafkaSeedProduceAttempts; attempt++ {
		readinessErr := residualKafkaSeedProducerReady(ctx, client, record.Topic)
		if readinessErr != nil {
			failures = append(failures, fmt.Sprintf("attempt %d readiness: %s",
				attempt, residualKafkaClientError(readinessErr)))
			if !residualKafkaSeedErrorIsRetriable(readinessErr) ||
				attempt == residualKafkaSeedProduceAttempts {
				t.Fatalf("produce %s failed after %d/%d bounded attempts: %s",
					description, attempt, residualKafkaSeedProduceAttempts,
					strings.Join(failures, "; "))
			}
			waitResidualKafkaSeedRetry(t, ctx, residualKafkaSeedRetryDelays[attempt-1],
				description, failures)
			continue
		}

		produceErr := client.ProduceSync(ctx, residualKafkaRecordCopy(record)).FirstErr()
		if produceErr == nil {
			return
		}
		failures = append(failures, fmt.Sprintf("attempt %d produce: %s",
			attempt, residualKafkaClientError(produceErr)))
		if !residualKafkaSeedErrorIsRetriable(produceErr) ||
			attempt == residualKafkaSeedProduceAttempts {
			t.Fatalf("produce %s failed after %d/%d bounded attempts: %s",
				description, attempt, residualKafkaSeedProduceAttempts,
				strings.Join(failures, "; "))
		}
		client.ForceMetadataRefresh()
		waitResidualKafkaSeedRetry(t, ctx, residualKafkaSeedRetryDelays[attempt-1],
			description, failures)
	}
}

func residualKafkaSeedProducerReady(ctx context.Context, client *kgo.Client, topic string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	client.ForceMetadataRefresh()
	if err := client.Ping(ctx); err != nil {
		return fmt.Errorf("ping producer: %w", err)
	}
	request := kmsg.NewPtrMetadataRequest()
	request.AllowAutoTopicCreation = false
	request.Topics = append(request.Topics, kmsg.MetadataRequestTopic{Topic: &topic})
	response, err := request.RequestWith(ctx, client)
	if err != nil {
		return fmt.Errorf("read producer topic metadata: %w", err)
	}
	if len(response.Topics) != 1 {
		return fmt.Errorf("producer metadata topics = %d, want 1: %w",
			len(response.Topics), kerr.UnknownTopicOrPartition)
	}
	topicMetadata := response.Topics[0]
	if topicMetadata.ErrorCode != 0 {
		return fmt.Errorf("producer topic metadata code %d: %w", topicMetadata.ErrorCode,
			kerr.ErrorForCode(topicMetadata.ErrorCode))
	}
	if topicMetadata.Topic == nil || *topicMetadata.Topic != topic {
		return fmt.Errorf("producer metadata returned unexpected topic")
	}
	if len(topicMetadata.Partitions) == 0 {
		return fmt.Errorf("producer topic has no partitions: %w", kerr.LeaderNotAvailable)
	}
	if len(topicMetadata.Partitions) != 3 {
		return fmt.Errorf("producer topic partitions = %d, want 3", len(topicMetadata.Partitions))
	}
	for _, partition := range topicMetadata.Partitions {
		if partition.ErrorCode != 0 {
			return fmt.Errorf("producer partition %d metadata code %d: %w",
				partition.Partition, partition.ErrorCode, kerr.ErrorForCode(partition.ErrorCode))
		}
		if partition.Leader < 0 {
			return fmt.Errorf("producer partition %d has no leader: %w",
				partition.Partition, kerr.LeaderNotAvailable)
		}
		if len(partition.ISR) == 0 {
			return fmt.Errorf("producer partition %d has no in-sync replica: %w",
				partition.Partition, kerr.ReplicaNotAvailable)
		}
	}
	client.ForceMetadataRefresh()
	return nil
}

func residualKafkaSeedErrorIsRetriable(err error) bool {
	if err == nil || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return false
	}
	if errors.Is(err, kgo.ErrRecordRetries) || errors.Is(err, kgo.ErrRecordTimeout) ||
		kerr.IsRetriable(err) {
		return true
	}
	var networkErr net.Error
	return errors.As(err, &networkErr) && (networkErr.Timeout() || networkErr.Temporary())
}

func waitResidualKafkaSeedRetry(t testing.TB, ctx context.Context, delay time.Duration,
	description string, failures []string) {
	t.Helper()
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		t.Fatalf("produce %s interrupted before bounded retry: %s; context: %s",
			description, strings.Join(failures, "; "), residualKafkaClientError(ctx.Err()))
	case <-timer.C:
	}
}

func residualKafkaRecordCopy(source *kgo.Record) *kgo.Record {
	copyRecord := &kgo.Record{
		Topic: source.Topic,
		Key:   append([]byte(nil), source.Key...),
		Value: append([]byte(nil), source.Value...),
	}
	for _, header := range source.Headers {
		copyRecord.Headers = append(copyRecord.Headers, kgo.RecordHeader{
			Key: header.Key, Value: append([]byte(nil), header.Value...),
		})
	}
	return copyRecord
}

func residualKafkaClientError(err error) string {
	if err == nil {
		return ""
	}
	parts := make([]string, 0, 4)
	for depth := 0; err != nil && depth < 4; depth++ {
		parts = append(parts, fmt.Sprintf("%T: %v", err, err))
		err = errors.Unwrap(err)
	}
	return strings.Join(parts, " -> ")
}

type residualRunningConsumer struct {
	consumer *worker.InventoryOwnerConsumer
	cancel   context.CancelFunc
	done     chan error
}

func startResidualOwnerConsumer(t testing.TB, parent context.Context, brokers []string,
	group, topic string, repository *persistence.Repository,
	options worker.InventoryOwnerConsumerOptions) residualRunningConsumer {
	t.Helper()
	client, err := worker.NewInventoryOwnerKafkaConsumerForIsolatedTest(brokers, group, topic)
	if err != nil {
		t.Fatalf("create isolated inventory owner consumer: %v", err)
	}
	consumer := worker.NewInventoryOwnerConsumerWithOptions(repository, client, residualLogger(), options)
	ctx, cancel := context.WithCancel(parent)
	done := make(chan error, 1)
	go func() { done <- consumer.Run(ctx) }()
	return residualRunningConsumer{consumer: consumer, cancel: cancel, done: done}
}

func (running residualRunningConsumer) stop(t testing.TB) {
	t.Helper()
	running.cancel()
	waitResidualRuntime(t, running.done, "inventory owner consumer")
	running.consumer.Close()
}

func residualKafkaRecord(t testing.TB, physicalTopic string, ownerID, warehouseID,
	eventID uuid.UUID, version int64, eventType string, ownerRevision int64, active bool) *kgo.Record {
	t.Helper()
	payload := map[string]any{
		"ownerType": persistence.OwnerTypeInventoryFinding, "ownerId": ownerID,
		"warehouseId": warehouseID, "ownerRevision": ownerRevision, "active": active,
	}
	if eventType != persistence.InventoryOwnerProofEvent {
		payload = map[string]any{
			"inventoryId": uuid.New(), "findingId": ownerID, "warehouseId": warehouseID,
			"sessionRevision": 0, "findingRevision": version, "origin": "EXPECTED",
			"inspection": "NOT_INSPECTED", "reconciliation": "MISSING", "assetId": nil,
			"sourceAttached": false, "mediaCount": 0, "planFingerprintSha256": nil,
		}
	}
	body, err := json.Marshal(map[string]any{
		"envelopeVersion": 2, "eventId": eventID, "eventType": eventType, "eventVersion": 1,
		"occurredAt": nil, "recordedAt": time.Now().UTC(), "producer": "inventory-service",
		"aggregateType": persistence.InventoryFindingAggregate, "aggregateId": ownerID,
		"aggregateVersion": version,
		"correlation":      map[string]any{"correlationId": uuid.New(), "causationId": nil},
		"actorRef":         nil, "payload": payload,
	})
	if err != nil {
		t.Fatal(err)
	}
	return &kgo.Record{Topic: physicalTopic, Key: []byte(ownerID.String()), Value: body}
}

type residualKafkaObserver struct {
	client *kgo.Client
}

func newResidualKafkaObserver(t testing.TB, ctx context.Context, brokers []string,
	topic, group string) *residualKafkaObserver {
	t.Helper()
	client, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topic), kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.DisableAutoCommit())
	if err != nil {
		t.Fatalf("create residual Kafka observer: %v", err)
	}
	if err := client.Ping(ctx); err != nil {
		client.Close()
		t.Fatalf("ping residual Kafka observer: %v", err)
	}
	return &residualKafkaObserver{client: client}
}

func (observer *residualKafkaObserver) next(t testing.TB, ctx context.Context) *kgo.Record {
	t.Helper()
	for {
		fetches := observer.client.PollRecords(ctx, 1)
		if errs := fetches.Errors(); len(errs) != 0 {
			t.Fatalf("poll residual Kafka observer: %v", errs[0].Err)
		}
		if records := fetches.Records(); len(records) != 0 {
			return records[0]
		}
		if ctx.Err() != nil {
			t.Fatalf("wait for residual Kafka record: %v", ctx.Err())
		}
	}
}

func (observer *residualKafkaObserver) close() { observer.client.Close() }

func assertResidualInvalidDLT(t testing.TB, raw []byte, record *kgo.Record) {
	t.Helper()
	sum := sha256.Sum256(raw)
	hash := hex.EncodeToString(sum[:])
	expectedKey := uuid.NewSHA1(uuid.NameSpaceOID, []byte(hash)).String()
	if string(record.Key) != expectedKey {
		t.Fatalf("invalid DLT key = %q, want exact %s", record.Key, expectedKey)
	}
	var body map[string]json.RawMessage
	if err := json.Unmarshal(record.Value, &body); err != nil {
		t.Fatalf("decode invalid DLT body: %v", err)
	}
	if len(body) != 3 || body["failureCode"] == nil || body["messageSha256"] == nil ||
		body["recordedAt"] == nil {
		t.Fatalf("invalid DLT exact fields = %v", body)
	}
	var failureCode, messageHash string
	if err := json.Unmarshal(body["failureCode"], &failureCode); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(body["messageSha256"], &messageHash); err != nil {
		t.Fatal(err)
	}
	if failureCode != "INVALID_INVENTORY_OWNER_FACT" || messageHash != hash ||
		strings.Contains(string(record.Value), "operator@example.test") ||
		strings.Contains(string(record.Value), "must-not-leak") {
		t.Fatalf("invalid DLT is not exact/sanitized: %s", record.Value)
	}
}

func waitResidualCheckpoint(t testing.TB, ctx context.Context, pool *pgxpool.Pool,
	aggregateID uuid.UUID, version int64) {
	t.Helper()
	waitResidualDatabase(t, ctx, func() (bool, error) {
		var actual int64
		err := pool.QueryRow(ctx, `select aggregate_version from media_consumer_aggregate_checkpoint
			where consumer_name=$1 and aggregate_type='FINDING' and aggregate_id=$2`,
			persistence.InventoryOwnerConsumerGroup, aggregateID).Scan(&actual)
		return err == nil && actual == version, err
	}, "checkpoint")
}

func assertResidualKafkaInbox(t testing.TB, ctx context.Context, pool *pgxpool.Pool,
	aggregateID uuid.UUID, total, applied int) {
	t.Helper()
	var actualTotal, actualApplied int
	if err := pool.QueryRow(ctx, `select count(*),count(*) filter (where outcome='APPLIED')
		from media_inventory_finding_inbox where consumer_name=$1 and aggregate_id=$2`,
		persistence.InventoryOwnerConsumerGroup, aggregateID).Scan(&actualTotal, &actualApplied); err != nil {
		t.Fatal(err)
	}
	if actualTotal != total || actualApplied != applied {
		t.Fatalf("Kafka inbox = total:%d applied:%d, want %d/%d",
			actualTotal, actualApplied, total, applied)
	}
}

func waitResidualDLTAck(t testing.TB, ctx context.Context, pool *pgxpool.Pool,
	recordKey uuid.UUID) {
	t.Helper()
	waitResidualDatabase(t, ctx, func() (bool, error) {
		var count int
		err := pool.QueryRow(ctx, `select count(*) from media_transport_outbox
			where aggregate_type='INVENTORY_OWNER_DLT' and record_key=$1
			  and event_status='PUBLISHED' and published_at is not null`, recordKey).Scan(&count)
		return err == nil && count > 0, err
	}, "broker-acknowledged invalid DLT")
}

func waitResidualOutboxStatus(t testing.TB, ctx context.Context, pool *pgxpool.Pool,
	eventID uuid.UUID, expected string) {
	t.Helper()
	waitResidualDatabase(t, ctx, func() (bool, error) {
		var actual string
		err := pool.QueryRow(ctx, `select event_status from media_transport_outbox where event_id=$1`,
			eventID).Scan(&actual)
		return err == nil && actual == expected, err
	}, "outbox status "+expected)
}

func waitResidualDatabase(t testing.TB, ctx context.Context,
	check func() (bool, error), description string) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		matched, err := check()
		if matched {
			return
		}
		lastErr = err
		select {
		case <-ctx.Done():
			t.Fatalf("wait for %s: %v", description, ctx.Err())
		case <-time.After(25 * time.Millisecond):
		}
	}
	t.Fatalf("wait for %s timed out: %v", description, lastErr)
}

func waitResidualRuntime(t testing.TB, done <-chan error, name string) {
	t.Helper()
	select {
	case err := <-done:
		if err != nil && !errors.Is(err, context.Canceled) {
			t.Fatalf("%s stopped with error: %v", name, err)
		}
	case <-time.After(10 * time.Second):
		t.Fatalf("%s did not stop", name)
	}
}

func residualLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

type residualSwitchingOwnerPersistence struct {
	mu         sync.RWMutex
	repository *persistence.Repository
}

func (store *residualSwitchingOwnerPersistence) setRepository(repository *persistence.Repository) {
	store.mu.Lock()
	defer store.mu.Unlock()
	store.repository = repository
}

func (store *residualSwitchingOwnerPersistence) current() *persistence.Repository {
	store.mu.RLock()
	defer store.mu.RUnlock()
	return store.repository
}

func (store *residualSwitchingOwnerPersistence) ApplyInventoryFindingMessage(ctx context.Context,
	message persistence.InventoryFindingMessage) (persistence.InventoryFindingApplyResult, error) {
	return store.current().ApplyInventoryFindingMessage(ctx, message)
}

func (store *residualSwitchingOwnerPersistence) RecordInventoryOwnerDLT(ctx context.Context,
	message persistence.InventoryOwnerDLTMessage) error {
	return store.current().RecordInventoryOwnerDLT(ctx, message)
}

func (store *residualSwitchingOwnerPersistence) QuarantineInventoryOwnerProcessingFailure(
	ctx context.Context, message persistence.InventoryFindingMessage, attemptCount int,
) error {
	return store.current().QuarantineInventoryOwnerProcessingFailure(ctx, message, attemptCount)
}

func (store *residualSwitchingOwnerPersistence) ScheduleInventoryOwnerRetry(ctx context.Context,
	message persistence.InventoryFindingMessage, attempt int, delay time.Duration) error {
	return store.current().ScheduleInventoryOwnerRetry(ctx, message, attempt, delay)
}

func (store *residualSwitchingOwnerPersistence) InventoryOwnerRetryState(ctx context.Context,
	eventID uuid.UUID, bodySHA256 string) (persistence.InventoryOwnerRetryState, bool, error) {
	return store.current().InventoryOwnerRetryState(ctx, eventID, bodySHA256)
}

func (store *residualSwitchingOwnerPersistence) QuarantineInventoryOwnerRetryIdentityConflict(
	ctx context.Context, message persistence.InventoryFindingMessage,
) error {
	return store.current().QuarantineInventoryOwnerRetryIdentityConflict(ctx, message)
}

func (store *residualSwitchingOwnerPersistence) ClearInventoryOwnerRetry(ctx context.Context,
	eventID uuid.UUID) error {
	return store.current().ClearInventoryOwnerRetry(ctx, eventID)
}

func residualNoOwnerMutation(ctx context.Context, pool *pgxpool.Pool) error {
	var inbox, checkpoints int
	if err := pool.QueryRow(ctx, `select
		(select count(*) from media_inventory_finding_inbox),
		(select count(*) from media_consumer_aggregate_checkpoint
		 where consumer_name=$1 and aggregate_type='FINDING')`,
		persistence.InventoryOwnerConsumerGroup).Scan(&inbox, &checkpoints); err != nil {
		return fmt.Errorf("inspect PostgreSQL-outage mutations: %w", err)
	}
	if inbox != 0 || checkpoints != 0 {
		return fmt.Errorf("PostgreSQL-outage attempt mutated state: inbox=%d checkpoints=%d",
			inbox, checkpoints)
	}
	return nil
}

func residualNoCommittedOwnerOffset(client *kgo.Client, topic string) error {
	for _, offset := range client.CommittedOffsets()[topic] {
		if offset.Offset > 0 {
			return fmt.Errorf("unfinished PostgreSQL-outage record committed offset %d", offset.Offset)
		}
	}
	return nil
}
