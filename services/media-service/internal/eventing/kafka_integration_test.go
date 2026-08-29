package eventing

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"dev.buhanzaz.rwms/media-service/internal/worker"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/kmsg"
)

func TestKafkaRelayAckBeforeDatabaseMarkIsReconciledByProcessingConsumerIntegration(t *testing.T) {
	databaseURL, brokers := kafkaIntegrationEnvironment(t)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := persistence.Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media ACK-race database: %v", err)
	}
	defer database.Close()
	repository := persistence.NewRepository(database.Pool)

	producer, err := NewProducer(brokers)
	if err != nil {
		t.Fatalf("create ACK-race producer: %v", err)
	}
	defer producer.Close()
	if err := producer.Ping(ctx); err != nil {
		t.Fatalf("ping ACK-race Kafka: %v", err)
	}
	ensureKafkaTopics(t, ctx, producer, []string{persistence.ProcessingTopic})

	assigned := make(chan struct{})
	var assignedOnce sync.Once
	consumerClient, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup("media-ack-race-"+uuid.NewString()),
		kgo.ConsumeTopics(persistence.ProcessingTopic),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
		kgo.FetchMaxBytes(2<<20),
		kgo.FetchMaxPartitionBytes(1<<20),
		kgo.OnPartitionsAssigned(func(context.Context, *kgo.Client, map[string][]int32) {
			assignedOnce.Do(func() { close(assigned) })
		}),
	)
	if err != nil {
		t.Fatalf("create ACK-race consumer: %v", err)
	}

	mediaID := uuid.New()
	jobID := uuid.New()
	warehouseID := uuid.New()
	ownerID := uuid.New()
	uploadedEventID := uuid.New()
	requestEventID := uuid.New()
	correlationID := uuid.New()
	recordedAt := time.Now().UTC()
	sourceVersionID := "immutable-kafka-integration-version"
	sourceChecksum := strings.Repeat("a", 64)
	sourceKey := "media/" + mediaID.String() + "/source/upload.jpg"
	seedKafkaProcessingState(t, ctx, database.Pool, mediaID, jobID, warehouseID, ownerID,
		sourceKey, sourceVersionID, sourceChecksum, recordedAt)
	uploadedBody := kafkaFactEnvelope(t, uploadedEventID, mediaID, 1, "media.media.uploaded.v1",
		media.StatusProcessing, ownerID, warehouseID, correlationID, recordedAt)
	requestBody := kafkaProcessingEnvelope(t, requestEventID, jobID, mediaID, warehouseID,
		correlationID, recordedAt.Add(time.Millisecond))
	insertKafkaOutboxRecord(t, ctx, database.Pool, kafkaOutboxSeed{
		EventID: uploadedEventID, AggregateType: "MEDIA", AggregateID: mediaID,
		AggregateVersion: 1, EventType: "media.media.uploaded.v1", Topic: persistence.MediaTopic,
		RecordKey: mediaID, Ordinal: 1, Body: uploadedBody, RecordedAt: recordedAt,
	})
	if _, err := database.Pool.Exec(ctx, `update media_transport_outbox
		set event_status='PUBLISHED',published_at=clock_timestamp()
		where event_id=$1`, uploadedEventID); err != nil {
		t.Fatalf("publish ACK-race dependency seed: %v", err)
	}
	insertKafkaOutboxRecord(t, ctx, database.Pool, kafkaOutboxSeed{
		EventID: requestEventID, AggregateType: "PROCESSING_JOB", AggregateID: jobID,
		AggregateVersion: 1, EventType: "media.processing.request.v1", Topic: persistence.ProcessingTopic,
		RecordKey: jobID, Ordinal: 1, DependsOn: &uploadedEventID,
		Body: requestBody, RecordedAt: recordedAt.Add(time.Millisecond),
	})

	processor := worker.Processor{}
	processingConsumer := worker.NewConsumer(repository, consumerClient, processor,
		"ack-race-worker-"+uuid.NewString(), 10*time.Second, integrationLogger())
	consumerContext, stopConsumer := context.WithCancel(ctx)
	consumerDone := make(chan error, 1)
	go func() { consumerDone <- processingConsumer.Run(consumerContext) }()
	select {
	case <-assigned:
	case err := <-consumerDone:
		t.Fatalf("processing consumer exited before assignment: %v", err)
	case <-time.After(15 * time.Second):
		t.Fatal("processing consumer did not receive a partition assignment")
	}

	relay := NewRelay(repository, producer, "ack-race-relay-"+uuid.NewString(), integrationLogger())
	claim, err := repository.ClaimOutbox(ctx, relay.owner, 30*time.Second)
	if err != nil || claim == nil || claim.EventID != requestEventID {
		t.Fatalf("claim ACK-race processing request = %#v, %v", claim, err)
	}
	mismatched := persistence.ProcessingMessage{
		EventID: requestEventID, BodySHA256: strings.Repeat("b", 64),
		Topic: persistence.ProcessingTopic, EventType: "media.processing.request.v1",
		AggregateType: "PROCESSING_JOB", AggregateID: jobID, AggregateVersion: 1, RecordKey: jobID,
		CorrelationID: correlationID, ExpectedMediaID: mediaID, ExpectedWarehouseID: warehouseID,
		ExpectedKind: media.KindImage, ExpectedProcessingKind: media.ProcessingInitial,
		ExpectedGeneration: 1, ExpectedRotation: media.Rotation0, ExpectedSourceVersionID: sourceVersionID,
	}
	if _, mismatchErr := repository.ClaimProcessingJob(ctx, mismatched, "foreign-ack-race-worker", time.Minute); !errors.Is(mismatchErr, persistence.ErrConflict) {
		t.Fatalf("mismatched PUBLISHING record claim = %v, want ErrConflict", mismatchErr)
	}
	if err := relay.publish(ctx, *claim); err != nil {
		t.Fatalf("broker ACK for processing request: %v", err)
	}
	// Intentionally do not call MarkOutboxPublished. The consumer must observe
	// the exact broker-acknowledged record while the DB row is still PUBLISHING.
	waitForKafkaRaceCompletion(t, ctx, database.Pool, requestEventID, jobID)
	if err := repository.MarkOutboxPublished(ctx, *claim); !errors.Is(err, persistence.ErrLeaseLost) {
		t.Fatalf("stale relay mark after consumer reconciliation = %v, want ErrLeaseLost", err)
	}
	select {
	case err := <-consumerDone:
		t.Fatalf("processing consumer exited after reconciling ACK race: %v", err)
	default:
	}

	stopConsumer()
	select {
	case err := <-consumerDone:
		if err != nil {
			t.Fatalf("processing consumer shutdown: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("processing consumer did not stop")
	}
	closeContext, closeCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer closeCancel()
	if err := processingConsumer.Close(closeContext); err != nil {
		t.Fatalf("close processing consumer: %v", err)
	}
}

func seedKafkaProcessingState(
	t *testing.T,
	ctx context.Context,
	pool *pgxpool.Pool,
	mediaID, jobID, warehouseID, ownerID uuid.UUID,
	sourceKey, sourceVersionID, sourceChecksum string,
	recordedAt time.Time,
) {
	t.Helper()
	_, err := pool.Exec(ctx, `insert into media_asset (
		media_id,folder_id,owner_type,owner_id,warehouse_id,media_kind,original_file_name,
		original_content_type,source_object_key,processing_status,rotation_degrees,
		current_generation,pending_generation,pending_rotation_degrees,sort_order,size_bytes,
		version,created_at,updated_at,next_generation,source_version_id,source_etag,
		source_checksum_sha256,finalized_content_type,finalized_size_bytes)
	values ($1,$1,'INVENTORY_FINDING',$2,$3,'IMAGE','ack-race.jpg','image/jpeg',$4,
		'PROCESSING',0,0,1,0,0,1024,1,$5,$5,2,$6,'ack-race-etag',$7,'image/jpeg',1024)`,
		mediaID, ownerID.String(), warehouseID, sourceKey, recordedAt, sourceVersionID, sourceChecksum)
	if err != nil {
		t.Fatalf("seed ACK-race media asset: %v", err)
	}
	_, err = pool.Exec(ctx, `insert into media_upload_session (
		upload_session_id,media_id,principal_type,subject_id,idempotency_key,
		expected_content_length,expected_content_type,expected_checksum_sha256,
		upload_mode,expires_at,completed_at,created_at)
	values ($1,$2,'USER',$3,$4,1024,'image/jpeg',$5,'SOURCE',$6,$7,$7)`,
		uuid.New(), mediaID, uuid.New(), uuid.New(), sourceChecksum,
		recordedAt.Add(time.Hour), recordedAt)
	if err != nil {
		t.Fatalf("seed ACK-race upload session: %v", err)
	}
	_, err = pool.Exec(ctx, `insert into media_processing_job (
		processing_job_id,media_id,generation,processing_kind,requested_rotation_degrees,
		job_status,source_version_id,source_checksum_sha256,next_attempt_at)
	values ($1,$2,1,'INITIAL',0,'PENDING',$3,$4,$5)`,
		jobID, mediaID, sourceVersionID, sourceChecksum, recordedAt)
	if err != nil {
		t.Fatalf("seed ACK-race processing job: %v", err)
	}
	_, err = pool.Exec(ctx, `insert into media_event_stream_head (
		aggregate_type,aggregate_id,stream_version,updated_at)
	values ('MEDIA',$1,1,$2)`, mediaID, recordedAt)
	if err != nil {
		t.Fatalf("seed ACK-race event stream: %v", err)
	}
}

func waitForKafkaRaceCompletion(
	t *testing.T,
	ctx context.Context,
	pool *pgxpool.Pool,
	eventID, jobID uuid.UUID,
) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for {
		var outboxStatus, jobStatus, inboxOutcome string
		var variantCount int
		err := pool.QueryRow(ctx, `select request.event_status,job.job_status,
			coalesce((select inbox.outcome from media_processing_inbox inbox
				where inbox.consumer_name='media-processing-v1' and inbox.event_id=request.event_id),''),
			(select count(*) from media_variant variant where variant.media_id=job.media_id and variant.generation=job.generation)
		from media_transport_outbox request
		join media_processing_job job on job.processing_job_id=$2
		where request.event_id=$1`, eventID, jobID).Scan(
			&outboxStatus, &jobStatus, &inboxOutcome, &variantCount)
		if err != nil {
			t.Fatalf("read ACK-race completion: %v", err)
		}
		if outboxStatus == "PUBLISHED" && jobStatus == "COMPLETED" && inboxOutcome == "APPLIED" && variantCount == 4 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("ACK-race state = outbox:%s job:%s inbox:%s variants:%d",
				outboxStatus, jobStatus, inboxOutcome, variantCount)
		}
		select {
		case <-ctx.Done():
			t.Fatalf("wait for ACK-race completion: %v", ctx.Err())
		case <-time.After(25 * time.Millisecond):
		}
	}
}

