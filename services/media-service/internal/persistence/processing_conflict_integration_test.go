package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestProcessingConflictResolutionUsesAuthoritativeStateIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}

	t.Run("orphan terminalization and exact replay", func(t *testing.T) {
		ctx, repository, database := newProcessingConflictRepository(t, databaseURL)
		message := ProcessingMessage{
			EventID: uuid.New(), BodySHA256: hex64('6'), Topic: ProcessingTopic,
			EventType: "media.processing.request.v1", AggregateType: "PROCESSING_JOB",
			AggregateID: uuid.New(), AggregateVersion: 1, CorrelationID: uuid.New(),
			ExpectedMediaID: uuid.New(), ExpectedWarehouseID: uuid.New(),
			ExpectedKind: media.KindImage, ExpectedProcessingKind: media.ProcessingInitial,
			ExpectedGeneration: 1, ExpectedRotation: media.Rotation0,
			ExpectedSourceVersionID: "orphan-source-version",
		}
		message.RecordKey = message.AggregateID

		resolution, err := repository.ResolveProcessingClaimConflict(ctx, message)
		if err != nil || resolution.Disposition != ProcessingConflictTerminalConflict ||
			!resolution.RetryAt.IsZero() {
			t.Fatalf("orphan resolution = %#v, %v", resolution, err)
		}
		assertOrphanProcessingTerminalState(t, ctx, database, message)

		replay, err := repository.ResolveProcessingClaimConflict(ctx, message)
		if err != nil || replay.Disposition != ProcessingConflictTerminalConflict {
			t.Fatalf("orphan replay = %#v, %v", replay, err)
		}
		assertOrphanProcessingTerminalState(t, ctx, database, message)

		changed := message
		changed.BodySHA256 = hex64('7')
		if _, err := repository.ResolveProcessingClaimConflict(ctx, changed); !errors.Is(err, ErrIdempotencyMismatch) {
			t.Fatalf("changed orphan replay error = %v, want ErrIdempotencyMismatch", err)
		}
		assertOrphanProcessingTerminalState(t, ctx, database, message)
	})

	t.Run("live lease and persisted retry remain retryable", func(t *testing.T) {
		ctx, repository, database := newProcessingConflictRepository(t, databaseURL)
		message := createAuthoritativeProcessingMessage(t, ctx, repository)

		changed := message
		changed.BodySHA256 = hex64('f')
		if _, err := repository.ResolveProcessingClaimConflict(ctx, changed); !errors.Is(err, ErrIdempotencyMismatch) {
			t.Fatalf("changed authoritative bytes error = %v, want ErrIdempotencyMismatch", err)
		}
		assertNoProcessingTerminalState(t, ctx, database, message.EventID)

		claim, err := repository.ClaimProcessingJob(ctx, message, "live-lease-worker", 2*time.Minute)
		if err != nil || claim.Duplicate || claim.Job.Attempt != 1 || claim.Job.AttemptInCycle != 1 {
			t.Fatalf("live processing claim = %#v, %v", claim, err)
		}
		if _, err := repository.ClaimProcessingJob(ctx, message, "competing-worker", time.Minute); !errors.Is(err, ErrConflict) {
			t.Fatalf("competing live lease claim error = %v, want ErrConflict", err)
		}
		live, err := repository.ResolveProcessingClaimConflict(ctx, message)
		if err != nil || live.Disposition != ProcessingConflictRetryAt ||
			!live.RetryAt.After(time.Now().Add(time.Minute)) {
			t.Fatalf("live lease resolution = %#v, %v", live, err)
		}
		assertNoProcessingTerminalState(t, ctx, database, message.EventID)

		if err := repository.ReleaseProcessingLeases(ctx, "live-lease-worker"); err != nil {
			t.Fatalf("release live processing lease: %v", err)
		}
		claim, err = repository.ClaimProcessingJob(ctx, message, "retry-worker", time.Minute)
		if err != nil || claim.Duplicate || claim.Job.Attempt != 2 || claim.Job.AttemptInCycle != 2 {
			t.Fatalf("retry processing claim = %#v, %v", claim, err)
		}
		delay, terminal, err := repository.RecordProcessingFailure(
			ctx, claim.Job, "PROCESSING_DEPENDENCY_UNAVAILABLE")
		if err != nil || terminal || delay != 2*time.Second {
			t.Fatalf("persist retry = %s, terminal=%v, error=%v", delay, terminal, err)
		}
		if _, err := repository.ClaimProcessingJob(ctx, message, "early-retry-worker", time.Minute); !errors.Is(err, ErrConflict) {
			t.Fatalf("early persisted retry claim error = %v, want ErrConflict", err)
		}
		retry, err := repository.ResolveProcessingClaimConflict(ctx, message)
		if err != nil || retry.Disposition != ProcessingConflictRetryAt ||
			!retry.RetryAt.After(time.Now()) || retry.RetryAt.After(time.Now().Add(3*time.Second)) {
			t.Fatalf("persisted retry resolution = %#v, %v", retry, err)
		}
		var attempt int
		var bodySHA string
		if err := database.Pool.QueryRow(ctx, `select attempt,body_sha256
			from media_retry_schedule where consumer_name=$1 and event_id=$2`,
			processingConsumer, message.EventID).Scan(&attempt, &bodySHA); err != nil ||
			attempt != 2 || bodySHA != message.BodySHA256 {
			t.Fatalf("persisted retry state = attempt:%d hash:%s error:%v", attempt, bodySHA, err)
		}
		assertNoProcessingTerminalState(t, ctx, database, message.EventID)
	})
}

