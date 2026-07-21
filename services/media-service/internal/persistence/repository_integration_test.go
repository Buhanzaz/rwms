package persistence

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestRepositoryConcurrencyOwnerProofCursorAndOutboxIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	warehouseID := uuid.New()
	ownerID := uuid.New()
	proof := ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(),
		BodySHA256: hex64('1'), AggregateType: InventoryFindingAggregate,
		AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, OwnerRevision: 1, Active: true,
		RecordedAt: time.Now().UTC(),
	}
	if replayed, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil || replayed {
		t.Fatalf("ApplyValidatedOwnerProof() = %v, %v; want new proof", replayed, err)
	}
	if replayed, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil || !replayed {
		t.Fatalf("proof replay = %v, %v; want exact replay", replayed, err)
	}
	changedProof := proof
	changedProof.BodySHA256 = hex64('2')
	if _, err := repository.ApplyValidatedOwnerProof(ctx, changedProof); !errors.Is(err, ErrIdempotencyMismatch) {
		t.Fatalf("changed proof replay error = %v, want ErrIdempotencyMismatch", err)
	}
	if _, _, err := repository.CreateUpload(ctx,
		createCommand(ownerID, warehouseID, media.KindImage, 0)); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("event-ID-conflicted owner create error = %v, want fail closed", err)
	}
	conflictDLT, err := repository.ClaimOutbox(ctx, "owner-conflict-test-relay", time.Minute)
	if err != nil || conflictDLT == nil || conflictDLT.Topic != InventoryOwnerDLTTopic {
		t.Fatalf("event-ID conflict DLT claim = %#v, %v", conflictDLT, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *conflictDLT); err != nil {
		t.Fatalf("ack event-ID conflict DLT: %v", err)
	}
	gapProof := proof
	gapProof.EventID = uuid.New()
	gapProof.AggregateID = uuid.New()
	gapProof.OwnerID = gapProof.AggregateID.String()
	gapProof.AggregateVersion = 2
	gapProof.BodySHA256 = hex64('3')
	if _, err := repository.ApplyValidatedOwnerProof(ctx, gapProof); !errors.Is(err, ErrVersionGap) {
		t.Fatalf("owner proof version gap error = %v, want ErrVersionGap", err)
	}

	// The concurrency/cursor scenario needs a separate canonical owner. The
	// first aggregate is intentionally quarantined by the changed event-ID
	// replay above and must never become authorized implicitly.
	warehouseID = uuid.New()
	ownerID = uuid.New()
	proof = ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(),
		BodySHA256: hex64('4'), AggregateType: InventoryFindingAggregate,
		AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, OwnerRevision: 1, Active: true,
		RecordedAt: time.Now().UTC(),
	}
	if replayed, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil || replayed {
		t.Fatalf("ApplyValidatedOwnerProof(clean concurrency owner) = %v, %v", replayed, err)
	}

	command := createCommand(ownerID, warehouseID, media.KindImage, 5)
	var wait sync.WaitGroup
	wait.Add(2)
	type createResult struct {
		asset    AssetRecord
		replayed bool
		err      error
	}
	results := make(chan createResult, 2)
	for range 2 {
		go func() {
			defer wait.Done()
			asset, replayed, err := repository.CreateUpload(ctx, command)
			results <- createResult{asset: asset, replayed: replayed, err: err}
		}()
	}
	wait.Wait()
	close(results)
	var created, replayed int
	var asset AssetRecord
	for result := range results {
		if result.err != nil {
			t.Fatalf("concurrent CreateUpload() error = %v", result.err)
		}
		asset = result.asset
		if result.replayed {
			replayed++
		} else {
			created++
		}
	}
	if created != 1 || replayed != 1 || asset.ID != command.MediaID || asset.FolderID != command.FolderID {
		t.Fatalf("concurrent create outcomes = created:%d replayed:%d asset:%s", created, replayed, asset.ID)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
	changedCommand := command
	changedCommand.RequestSHA256 = hex64('9')
	if _, _, err := repository.CreateUpload(ctx, changedCommand); !errors.Is(err, ErrIdempotencyMismatch) {
		t.Fatalf("changed create replay error = %v, want ErrIdempotencyMismatch", err)
	}

	second := createCommand(ownerID, warehouseID, media.KindImage, 10)
	second.FolderID = command.FolderID
	if _, _, err := repository.CreateUpload(ctx, second); err != nil {
		t.Fatalf("create second image: %v", err)
	}
	video := createCommand(ownerID, warehouseID, media.KindVideo, 0)
	if _, _, err := repository.CreateUpload(ctx, video); err != nil {
		t.Fatalf("create video: %v", err)
	}
	page, err := repository.ListOwner(ctx, OwnerTypeInventoryFinding, ownerID.String(), warehouseID, 2, nil)
	if err != nil || len(page) != 2 || page[0].Kind != media.KindImage || page[1].Kind != media.KindImage {
		t.Fatalf("first page = %#v, %v; want both images", page, err)
	}
	if page[0].FolderID != command.FolderID || page[1].FolderID != command.FolderID {
		t.Fatalf("batch folder IDs = %s, %s; want %s", page[0].FolderID, page[1].FolderID, command.FolderID)
	}
	next, err := repository.ListOwner(ctx, OwnerTypeInventoryFinding, ownerID.String(), warehouseID, 2, &page[1].ID)
	if err != nil || len(next) != 1 || next[0].ID != video.MediaID {
		t.Fatalf("cursor page = %#v, %v; want video %s", next, err, video.MediaID)
	}
	foreignCursor := uuid.New()
	if _, err := repository.ListOwner(ctx, OwnerTypeInventoryFinding, ownerID.String(), warehouseID, 2, &foreignCursor); !errors.Is(err, ErrConflict) {
		t.Fatalf("foreign cursor error = %v, want ErrConflict", err)
	}

	finalize := FinalizeCommand{
		SessionID: command.UploadSessionID, SubjectID: command.SubjectID,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('4'),
		ObjectVersionID: "minio-version-1", ETag: "etag-1",
		ChecksumSHA256: command.ChecksumSHA256, ContentType: "image/jpeg",
		SizeBytes: command.ContentLength, CorrelationID: uuid.New(),
	}
	if _, replay, err := repository.FinalizeUpload(ctx, finalize); err != nil || replay {
		t.Fatalf("FinalizeUpload() = %v, %v; want new command", replay, err)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
	firstClaim, err := repository.ClaimOutbox(ctx, "integration-relay", time.Minute)
	if err != nil || firstClaim == nil || firstClaim.Topic != MediaTopic {
		t.Fatalf("first outbox claim = %#v, %v; want media fact", firstClaim, err)
	}
	assertExactClaimHash(t, firstClaim)
	assertFactFolder(t, firstClaim, command.FolderID)
	blocked, err := repository.ClaimOutbox(ctx, "integration-relay-2", time.Minute)
	if err != nil || blocked != nil {
		t.Fatalf("dependent request claim before ACK = %#v, %v; want nil", blocked, err)
	}
	firstFence := firstClaim.LeaseFence
	if err := repository.ReleaseOutboxLeases(ctx, "integration-relay"); err != nil {
		t.Fatalf("ReleaseOutboxLeases(): %v", err)
	}
	firstClaim, err = repository.ClaimOutbox(ctx, "integration-relay-restarted", time.Minute)
	if err != nil || firstClaim == nil || firstClaim.Attempt != 2 || firstClaim.LeaseFence <= firstFence {
		t.Fatalf("outbox claim after graceful release = %#v, %v; first fence=%d", firstClaim, err, firstFence)
	}
	for attempt := 1; attempt <= 5; attempt++ {
		if err := repository.MarkOutboxFailed(ctx, *firstClaim, "KAFKA_UNAVAILABLE"); err != nil {
			t.Fatalf("MarkOutboxFailed(attempt %d): %v", attempt, err)
		}
		var status string
		if err := database.Pool.QueryRow(ctx, `select event_status from media_transport_outbox where event_id=$1`, firstClaim.EventID).Scan(&status); err != nil || status != "PENDING" {
			t.Fatalf("outbox status after attempt %d = %q, %v; want PENDING", attempt, status, err)
		}
		if _, err := database.Pool.Exec(ctx, `update media_transport_outbox set next_attempt_at=clock_timestamp()-interval '1 second' where event_id=$1`, firstClaim.EventID); err != nil {
			t.Fatalf("make outbox immediately retryable: %v", err)
		}
		firstClaim, err = repository.ClaimOutbox(ctx, "integration-relay", time.Minute)
		if err != nil || firstClaim == nil || firstClaim.Attempt != attempt+2 {
			t.Fatalf("outbox retry claim %d = %#v, %v", attempt+2, firstClaim, err)
		}
	}
	if err := repository.MarkOutboxPublished(ctx, *firstClaim); err != nil {
		t.Fatalf("MarkOutboxPublished(fact): %v", err)
	}
	requestClaim, err := repository.ClaimOutbox(ctx, "integration-relay", time.Minute)
	if err != nil || requestClaim == nil || requestClaim.Topic != ProcessingTopic {
		t.Fatalf("dependent outbox claim = %#v, %v; want processing request", requestClaim, err)
	}
	assertExactClaimHash(t, requestClaim)
	if err := repository.MarkOutboxPublished(ctx, *requestClaim); err != nil {
		t.Fatalf("MarkOutboxPublished(request): %v", err)
	}
	message := ProcessingMessage{
		EventID: requestClaim.EventID, BodySHA256: requestClaim.BodySHA256,
		Topic: ProcessingTopic, EventType: "media.processing.request.v1",
		AggregateType: "PROCESSING_JOB", AggregateID: requestClaim.RecordKey,
		AggregateVersion: 1, RecordKey: requestClaim.RecordKey,
		CorrelationID: finalize.CorrelationID, ExpectedMediaID: command.MediaID,
		ExpectedWarehouseID: warehouseID, ExpectedKind: media.KindImage,
		ExpectedProcessingKind: media.ProcessingInitial, ExpectedGeneration: 1,
		ExpectedRotation: media.Rotation0, ExpectedSourceVersionID: finalize.ObjectVersionID,
	}
	foreign := message
	foreign.EventID = uuid.New()
	foreign.BodySHA256 = hex64('f')
	if _, err := repository.ClaimProcessingJob(ctx, foreign, "foreign-worker", time.Minute); !errors.Is(err, ErrConflict) {
		t.Fatalf("foreign valid-looking processing record error = %v, want ErrConflict", err)
	}
	firstJob, err := repository.ClaimProcessingJob(ctx, message, "integration-worker-1", time.Minute)
	if err != nil || firstJob.Duplicate {
		t.Fatalf("first processing claim = %#v, %v", firstJob, err)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
	if err := repository.ReleaseProcessingLeases(ctx, "integration-worker-1"); err != nil {
		t.Fatalf("ReleaseProcessingLeases(): %v", err)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
	secondJob, err := repository.ClaimProcessingJob(ctx, message, "integration-worker-2", time.Minute)
	if err != nil || secondJob.Duplicate || secondJob.Job.LeaseFence <= firstJob.Job.LeaseFence {
		t.Fatalf("reclaimed processing job = %#v, %v; first fence %d", secondJob, err, firstJob.Job.LeaseFence)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
	variants := processedImageVariants(command.MediaID, 1)
	if err := repository.CompleteProcessingJob(ctx, firstJob.Job, variants); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("stale worker completion error = %v, want ErrLeaseLost", err)
	}
	if err := repository.CompleteProcessingJob(ctx, secondJob.Job, variants); err != nil {
		t.Fatalf("fenced worker completion: %v", err)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
	duplicate, err := repository.ClaimProcessingJob(ctx, message, "integration-worker-3", time.Minute)
	if err != nil || !duplicate.Duplicate {
		t.Fatalf("processing duplicate = %#v, %v", duplicate, err)
	}
	readyClaim, err := repository.ClaimOutbox(ctx, "integration-relay", time.Minute)
	if err != nil || readyClaim == nil || readyClaim.Topic != MediaTopic {
		t.Fatalf("READY fact claim = %#v, %v", readyClaim, err)
	}
	assertExactClaimHash(t, readyClaim)
	if err := repository.MarkOutboxPublished(ctx, *readyClaim); err != nil {
		t.Fatalf("MarkOutboxPublished(ready): %v", err)
	}
	readyAsset, err := repository.GetAssetScoped(ctx, command.MediaID, OwnerTypeInventoryFinding, ownerID.String(), warehouseID)
	if err != nil {
		t.Fatalf("GetAssetScoped(ready): %v", err)
	}
	rotation := RotateCommand{
		MediaID: command.MediaID, SubjectID: command.SubjectID, IdempotencyKey: uuid.New(),
		RequestSHA256: hex64('c'), ExpectedVersion: readyAsset.Version,
		Rotation: media.Rotation90, CorrelationID: uuid.New(),
	}
	if _, replay, err := repository.Rotate(ctx, rotation); err != nil || replay {
		t.Fatalf("Rotate() = %v, %v; want new rotation", replay, err)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
	rotationRequest, err := repository.ClaimOutbox(ctx, "integration-relay", time.Minute)
	if err != nil || rotationRequest == nil || rotationRequest.Topic != ProcessingTopic {
		t.Fatalf("rotation request claim = %#v, %v", rotationRequest, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *rotationRequest); err != nil {
		t.Fatalf("publish rotation request: %v", err)
	}
	rotationMessage := ProcessingMessage{
		EventID: rotationRequest.EventID, BodySHA256: rotationRequest.BodySHA256,
		Topic: ProcessingTopic, EventType: "media.processing.request.v1",
		AggregateType: "PROCESSING_JOB", AggregateID: rotationRequest.RecordKey,
		AggregateVersion: 1, RecordKey: rotationRequest.RecordKey,
		CorrelationID: rotation.CorrelationID, ExpectedMediaID: command.MediaID,
		ExpectedWarehouseID: warehouseID, ExpectedKind: media.KindImage,
		ExpectedProcessingKind: media.ProcessingRotation, ExpectedGeneration: readyAsset.Generation + 1,
		ExpectedRotation: media.Rotation90, ExpectedSourceVersionID: finalize.ObjectVersionID,
	}
	rotationJob, err := repository.ClaimProcessingJob(ctx, rotationMessage, "rotation-worker", time.Minute)
	if err != nil || rotationJob.Duplicate {
		t.Fatalf("rotation processing claim = %#v, %v", rotationJob, err)
	}
	if err := repository.CompleteProcessingJob(ctx, rotationJob.Job, processedImageVariants(command.MediaID, readyAsset.Generation+1)); err != nil {
		t.Fatalf("complete rotation: %v", err)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
	rotatedFact, err := repository.ClaimOutbox(ctx, "integration-relay", time.Minute)
	if err != nil || rotatedFact == nil || rotatedFact.Topic != MediaTopic {
		t.Fatalf("rotated fact claim = %#v, %v", rotatedFact, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *rotatedFact); err != nil {
		t.Fatalf("publish rotated fact: %v", err)
	}
	if remaining, err := repository.ClaimOutbox(ctx, "integration-relay", time.Minute); err != nil || remaining != nil {
		t.Fatalf("remaining outbox claim = %#v, %v; want nil", remaining, err)
	}
	if _, replay, err := repository.FinalizeUpload(ctx, finalize); err != nil || !replay {
		t.Fatalf("finalize replay = %v, %v; want exact replay", replay, err)
	}
	firstReplay, err := repository.RebuildShadowProjection(ctx, command.MediaID)
	if err != nil {
		t.Fatalf("first replay: %v", err)
	}
	secondReplay, err := repository.RebuildShadowProjection(ctx, command.MediaID)
	if err != nil || firstReplay.SHA256 != secondReplay.SHA256 || string(firstReplay.State) != string(secondReplay.State) {
		t.Fatalf("repeat replay = %#v, %v; want deterministic %#v", secondReplay, err, firstReplay)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set sort_order=sort_order+1 where media_id=$1`, command.MediaID); err != nil {
		t.Fatalf("tamper live projection: %v", err)
	}
	if err := repository.VerifyReplayParity(ctx, command.MediaID); !errors.Is(err, ErrReplayParity) {
		t.Fatalf("tampered projection parity error = %v, want ErrReplayParity", err)
	}
	if _, err := database.Pool.Exec(ctx, `update media_asset set sort_order=sort_order-1 where media_id=$1`, command.MediaID); err != nil {
		t.Fatalf("restore live projection: %v", err)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)
}

func TestProcessingRetriesAndSanitizedDLTIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	warehouseID := uuid.New()
	ownerID := uuid.New()
	proof := ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(),
		BodySHA256: hex64('6'), AggregateType: InventoryFindingAggregate,
		AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, OwnerRevision: 1, Active: true,
		RecordedAt: time.Now().UTC(),
	}
	if _, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil {
		t.Fatalf("ApplyValidatedOwnerProof() error = %v", err)
	}
	command := createCommand(ownerID, warehouseID, media.KindImage, 0)
	if _, _, err := repository.CreateUpload(ctx, command); err != nil {
		t.Fatalf("CreateUpload() error = %v", err)
	}
	finalize := FinalizeCommand{
		SessionID: command.UploadSessionID, SubjectID: command.SubjectID,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('7'),
		ObjectVersionID: "minio-version-failure", ETag: "etag-failure",
		ChecksumSHA256: command.ChecksumSHA256, ContentType: "image/jpeg",
		SizeBytes: command.ContentLength, CorrelationID: uuid.New(),
	}
	if _, _, err := repository.FinalizeUpload(ctx, finalize); err != nil {
		t.Fatalf("FinalizeUpload() error = %v", err)
	}
	factClaim, err := repository.ClaimOutbox(ctx, "retry-relay", time.Minute)
	if err != nil || factClaim == nil || factClaim.Topic != MediaTopic {
		t.Fatalf("UPLOADED fact claim = %#v, %v", factClaim, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *factClaim); err != nil {
		t.Fatalf("publish UPLOADED fact: %v", err)
	}
	requestClaim, err := repository.ClaimOutbox(ctx, "retry-relay", time.Minute)
	if err != nil || requestClaim == nil || requestClaim.Topic != ProcessingTopic {
		t.Fatalf("processing request claim = %#v, %v", requestClaim, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *requestClaim); err != nil {
		t.Fatalf("publish processing request: %v", err)
	}
	message := ProcessingMessage{
		EventID: requestClaim.EventID, BodySHA256: requestClaim.BodySHA256,
		Topic: ProcessingTopic, EventType: "media.processing.request.v1",
		AggregateType: "PROCESSING_JOB", AggregateID: requestClaim.RecordKey,
		AggregateVersion: 1, RecordKey: requestClaim.RecordKey,
		CorrelationID: finalize.CorrelationID, ExpectedMediaID: command.MediaID,
		ExpectedWarehouseID: warehouseID, ExpectedKind: media.KindImage,
		ExpectedProcessingKind: media.ProcessingInitial, ExpectedGeneration: 1,
		ExpectedRotation: media.Rotation0, ExpectedSourceVersionID: finalize.ObjectVersionID,
	}
	for attempt := 1; attempt <= 4; attempt++ {
		claim, err := repository.ClaimProcessingJob(ctx, message, "retry-worker", time.Minute)
		if err != nil || claim.Duplicate || claim.Job.Attempt != attempt {
			t.Fatalf("processing attempt %d claim = %#v, %v", attempt, claim, err)
		}
		assertReplayParity(t, ctx, repository, command.MediaID)
		delay, terminal, err := repository.RecordProcessingFailure(ctx, claim.Job, "PROCESSING_DEPENDENCY_UNAVAILABLE")
		if err != nil {
			t.Fatalf("RecordProcessingFailure(attempt %d): %v", attempt, err)
		}
		if attempt < 4 {
			assertReplayParity(t, ctx, repository, command.MediaID)
			wantDelay := time.Second * time.Duration(1<<(attempt-1))
			if terminal || delay != wantDelay {
				t.Fatalf("attempt %d retry = %s, terminal=%v; want %s,false", attempt, delay, terminal, wantDelay)
			}
			if _, err := database.Pool.Exec(ctx, `update media_processing_job
				set next_attempt_at=clock_timestamp()-interval '1 second'
				where processing_job_id=$1`, claim.Job.JobID); err != nil {
				t.Fatalf("make attempt %d immediately retryable: %v", attempt, err)
			}
		} else if !terminal || delay != 0 {
			t.Fatalf("attempt 4 retry = %s, terminal=%v; want 0,true", delay, terminal)
		}
	}
	duplicate, err := repository.ClaimProcessingJob(ctx, message, "retry-worker", time.Minute)
	if err != nil || !duplicate.Duplicate {
		t.Fatalf("terminal processing duplicate = %#v, %v", duplicate, err)
	}
	var failureCode, bodySHA, status string
	var attemptCount, generation, nextGeneration int
	if err := database.Pool.QueryRow(ctx, `select dead.failure_code,dead.attempt_count,dead.body_sha256,
		asset.processing_status,asset.current_generation,asset.next_generation
		from media_dead_letter dead join media_asset asset on asset.media_id=$2
		where dead.consumer_name=$1 and dead.event_id=$3`, processingConsumer,
		command.MediaID, message.EventID).Scan(&failureCode, &attemptCount, &bodySHA, &status, &generation, &nextGeneration); err != nil {
		t.Fatalf("read terminal DLT/projection: %v", err)
	}
	if failureCode != "PROCESSING_DEPENDENCY_UNAVAILABLE" || attemptCount != 4 || bodySHA != message.BodySHA256 ||
		status != string(media.StatusFailed) || generation != 0 || nextGeneration != 2 {
		t.Fatalf("terminal DLT/projection = %s,%d,%s,%s,%d,%d", failureCode, attemptCount, bodySHA, status, generation, nextGeneration)
	}
	terminalTopics := map[string]bool{}
	for range 2 {
		claim, err := repository.ClaimOutbox(ctx, "retry-relay", time.Minute)
		if err != nil || claim == nil {
			t.Fatalf("terminal outbox claim = %#v, %v", claim, err)
		}
		assertExactClaimHash(t, claim)
		terminalTopics[claim.Topic] = true
		if claim.Topic == ProcessingDLTTopic {
			var safeBody map[string]any
			if err := json.Unmarshal(claim.Body, &safeBody); err != nil || len(safeBody) != 3 ||
				safeBody["failureCode"] != "PROCESSING_DEPENDENCY_UNAVAILABLE" ||
				safeBody["messageSha256"] != message.BodySHA256 {
				t.Fatalf("sanitized DLT body = %s, error=%v", claim.Body, err)
			}
		}
		if err := repository.MarkOutboxPublished(ctx, *claim); err != nil {
			t.Fatalf("publish terminal record: %v", err)
		}
	}
	if !terminalTopics[MediaTopic] || !terminalTopics[ProcessingDLTTopic] || len(terminalTopics) != 2 {
		t.Fatalf("terminal topics = %#v, want FAILED fact and sanitized DLT", terminalTopics)
	}
	assertReplayParity(t, ctx, repository, command.MediaID)

	validationCommand := createCommand(ownerID, warehouseID, media.KindImage, 1)
	if _, _, err := repository.CreateUpload(ctx, validationCommand); err != nil {
		t.Fatalf("create validation-failure image: %v", err)
	}
	validationFinalize := FinalizeCommand{
		SessionID: validationCommand.UploadSessionID, SubjectID: validationCommand.SubjectID,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('8'), ObjectVersionID: "validation-version",
		ETag: "validation-etag", ChecksumSHA256: validationCommand.ChecksumSHA256,
		ContentType: "image/jpeg", SizeBytes: validationCommand.ContentLength, CorrelationID: uuid.New(),
	}
	if _, _, err := repository.FinalizeUpload(ctx, validationFinalize); err != nil {
		t.Fatalf("finalize validation-failure image: %v", err)
	}
	validationFact, err := repository.ClaimOutbox(ctx, "validation-relay", time.Minute)
	if err != nil || validationFact == nil || validationFact.Topic != MediaTopic {
		t.Fatalf("validation UPLOADED claim = %#v, %v", validationFact, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *validationFact); err != nil {
		t.Fatalf("publish validation UPLOADED: %v", err)
	}
	validationRequest, err := repository.ClaimOutbox(ctx, "validation-relay", time.Minute)
	if err != nil || validationRequest == nil || validationRequest.Topic != ProcessingTopic {
		t.Fatalf("validation request claim = %#v, %v", validationRequest, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *validationRequest); err != nil {
		t.Fatalf("publish validation request: %v", err)
	}
	validationMessage := ProcessingMessage{
		EventID: validationRequest.EventID, BodySHA256: validationRequest.BodySHA256,
		Topic: ProcessingTopic, EventType: "media.processing.request.v1",
		AggregateType: "PROCESSING_JOB", AggregateID: validationRequest.RecordKey,
		AggregateVersion: 1, RecordKey: validationRequest.RecordKey,
		CorrelationID: validationFinalize.CorrelationID, ExpectedMediaID: validationCommand.MediaID,
		ExpectedWarehouseID: warehouseID, ExpectedKind: media.KindImage,
		ExpectedProcessingKind: media.ProcessingInitial, ExpectedGeneration: 1,
		ExpectedRotation: media.Rotation0, ExpectedSourceVersionID: validationFinalize.ObjectVersionID,
	}
	validationJob, err := repository.ClaimProcessingJob(ctx, validationMessage, "validation-worker", time.Minute)
	if err != nil || validationJob.Job.Attempt != 1 {
		t.Fatalf("validation attempt claim = %#v, %v", validationJob, err)
	}
	if delay, terminal, err := repository.RecordProcessingFailure(ctx, validationJob.Job, "VALIDATION_FAILED"); err != nil || !terminal || delay != 0 {
		t.Fatalf("validation failure = delay:%s terminal:%v error:%v", delay, terminal, err)
	}
	var validationAttempts int
	if err := database.Pool.QueryRow(ctx, `select attempt_count from media_dead_letter
		where consumer_name=$1 and event_id=$2`, processingConsumer, validationMessage.EventID).Scan(&validationAttempts); err != nil {
		t.Fatalf("read validation DLT attempt count: %v", err)
	}
	if validationAttempts != 1 {
		t.Fatalf("validation DLT attempt count = %d, want actual first attempt", validationAttempts)
	}
	assertReplayParity(t, ctx, repository, validationCommand.MediaID)
	for range 2 {
		claim, err := repository.ClaimOutbox(ctx, "validation-relay", time.Minute)
		if err != nil || claim == nil {
			t.Fatalf("validation terminal claim = %#v, %v", claim, err)
		}
		if err := repository.MarkOutboxPublished(ctx, *claim); err != nil {
			t.Fatalf("publish validation terminal record: %v", err)
		}
	}
}

func TestOwnerProofRevisionConflictsQuarantineWithoutAdvancingCheckpoint(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)
	ownerID, warehouseID := uuid.New(), uuid.New()
	proof := ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(), BodySHA256: hex64('1'),
		AggregateType: InventoryFindingAggregate, AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(), WarehouseID: warehouseID,
		OwnerRevision: 2, Active: true, RecordedAt: time.Now().UTC(),
	}
	if _, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil {
		t.Fatalf("initial owner proof: %v", err)
	}
	regression := proof
	regression.EventID, regression.AggregateVersion, regression.OwnerRevision, regression.BodySHA256 = uuid.New(), 2, 1, hex64('2')
	if _, err := repository.ApplyValidatedOwnerProof(ctx, regression); !errors.Is(err, ErrOwnerProofConflict) {
		t.Fatalf("owner revision regression error = %v, want ErrOwnerProofConflict", err)
	}
	var checkpoint int64
	var reason string
	if err := database.Pool.QueryRow(ctx, `select aggregate_version from media_consumer_aggregate_checkpoint
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3`, proof.ConsumerName, proof.AggregateType, ownerID).Scan(&checkpoint); err != nil {
		t.Fatalf("read owner checkpoint: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3 and reconciled_at is null`, proof.ConsumerName, proof.AggregateType, ownerID).Scan(&reason); err != nil {
		t.Fatalf("read owner quarantine: %v", err)
	}
	if checkpoint != 1 || reason != "OWNER_REVISION_REGRESSION" {
		t.Fatalf("owner conflict state = checkpoint:%d reason:%s", checkpoint, reason)
	}
	if _, _, err := repository.CreateUpload(ctx, createCommand(ownerID, warehouseID, media.KindImage, 0)); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("quarantined owner create error = %v, want fail closed", err)
	}

	secondOwner, secondWarehouse := uuid.New(), uuid.New()
	second := proof
	second.AggregateID, second.OwnerID, second.WarehouseID, second.EventID = secondOwner, secondOwner.String(), secondWarehouse, uuid.New()
	second.OwnerRevision, second.BodySHA256 = 4, hex64('3')
	if _, err := repository.ApplyValidatedOwnerProof(ctx, second); err != nil {
		t.Fatalf("second initial owner proof: %v", err)
	}
	equalConflict := second
	equalConflict.EventID, equalConflict.AggregateVersion = uuid.New(), 2
	equalConflict.WarehouseID, equalConflict.BodySHA256 = uuid.New(), hex64('4')
	if _, err := repository.ApplyValidatedOwnerProof(ctx, equalConflict); !errors.Is(err, ErrOwnerProofConflict) {
		t.Fatalf("equal revision conflicting payload error = %v, want ErrOwnerProofConflict", err)
	}
}

func createCommand(ownerID, warehouseID uuid.UUID, kind media.Kind, sortOrder int64) CreateUploadCommand {
	mediaID := uuid.New()
	extension := ".jpg"
	contentType := "image/jpeg"
	if kind == media.KindVideo {
		extension = ".mp4"
		contentType = "video/mp4"
	}
	return CreateUploadCommand{
		MediaID: mediaID, FolderID: mediaID, UploadSessionID: uuid.New(), SubjectID: uuid.New(),
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('5'),
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, Kind: kind, FileName: "source" + extension,
		ContentType: contentType, ContentLength: 128, ChecksumSHA256: hex64('a'),
		SortOrder: sortOrder, SourceObjectKey: "media/" + mediaID.String() + "/source/upload" + extension,
		UploadExpiresAt: time.Now().UTC().Add(time.Hour), CorrelationID: uuid.New(),
	}
}

func assertFactFolder(t *testing.T, claim *OutboxClaim, folderID uuid.UUID) {
	t.Helper()
	var envelope map[string]any
	if err := json.Unmarshal(claim.Body, &envelope); err != nil {
		t.Fatalf("decode media fact: %v", err)
	}
	payload, ok := envelope["payload"].(map[string]any)
	if !ok || payload["folderId"] != folderID.String() {
		t.Fatalf("media fact folder = %#v, want %s", payload["folderId"], folderID)
	}
}

func assertExactClaimHash(t *testing.T, claim *OutboxClaim) {
	t.Helper()
	sum := sha256.Sum256(claim.Body)
	if got := hex.EncodeToString(sum[:]); got != claim.BodySHA256 {
		t.Fatalf("claimed exact-byte hash = %s, want %s", got, claim.BodySHA256)
	}
}

func processedImageVariants(mediaID uuid.UUID, generation int) []media.ProcessedVariant {
	return []media.ProcessedVariant{
		{Variant: media.VariantOriginal, ObjectKey: media.OriginalObjectKey(mediaID.String(), generation, ".jpg"), ContentType: "image/jpeg", SizeBytes: 128, Width: 10, Height: 10, ChecksumSHA256: hex64('b'), ObjectVersionID: "derived-version-original"},
		{Variant: media.VariantSmall, ObjectKey: media.ImageVariantObjectKey(mediaID.String(), generation, media.VariantSmall), ContentType: "image/webp", SizeBytes: 64, Width: 10, Height: 10, ChecksumSHA256: hex64('c'), ObjectVersionID: "derived-version-small"},
		{Variant: media.VariantMedium, ObjectKey: media.ImageVariantObjectKey(mediaID.String(), generation, media.VariantMedium), ContentType: "image/webp", SizeBytes: 96, Width: 10, Height: 10, ChecksumSHA256: hex64('d'), ObjectVersionID: "derived-version-medium"},
		{Variant: media.VariantLarge, ObjectKey: media.ImageVariantObjectKey(mediaID.String(), generation, media.VariantLarge), ContentType: "image/webp", SizeBytes: 112, Width: 10, Height: 10, ChecksumSHA256: hex64('e'), ObjectVersionID: "derived-version-large"},
	}
}

func assertReplayParity(t *testing.T, ctx context.Context, repository *Repository, mediaID uuid.UUID) {
	t.Helper()
	if err := repository.VerifyReplayParity(ctx, mediaID); err != nil {
		t.Fatalf("VerifyReplayParity(%s): %v", mediaID, err)
	}
}

func hex64(character byte) string {
	value := make([]byte, 64)
	for index := range value {
		value[index] = character
	}
	return string(value)
}