type kafkaRaceObject struct {
	body        []byte
	versionID   string
	contentType string
}

type kafkaRaceObjectStore struct {
	mu      sync.Mutex
	objects map[string]kafkaRaceObject
}

func newKafkaRaceObjectStore(key, versionID string, body []byte) *kafkaRaceObjectStore {
	return &kafkaRaceObjectStore{objects: map[string]kafkaRaceObject{
		key: {body: append([]byte(nil), body...), versionID: versionID, contentType: "image/jpeg"},
	}}
}

func (store *kafkaRaceObjectStore) GetVersion(_ context.Context, key, versionID string) (io.ReadCloser, media.ObjectMetadata, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	object, exists := store.objects[key]
	if !exists || object.versionID != versionID {
		return nil, media.ObjectMetadata{}, fmt.Errorf("missing pinned ACK-race object %q version %q", key, versionID)
	}
	body := append([]byte(nil), object.body...)
	return io.NopCloser(bytes.NewReader(body)), media.ObjectMetadata{
		SizeBytes: int64(len(body)), ContentType: object.contentType, VersionID: object.versionID,
	}, nil
}

func (store *kafkaRaceObjectStore) PutVersion(_ context.Context, key string, source io.Reader, sizeBytes int64, contentType string) (media.ObjectMetadata, error) {
	body, err := io.ReadAll(source)
	if err != nil {
		return media.ObjectMetadata{}, err
	}
	if int64(len(body)) != sizeBytes {
		return media.ObjectMetadata{}, fmt.Errorf("ACK-race object %q size = %d, want %d", key, len(body), sizeBytes)
	}
	versionID := uuid.NewString()
	store.mu.Lock()
	store.objects[key] = kafkaRaceObject{body: append([]byte(nil), body...), versionID: versionID, contentType: contentType}
	store.mu.Unlock()
	return media.ObjectMetadata{SizeBytes: sizeBytes, ContentType: contentType, VersionID: versionID}, nil
}