func newProcessingConflictRepository(
	t testing.TB,
	baseURL string,
) (context.Context, *Repository, *Database) {
	t.Helper()
	databaseURL := testsupport.NewMigratedMediaDatabase(t, baseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	t.Cleanup(cancel)
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open processing conflict database: %v", err)
	}
	t.Cleanup(database.Close)
	return ctx, NewRepository(database.Pool), database
}

func createAuthoritativeProcessingMessage(
	t testing.TB,
	ctx context.Context,
	repository *Repository,
) ProcessingMessage {
	t.Helper()
	ownerID, warehouseID := uuid.New(), uuid.New()
	proof := ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(),
		BodySHA256: hex64('1'), AggregateType: InventoryFindingAggregate,
		AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, OwnerRevision: 1, Active: true,
		RecordedAt: time.Now().UTC(),
	}
	if replayed, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil || replayed {
		t.Fatalf("apply processing conflict owner proof = %v, %v", replayed, err)
	}
	command := createCommand(ownerID, warehouseID, media.KindImage, 0)
	if _, replayed, err := repository.CreateUpload(ctx, command); err != nil || replayed {
		t.Fatalf("create processing conflict upload = %v, %v", replayed, err)
	}
	finalize := FinalizeCommand{
		SessionID: command.UploadSessionID, SubjectID: command.SubjectID,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('2'),
		ObjectVersionID: "processing-conflict-version", ETag: "processing-conflict-etag",
		ChecksumSHA256: command.ChecksumSHA256, ContentType: command.ContentType,
		SizeBytes: command.ContentLength, CorrelationID: uuid.New(),
	}
	if _, replayed, err := repository.FinalizeUpload(ctx, finalize); err != nil || replayed {
		t.Fatalf("finalize processing conflict upload = %v, %v", replayed, err)
	}
	fact, err := repository.ClaimOutbox(ctx, "processing-conflict-relay", time.Minute)
	if err != nil || fact == nil || fact.Topic != MediaTopic {
		t.Fatalf("claim processing conflict fact = %#v, %v", fact, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *fact); err != nil {
		t.Fatalf("publish processing conflict fact: %v", err)
	}
	request, err := repository.ClaimOutbox(ctx, "processing-conflict-relay", time.Minute)
	if err != nil || request == nil || request.Topic != ProcessingTopic {
		t.Fatalf("claim processing conflict request = %#v, %v", request, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *request); err != nil {
		t.Fatalf("publish processing conflict request: %v", err)
	}
	return ProcessingMessage{
		EventID: request.EventID, BodySHA256: request.BodySHA256,
		Topic: ProcessingTopic, EventType: "media.processing.request.v1",
		AggregateType: "PROCESSING_JOB", AggregateID: request.RecordKey,
		AggregateVersion: 1, RecordKey: request.RecordKey,
		CorrelationID: finalize.CorrelationID, ExpectedMediaID: command.MediaID,
		ExpectedWarehouseID: warehouseID, ExpectedKind: media.KindImage,
		ExpectedProcessingKind: media.ProcessingInitial, ExpectedGeneration: 1,
		ExpectedRotation: media.Rotation0, ExpectedSourceVersionID: finalize.ObjectVersionID,
	}
}

func assertOrphanProcessingTerminalState(
	t testing.TB,
	ctx context.Context,
	database *Database,
	message ProcessingMessage,
) {
	t.Helper()
	var outcome, inboxHash, failureCode string
	var attempt, inboxCount, deadCount, outboxCount int
	if err := database.Pool.QueryRow(ctx, `select
		(select count(*) from media_processing_inbox where consumer_name=$1 and event_id=$2),
		(select outcome from media_processing_inbox where consumer_name=$1 and event_id=$2),
		(select body_sha256 from media_processing_inbox where consumer_name=$1 and event_id=$2),
		(select count(*) from media_dead_letter where consumer_name=$1 and event_id=$2),
		(select failure_code from media_dead_letter where consumer_name=$1 and event_id=$2),
		(select attempt_count from media_dead_letter where consumer_name=$1 and event_id=$2),
		(select count(*) from media_transport_outbox where aggregate_type='PROCESSING_DLT'
		 and aggregate_id=$3 and event_type='media.processing.dlt.v1')`, processingConsumer,
		message.EventID, message.AggregateID).Scan(&inboxCount, &outcome, &inboxHash,
		&deadCount, &failureCode, &attempt, &outboxCount); err != nil {
		t.Fatalf("read orphan processing terminal state: %v", err)
	}
	if inboxCount != 1 || outcome != "DLT" || inboxHash != message.BodySHA256 ||
		deadCount != 1 || failureCode != "VALIDATION_FAILED" || attempt != 1 ||
		outboxCount != 1 {
		t.Fatalf("orphan terminal state = inbox:%d/%s/%s dead:%d/%s/%d outbox:%d",
			inboxCount, outcome, inboxHash, deadCount, failureCode, attempt, outboxCount)
	}

	var eventID, aggregateID, recordKey uuid.UUID
	var topic, eventType, bodySHA string
	var wireBody []byte
	if err := database.Pool.QueryRow(ctx, `select event_id,aggregate_id,event_type,topic,
		record_key,wire_body,envelope_sha256 from media_transport_outbox
		where aggregate_type='PROCESSING_DLT' and aggregate_id=$1`,
		message.AggregateID).Scan(&eventID, &aggregateID, &eventType, &topic,
		&recordKey, &wireBody, &bodySHA); err != nil {
		t.Fatalf("read orphan processing DLT outbox: %v", err)
	}
	wantEventID := uuid.NewSHA1(uuid.NameSpaceOID, []byte(
		"media-processing-dlt:"+message.EventID.String()+":VALIDATION_FAILED"))
	sum := sha256.Sum256(wireBody)
	var body map[string]any
	if err := json.Unmarshal(wireBody, &body); err != nil {
		t.Fatalf("decode orphan processing DLT: %v", err)
	}
	if eventID != wantEventID || aggregateID != message.AggregateID ||
		recordKey != message.AggregateID || eventType != "media.processing.dlt.v1" ||
		topic != ProcessingDLTTopic || hex.EncodeToString(sum[:]) != bodySHA ||
		len(body) != 3 || body["failureCode"] != "VALIDATION_FAILED" ||
		body["messageSha256"] != message.BodySHA256 {
		t.Fatalf("orphan DLT metadata/hash/body mismatch")
	}
}

func assertNoProcessingTerminalState(
	t testing.TB,
	ctx context.Context,
	database *Database,
	eventID uuid.UUID,
) {
	t.Helper()
	var inboxCount, deadCount int
	if err := database.Pool.QueryRow(ctx, `select
		(select count(*) from media_processing_inbox where consumer_name=$1 and event_id=$2),
		(select count(*) from media_dead_letter where consumer_name=$1 and event_id=$2)`,
		processingConsumer, eventID).Scan(&inboxCount, &deadCount); err != nil {
		t.Fatalf("read processing terminal counts: %v", err)
	}
	if inboxCount != 0 || deadCount != 0 {
		t.Fatalf("processing state terminalized early: inbox=%d dead=%d", inboxCount, deadCount)
	}
}