func kafkaRaceJPEG(t *testing.T, width, height int) []byte {
	t.Helper()
	canvas := image.NewRGBA(image.Rect(0, 0, width, height))
	for x := 0; x < width; x++ {
		for y := 0; y < height; y++ {
			canvas.Set(x, y, color.RGBA{R: uint8(x), G: uint8(y), B: 180, A: 255})
		}
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, canvas, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatalf("encode ACK-race JPEG: %v", err)
	}
	return output.Bytes()
}

func TestKafkaRelayExactBytesKeyOrderDependencyDLTAndDuplicateObservationIntegration(t *testing.T) {
	databaseURL, brokers := kafkaIntegrationEnvironment(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	database, err := persistence.Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media integration database: %v", err)
	}
	defer database.Close()
	repository := persistence.NewRepository(database.Pool)

	producer, err := NewProducer(brokers)
	if err != nil {
		t.Fatalf("create production Kafka producer: %v", err)
	}
	if err := producer.Ping(ctx); err != nil {
		producer.Close()
		t.Fatalf("ping Kafka: %v", err)
	}
	topics := []string{persistence.MediaTopic, persistence.ProcessingTopic, persistence.ProcessingDLTTopic}
	ensureKafkaTopics(t, ctx, producer, topics)
	observer := startKafkaObserver(t, brokers, topics)
	defer observer.Close()

	mediaID := uuid.New()
	processingJobID := uuid.New()
	warehouseID := uuid.New()
	ownerID := uuid.New()
	uploadedEventID := uuid.New()
	requestEventID := uuid.New()
	readyEventID := uuid.New()
	correlationID := uuid.New()
	baseTime := time.Now().UTC()
	uploadedBody := kafkaFactEnvelope(
		t,
		uploadedEventID,
		mediaID,
		1,
		"media.media.uploaded.v1",
		media.StatusProcessing,
		ownerID,
		warehouseID,
		correlationID,
		baseTime,
	)
	requestBody := kafkaProcessingEnvelope(
		t,
		requestEventID,
		processingJobID,
		mediaID,
		warehouseID,
		correlationID,
		baseTime.Add(time.Millisecond),
	)
	readyBody := kafkaFactEnvelope(
		t,
		readyEventID,
		mediaID,
		2,
		"media.media.ready.v1",
		media.StatusReady,
		ownerID,
		warehouseID,
		correlationID,
		baseTime.Add(2*time.Millisecond),
	)
	insertKafkaOutboxRecord(t, ctx, database.Pool, kafkaOutboxSeed{
		EventID: uploadedEventID, AggregateType: "MEDIA", AggregateID: mediaID,
		AggregateVersion: 1, EventType: "media.media.uploaded.v1", Topic: persistence.MediaTopic,
		RecordKey: mediaID, Ordinal: 1, Body: uploadedBody, RecordedAt: baseTime,
	})
	insertKafkaOutboxRecord(t, ctx, database.Pool, kafkaOutboxSeed{
		EventID: requestEventID, AggregateType: "PROCESSING_JOB", AggregateID: processingJobID,
		AggregateVersion: 1, EventType: "media.processing.request.v1", Topic: persistence.ProcessingTopic,
		RecordKey: processingJobID, Ordinal: 1, DependsOn: &uploadedEventID,
		Body: requestBody, RecordedAt: baseTime.Add(time.Millisecond),
	})
	insertKafkaOutboxRecord(t, ctx, database.Pool, kafkaOutboxSeed{
		EventID: readyEventID, AggregateType: "MEDIA", AggregateID: mediaID,
		AggregateVersion: 2, EventType: "media.media.ready.v1", Topic: persistence.MediaTopic,
		RecordKey: mediaID, Ordinal: 2, Body: readyBody, RecordedAt: baseTime.Add(2 * time.Millisecond),
	})
	preflightOwner := "kafka-dependency-preflight-" + uuid.NewString()
	preflightClaim, err := repository.ClaimOutbox(ctx, preflightOwner, time.Second)
	if err != nil || preflightClaim == nil || preflightClaim.EventID != uploadedEventID {
		t.Fatalf("first dependency-order claim = %#v, %v; want uploaded %s", preflightClaim, err, uploadedEventID)
	}
	blockedClaim, err := repository.ClaimOutbox(ctx, "kafka-dependency-blocked-"+uuid.NewString(), time.Second)
	if err != nil || blockedClaim != nil {
		t.Fatalf("claim before uploaded ACK = %#v, %v; want both READY and request blocked", blockedClaim, err)
	}
	if err := repository.ReleaseOutboxLeases(ctx, preflightOwner); err != nil {
		t.Fatalf("release dependency preflight lease: %v", err)
	}

	relayContext, stopRelay := context.WithCancel(ctx)
	relay := NewRelay(repository, producer, "kafka-integration-"+uuid.NewString(), integrationLogger())
	relayDone := make(chan error, 1)
	go func() { relayDone <- relay.Run(relayContext) }()
	defer func() {
		stopRelay()
		select {
		case err := <-relayDone:
			if err != nil {
				t.Errorf("relay shutdown: %v", err)
			}
		case <-time.After(5 * time.Second):
			t.Error("relay did not stop")
		}
		closeContext, closeCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer closeCancel()
		if err := relay.Close(closeContext); err != nil {
			t.Errorf("relay close: %v", err)
		}
	}()
	relay.Wake()

	records := observer.WaitForBodies(t, map[string]int{
		string(uploadedBody): 1,
		string(requestBody):  1,
		string(readyBody):    1,
	}, 20*time.Second)
	assertKafkaRecord(t, findKafkaRecord(t, records, uploadedBody), persistence.MediaTopic, mediaID, uploadedBody)
	assertKafkaRecord(t, findKafkaRecord(t, records, requestBody), persistence.ProcessingTopic, processingJobID, requestBody)
	assertKafkaRecord(t, findKafkaRecord(t, records, readyBody), persistence.MediaTopic, mediaID, readyBody)
	assertBodyOrder(t, records, uploadedBody, readyBody)
	assertOutboxPublished(t, ctx, database.Pool, uploadedEventID, 2)
	assertOutboxPublished(t, ctx, database.Pool, requestEventID, 1)
	assertOutboxPublished(t, ctx, database.Pool, readyEventID, 1)

	invalidMessage := persistence.ProcessingMessage{
		EventID:                 requestEventID,
		BodySHA256:              kafkaSHA256(requestBody),
		Topic:                   persistence.ProcessingTopic,
		EventType:               "media.processing.request.v1",
		AggregateType:           "PROCESSING_JOB",
		AggregateID:             processingJobID,
		AggregateVersion:        1,
		RecordKey:               processingJobID,
		CorrelationID:           correlationID,
		ExpectedMediaID:         mediaID,
		ExpectedWarehouseID:     warehouseID,
		ExpectedKind:            media.KindImage,
		ExpectedProcessingKind:  media.ProcessingInitial,
		ExpectedGeneration:      1,
		ExpectedRotation:        media.Rotation0,
		ExpectedSourceVersionID: "immutable-kafka-integration-version",
	}
	if err := repository.RecordInvalidProcessingMessage(ctx, invalidMessage, "INVALID_PROCESSING_REQUEST"); err != nil {
		t.Fatalf("create actual sanitized processing DLT outbox record: %v", err)
	}
	dltEventID := uuid.NewSHA1(
		uuid.NameSpaceOID,
		[]byte("media-processing-dlt:"+requestEventID.String()+":INVALID_PROCESSING_REQUEST"),
	)
	dltBody := readKafkaOutboxBody(t, ctx, database.Pool, dltEventID)
	relay.Wake()
	dltRecords := observer.WaitForBodies(t, map[string]int{string(dltBody): 1}, 20*time.Second)
	assertKafkaRecord(t, findKafkaRecord(t, dltRecords, dltBody), persistence.ProcessingDLTTopic, processingJobID, dltBody)
	assertOutboxPublished(t, ctx, database.Pool, dltEventID, 1)

	duplicateResult := producer.ProduceSync(ctx, &kgo.Record{
		Topic: persistence.MediaTopic,
		Key:   []byte(mediaID.String()),
		Value: append([]byte(nil), uploadedBody...),
	})
	if err := duplicateResult.FirstErr(); err != nil {
		t.Fatalf("publish at-least-once duplicate: %v", err)
	}
	duplicateRecords := observer.WaitForBodies(t, map[string]int{string(uploadedBody): 1}, 20*time.Second)
	duplicate := findKafkaRecord(t, duplicateRecords, uploadedBody)
	assertKafkaRecord(t, duplicate, persistence.MediaTopic, mediaID, uploadedBody)
	var originalEnvelope, duplicateEnvelope struct {
		EventID string `json:"eventId"`
	}
	if err := json.Unmarshal(uploadedBody, &originalEnvelope); err != nil {
		t.Fatalf("decode original event identity: %v", err)
	}
	if err := json.Unmarshal(duplicate.Value, &duplicateEnvelope); err != nil {
		t.Fatalf("decode duplicate event identity: %v", err)
	}
	if duplicateEnvelope.EventID != originalEnvelope.EventID {
		t.Fatalf("duplicate event identity = %q, want stable %q", duplicateEnvelope.EventID, originalEnvelope.EventID)
	}
}

func TestKafkaRelayMoreThanFourBrokerFailuresThenRealRecoveryIntegration(t *testing.T) {
	databaseURL, brokers := kafkaIntegrationEnvironment(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	database, err := persistence.Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media integration database: %v", err)
	}
	defer database.Close()
	repository := persistence.NewRepository(database.Pool)

	realProducer, err := NewProducer(brokers)
	if err != nil {
		t.Fatalf("create recovery Kafka producer: %v", err)
	}
	defer realProducer.Close()
	if err := realProducer.Ping(ctx); err != nil {
		t.Fatalf("ping recovery Kafka: %v", err)
	}
	ensureKafkaTopics(t, ctx, realProducer, []string{persistence.MediaTopic})
	observer := startKafkaObserver(t, brokers, []string{persistence.MediaTopic})
	defer observer.Close()

	aggregateID := uuid.New()
	eventID := uuid.New()
	body := kafkaFactEnvelope(
		t,
		eventID,
		aggregateID,
		1,
		"media.media.uploaded.v1",
		media.StatusProcessing,
		uuid.New(),
		uuid.New(),
		uuid.New(),
		time.Now().UTC(),
	)
	insertKafkaOutboxRecord(t, ctx, database.Pool, kafkaOutboxSeed{
		EventID: eventID, AggregateType: "MEDIA", AggregateID: aggregateID,
		AggregateVersion: 1, EventType: "media.media.uploaded.v1", Topic: persistence.MediaTopic,
		RecordKey: aggregateID, Ordinal: 1, Body: body, RecordedAt: time.Now().UTC(),
	})

	deadProducer, err := kgo.NewClient(
		kgo.SeedBrokers("127.0.0.1:1"),
		kgo.RequiredAcks(kgo.AllISRAcks()),
		kgo.RecordRetries(0),
		kgo.RecordDeliveryTimeout(time.Second),
		kgo.ProducerBatchCompression(kgo.ZstdCompression()),
	)
	if err != nil {
		t.Fatalf("create dead-broker producer: %v", err)
	}
	deadRelay := NewRelay(repository, deadProducer, "kafka-dead-"+uuid.NewString(), integrationLogger())
	for attempt := 1; attempt <= 5; attempt++ {
		claim, claimErr := repository.ClaimOutbox(ctx, deadRelay.owner, time.Second)
		if claimErr != nil || claim == nil || claim.EventID != eventID || claim.Attempt != attempt {
			t.Fatalf("dead-broker claim %d = %#v, %v", attempt, claim, claimErr)
		}
		publishContext, publishCancel := context.WithTimeout(ctx, time.Second)
		publishErr := deadRelay.publish(publishContext, *claim)
		publishCancel()
		if publishErr == nil {
			t.Fatalf("dead broker unexpectedly acknowledged attempt %d", attempt)
		}
		if err := repository.MarkOutboxFailed(ctx, *claim, "BROKER_UNAVAILABLE"); err != nil {
			t.Fatalf("reschedule dead-broker attempt %d: %v", attempt, err)
		}
		assertOutboxPendingAttempt(t, ctx, database.Pool, eventID, attempt)
		forceKafkaRetryDue(t, ctx, database.Pool, eventID)
	}
	deadProducer.Close()

	recoveryRelay := NewRelay(repository, realProducer, "kafka-recovery-"+uuid.NewString(), integrationLogger())
	recoveryClaim, err := repository.ClaimOutbox(ctx, recoveryRelay.owner, time.Second)
	if err != nil || recoveryClaim == nil || recoveryClaim.EventID != eventID || recoveryClaim.Attempt != 6 {
		t.Fatalf("recovery claim = %#v, %v; want event %s attempt 6", recoveryClaim, err, eventID)
	}
	if err := recoveryRelay.publish(ctx, *recoveryClaim); err != nil {
		t.Fatalf("publish after broker recovery: %v", err)
	}
	if err := repository.MarkOutboxPublished(ctx, *recoveryClaim); err != nil {
		t.Fatalf("ack recovered outbox row: %v", err)
	}
	records := observer.WaitForBodies(t, map[string]int{string(body): 1}, 20*time.Second)
	assertKafkaRecord(t, findKafkaRecord(t, records, body), persistence.MediaTopic, aggregateID, body)
	assertOutboxPublished(t, ctx, database.Pool, eventID, 6)
}

type kafkaOutboxSeed struct {
	EventID          uuid.UUID
	AggregateType    string
	AggregateID      uuid.UUID
	AggregateVersion int64
	EventType        string
	Topic            string
	RecordKey        uuid.UUID
	Ordinal          int64
	DependsOn        *uuid.UUID
	Body             []byte
	RecordedAt       time.Time
}

func insertKafkaOutboxRecord(t *testing.T, ctx context.Context, pool *pgxpool.Pool, seed kafkaOutboxSeed) {
	t.Helper()
	checksum := kafkaSHA256(seed.Body)
	_, err := pool.Exec(ctx, `insert into media_transport_outbox (
		event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,record_key,
		publication_ordinal,depends_on_event_id,envelope_body,wire_body,envelope_sha256,
		next_attempt_at,recorded_at)
	values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10::jsonb,$11,$12,clock_timestamp(),$13)`,
		seed.EventID,
		seed.AggregateType,
		seed.AggregateID,
		seed.AggregateVersion,
		seed.EventType,
		seed.Topic,
		seed.RecordKey,
		seed.Ordinal,
		seed.DependsOn,
		string(seed.Body),
		seed.Body,
		checksum,
		seed.RecordedAt,
	)
	if err != nil {
		t.Fatalf("insert isolated Kafka outbox row %s: %v", seed.EventID, err)
	}
}

func kafkaFactEnvelope(
	t *testing.T,
	eventID, aggregateID uuid.UUID,
	aggregateVersion int64,
	eventType string,
	status media.Status,
	ownerID, warehouseID, correlationID uuid.UUID,
	recordedAt time.Time,
) []byte {
	t.Helper()
	return mustKafkaJSON(t, map[string]any{
		"envelopeVersion":  2,
		"eventId":          eventID,
		"eventType":        eventType,
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       recordedAt,
		"producer":         "media-service",
		"aggregateType":    "MEDIA",
		"aggregateId":      aggregateID,
		"aggregateVersion": aggregateVersion,
		"correlation": map[string]any{
			"correlationId": correlationID,
			"causationId":   nil,
		},
		"actorRef": nil,
		"payload": map[string]any{
			"mediaId":           aggregateID,
			"ownerType":         persistence.OwnerTypeInventoryFinding,
			"ownerId":           ownerID,
			"warehouseId":       warehouseID,
			"clientReferenceId": nil,
			"kind":              media.KindImage,
			"status":            status,
			"generation":        1,
			"rotationDegrees":   media.Rotation0,
		},
	})
}

func kafkaProcessingEnvelope(
	t *testing.T,
	eventID, processingJobID, mediaID, warehouseID, correlationID uuid.UUID,
	recordedAt time.Time,
) []byte {
	t.Helper()
	return mustKafkaJSON(t, map[string]any{
		"envelopeVersion":  2,
		"eventId":          eventID,
		"eventType":        "media.processing.request.v1",
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       recordedAt,
		"producer":         "media-service",
		"aggregateType":    "PROCESSING_JOB",
		"aggregateId":      processingJobID,
		"aggregateVersion": 1,
		"correlation": map[string]any{
			"correlationId": correlationID,
			"causationId":   nil,
		},
		"actorRef": nil,
		"payload": map[string]any{
			"processingJobId": processingJobID,
			"mediaId":         mediaID,
			"warehouseId":     warehouseID,
			"kind":            media.KindImage,
			"processingKind":  media.ProcessingInitial,
			"generation":      1,
			"rotationDegrees": media.Rotation0,
			"sourceVersionId": "immutable-kafka-integration-version",
		},
	})
}

func mustKafkaJSON(t *testing.T, value any) []byte {
	t.Helper()
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal Kafka test envelope: %v", err)
	}
	return body
}

func readKafkaOutboxBody(t *testing.T, ctx context.Context, pool *pgxpool.Pool, eventID uuid.UUID) []byte {
	t.Helper()
	var body []byte
	if err := pool.QueryRow(ctx, `select wire_body from media_transport_outbox where event_id=$1`, eventID).Scan(&body); err != nil {
		t.Fatalf("read Kafka outbox body %s: %v", eventID, err)
	}
	return body
}

func assertOutboxPublished(t *testing.T, ctx context.Context, pool *pgxpool.Pool, eventID uuid.UUID, attempt int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		var status string
		var actualAttempt int
		var publishedAt *time.Time
		if err := pool.QueryRow(ctx, `select event_status,attempt_count,published_at
			from media_transport_outbox where event_id=$1`, eventID).Scan(&status, &actualAttempt, &publishedAt); err != nil {
			t.Fatalf("read published outbox row %s: %v", eventID, err)
		}
		if status == "PUBLISHED" && actualAttempt == attempt && publishedAt != nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("outbox %s = status:%s attempt:%d publishedAt:%v", eventID, status, actualAttempt, publishedAt)
		}
		select {
		case <-ctx.Done():
			t.Fatalf("wait for published outbox %s: %v", eventID, ctx.Err())
		case <-time.After(25 * time.Millisecond):
		}
	}
}

func assertOutboxPendingAttempt(t *testing.T, ctx context.Context, pool *pgxpool.Pool, eventID uuid.UUID, attempt int) {
	t.Helper()
	var status string
	var actualAttempt int
	var errorCode *string
	if err := pool.QueryRow(ctx, `select event_status,attempt_count,last_error_code
		from media_transport_outbox where event_id=$1`, eventID).Scan(&status, &actualAttempt, &errorCode); err != nil {
		t.Fatalf("read failed outbox attempt %s: %v", eventID, err)
	}
	if status != "PENDING" || actualAttempt != attempt || errorCode == nil || *errorCode != "BROKER_UNAVAILABLE" {
		t.Fatalf("outbox %s after failure = status:%s attempt:%d error:%v", eventID, status, actualAttempt, errorCode)
	}
}

func forceKafkaRetryDue(t *testing.T, ctx context.Context, pool *pgxpool.Pool, eventID uuid.UUID) {
	t.Helper()
	command, err := pool.Exec(ctx, `update media_transport_outbox
		set next_attempt_at=clock_timestamp()-interval '1 second'
		where event_id=$1 and event_status='PENDING'`, eventID)
	if err != nil || command.RowsAffected() != 1 {
		t.Fatalf("force Kafka retry due for %s: affected=%d error=%v", eventID, command.RowsAffected(), err)
	}
}

func kafkaIntegrationEnvironment(t *testing.T) (string, []string) {
	t.Helper()
	databaseURL := strings.TrimSpace(os.Getenv("MEDIA_TEST_DATABASE_URL"))
	brokerValue := strings.TrimSpace(os.Getenv("MEDIA_TEST_KAFKA_BROKERS"))
	if databaseURL == "" || brokerValue == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL and MEDIA_TEST_KAFKA_BROKERS are required")
	}
	var brokers []string
	for _, broker := range strings.Split(brokerValue, ",") {
		if broker = strings.TrimSpace(broker); broker != "" {
			brokers = append(brokers, broker)
		}
	}
	if len(brokers) == 0 {
		t.Fatal("MEDIA_TEST_KAFKA_BROKERS contains no broker addresses")
	}
	return testsupport.NewMigratedMediaDatabase(t, databaseURL), brokers
}

func ensureKafkaTopics(t *testing.T, ctx context.Context, client *kgo.Client, topics []string) {
	t.Helper()
	request := kmsg.NewPtrCreateTopicsRequest()
	request.TimeoutMillis = int32((10 * time.Second) / time.Millisecond)
	for _, topic := range topics {
		request.Topics = append(request.Topics, kmsg.CreateTopicsRequestTopic{
			Topic: topic, NumPartitions: 3, ReplicationFactor: 1,
		})
	}
	response, err := request.RequestWith(ctx, client)
	if err != nil {
		t.Fatalf("create Kafka integration topics: %v", err)
	}
	for _, topic := range response.Topics {
		if topic.ErrorCode != 0 && topic.ErrorCode != 36 {
			t.Fatalf("create Kafka topic %s: error code %d, message %v", topic.Topic, topic.ErrorCode, topic.ErrorMessage)
		}
	}
	waitKafkaTopicsReady(t, ctx, client, topics)
}

func waitKafkaTopicsReady(t testing.TB, ctx context.Context, client *kgo.Client, topics []string) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for {
		request := kmsg.NewPtrMetadataRequest()
		request.AllowAutoTopicCreation = false
		for _, topicName := range topics {
			topicName := topicName
			request.Topics = append(request.Topics, kmsg.MetadataRequestTopic{Topic: &topicName})
		}
		response, err := request.RequestWith(ctx, client)
		ready := err == nil && len(response.Topics) == len(topics)
		if ready {
			for _, topic := range response.Topics {
				if topic.ErrorCode != 0 || len(topic.Partitions) == 0 {
					ready = false
					break
				}
				for _, partition := range topic.Partitions {
					if partition.ErrorCode != 0 || partition.Leader < 0 {
						ready = false
						break
					}
				}
			}
		}
		if ready {
			client.ForceMetadataRefresh()
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("Kafka topics did not become leader-ready: %v", err)
		}
		select {
		case <-ctx.Done():
			t.Fatalf("wait for Kafka topic readiness: %v", ctx.Err())
		case <-time.After(50 * time.Millisecond):
		}
	}
}

type kafkaObserver struct {
	client  *kgo.Client
	cancel  context.CancelFunc
	records chan *kgo.Record
	errors  chan error
}

func startKafkaObserver(t *testing.T, brokers, topics []string) *kafkaObserver {
	t.Helper()
	assigned := make(chan struct{})
	var assignedOnce sync.Once
	client, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup("media-kafka-integration-"+uuid.NewString()),
		kgo.ConsumeTopics(topics...),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.DisableAutoCommit(),
		kgo.OnPartitionsAssigned(func(context.Context, *kgo.Client, map[string][]int32) {
			assignedOnce.Do(func() { close(assigned) })
		}),
	)
	if err != nil {
		t.Fatalf("create Kafka observer: %v", err)
	}
	observerContext, cancel := context.WithCancel(context.Background())
	observer := &kafkaObserver{
		client: client, cancel: cancel,
		records: make(chan *kgo.Record, 64), errors: make(chan error, 8),
	}
	go func() {
		for observerContext.Err() == nil {
			fetches := client.PollRecords(observerContext, 100)
			if observerContext.Err() != nil {
				return
			}
			for _, fetchError := range fetches.Errors() {
				select {
				case observer.errors <- fetchError.Err:
				default:
				}
			}
			for iterator := fetches.RecordIter(); !iterator.Done(); {
				record := iterator.Next()
				copyRecord := *record
				copyRecord.Key = append([]byte(nil), record.Key...)
				copyRecord.Value = append([]byte(nil), record.Value...)
				select {
				case observer.records <- &copyRecord:
				case <-observerContext.Done():
					return
				}
			}
		}
	}()
	select {
	case <-assigned:
	case err := <-observer.errors:
		observer.Close()
		t.Fatalf("Kafka observer assignment failed: %v", err)
	case <-time.After(15 * time.Second):
		observer.Close()
		t.Fatal("Kafka observer did not receive a partition assignment")
	}
	return observer
}

func (observer *kafkaObserver) Close() {
	observer.cancel()
	observer.client.Close()
}

func (observer *kafkaObserver) WaitForBodies(t *testing.T, wanted map[string]int, timeout time.Duration) []*kgo.Record {
	t.Helper()
	remaining := make(map[string]int, len(wanted))
	for body, count := range wanted {
		remaining[body] = count
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	var matched []*kgo.Record
	for len(remaining) > 0 {
		select {
		case record := <-observer.records:
			body := string(record.Value)
			if count, exists := remaining[body]; exists {
				matched = append(matched, record)
				if count == 1 {
					delete(remaining, body)
				} else {
					remaining[body] = count - 1
				}
			}
		case err := <-observer.errors:
			t.Fatalf("Kafka observer fetch failed: %v", err)
		case <-timer.C:
			t.Fatalf("timed out waiting for Kafka bodies; remaining=%v", remaining)
		}
	}
	return matched
}

func assertKafkaRecord(t *testing.T, record *kgo.Record, topic string, key uuid.UUID, body []byte) {
	t.Helper()
	if record.Topic != topic || string(record.Key) != key.String() || !bytes.Equal(record.Value, body) {
		t.Fatalf("Kafka record = topic:%q key:%q body:%s", record.Topic, record.Key, record.Value)
	}
}

func findKafkaRecord(t *testing.T, records []*kgo.Record, body []byte) *kgo.Record {
	t.Helper()
	for _, record := range records {
		if bytes.Equal(record.Value, body) {
			return record
		}
	}
	t.Fatalf("Kafka body not observed: %s", body)
	return nil
}

func assertBodyOrder(t *testing.T, records []*kgo.Record, first, second []byte) {
	t.Helper()
	firstIndex, secondIndex := -1, -1
	for index, record := range records {
		if bytes.Equal(record.Value, first) && firstIndex == -1 {
			firstIndex = index
		}
		if bytes.Equal(record.Value, second) && secondIndex == -1 {
			secondIndex = index
		}
	}
	if firstIndex == -1 || secondIndex == -1 || firstIndex >= secondIndex {
		t.Fatalf("same-aggregate Kafka order = first:%d second:%d", firstIndex, secondIndex)
	}
}

func kafkaSHA256(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

func integrationLogger() *slog.Logger {
	return slog.New(slog.NewJSONHandler(io.Discard, nil))
}
