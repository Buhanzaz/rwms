package persistence

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

// TestClientImageVariantBundleLifecycleIntegration proves that three
// independently uploaded WebP objects become one READY logical image without
// a server-side image decode, copy, or object rewrite.
func TestClientImageVariantBundleLifecycleIntegration(t *testing.T) {
	baseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if baseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL := testsupport.NewMigratedMediaDatabase(t, baseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	warehouseID, ownerID, subjectID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	proof := ValidatedOwnerProof{
		ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(), BodySHA256: hex64('1'),
		AggregateType: InventoryFindingAggregate, AggregateID: ownerID, AggregateVersion: 1,
		OwnerType: OwnerTypeInventoryFinding, OwnerID: ownerID.String(), WarehouseID: warehouseID,
		OwnerRevision: 1, Active: true, RecordedAt: time.Now().UTC(),
	}
	if replayed, proofErr := repository.ApplyValidatedOwnerProof(ctx, proof); proofErr != nil || replayed {
		t.Fatalf("ApplyValidatedOwnerProof() = %v, %v", replayed, proofErr)
	}
	parts := []UploadImageVariantExpectation{
		{Variant: media.VariantSmall, ContentLength: 100, ChecksumSHA256: hex64('a'), Width: 320, Height: 180,
			ObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantSmall)},
		{Variant: media.VariantMedium, ContentLength: 200, ChecksumSHA256: hex64('b'), Width: 640, Height: 360,
			ObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantMedium)},
		{Variant: media.VariantLarge, ContentLength: 300, ChecksumSHA256: hex64('c'), Width: 1280, Height: 720,
			ObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantLarge)},
	}
	create := CreateUploadCommand{
		MediaID: mediaID, FolderID: mediaID, UploadSessionID: uuid.New(), SubjectID: subjectID,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('2'), OwnerType: OwnerTypeInventoryFinding,
		OwnerID: ownerID.String(), WarehouseID: warehouseID, Kind: media.KindImage,
		FileName: "client-original.jpg", ContentType: "image/webp", ContentLength: 600,
		ChecksumSHA256: hex64('3'), UploadMode: UploadModeImageVariants, ImageVariants: parts,
		SourceObjectKey: parts[2].ObjectKey, UploadExpiresAt: time.Now().UTC().Add(time.Hour),
		CorrelationID: uuid.New(),
	}
	asset, replayed, err := repository.CreateUpload(ctx, create)
	if err != nil || replayed || asset.UploadMode != UploadModeImageVariants {
		t.Fatalf("CreateUpload() = %#v, replay=%v, error=%v", asset, replayed, err)
	}
	finalizeParts := make([]FinalizeImageVariant, 0, 3)
	variantCommands := make([]CompleteUploadImageVariantCommand, 0, 3)
	for index, part := range parts {
		idempotencyKey := uuid.New()
		command := CompleteUploadImageVariantCommand{
			SessionID: asset.UploadSessionID, SubjectID: subjectID, PrincipalType: PrincipalTypeUser,
			MediaID: mediaID, Variant: part.Variant, IdempotencyKey: idempotencyKey,
			ObjectVersionID: "object-version-" + string(rune('1'+index)),
			ETag:            "etag-" + string(rune('1'+index)), ChecksumSHA256: part.ChecksumSHA256,
			SizeBytes: part.ContentLength,
		}
		completed, partReplay, completeErr := repository.CompleteUploadImageVariant(ctx, command)
		if completeErr != nil || partReplay || completed.UploadedAt == nil {
			t.Fatalf("CompleteUploadImageVariant(%s) = %#v replay=%v error=%v", part.Variant, completed, partReplay, completeErr)
		}
		if _, exactReplay, replayErr := repository.CompleteUploadImageVariant(ctx, command); replayErr != nil || !exactReplay {
			t.Fatalf("variant exact replay(%s) = %v, %v", part.Variant, exactReplay, replayErr)
		}
		mismatch := command
		mismatch.IdempotencyKey = uuid.New()
		if _, _, mismatchErr := repository.CompleteUploadImageVariant(ctx, mismatch); !errors.Is(mismatchErr, ErrIdempotencyMismatch) {
			t.Fatalf("variant mismatched replay(%s) error = %v", part.Variant, mismatchErr)
		}
		finalizeParts = append(finalizeParts, FinalizeImageVariant{
			Variant: part.Variant, ObjectVersionID: command.ObjectVersionID,
			ETag: command.ETag, ChecksumSHA256: part.ChecksumSHA256,
		})
		variantCommands = append(variantCommands, command)
	}
	finalize := FinalizeCommand{
		SessionID: asset.UploadSessionID, SubjectID: subjectID, PrincipalType: PrincipalTypeUser,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('4'), ImageVariants: finalizeParts,
		CorrelationID: uuid.New(),
	}
	processingAsset, finalizeReplay, err := repository.FinalizeUpload(ctx, finalize)
	if err != nil || finalizeReplay || processingAsset.Status != media.StatusProcessing ||
		processingAsset.SourceVersionID != finalizeParts[2].ObjectVersionID ||
		processingAsset.SourceChecksum != parts[2].ChecksumSHA256 || processingAsset.SizeBytes == nil ||
		*processingAsset.SizeBytes != parts[2].ContentLength {
		t.Fatalf("FinalizeUpload() = %#v replay=%v error=%v", processingAsset, finalizeReplay, err)
	}
	for _, command := range variantCommands {
		if _, exactReplay, replayErr := repository.CompleteUploadImageVariant(ctx, command); replayErr != nil || !exactReplay {
			t.Fatalf("finalized variant exact replay(%s) = %v, %v", command.Variant, exactReplay, replayErr)
		}
	}
	uploaded, err := repository.ClaimOutbox(ctx, "client-image-relay", time.Minute)
	if err != nil || uploaded == nil || uploaded.Topic != MediaTopic {
		t.Fatalf("claim uploaded fact = %#v, %v", uploaded, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *uploaded); err != nil {
		t.Fatalf("publish uploaded fact: %v", err)
	}
	request, err := repository.ClaimOutbox(ctx, "client-image-relay", time.Minute)
	if err != nil || request == nil || request.Topic != ProcessingTopic {
		t.Fatalf("claim processing request = %#v, %v", request, err)
	}
	if err := repository.MarkOutboxPublished(ctx, *request); err != nil {
		t.Fatalf("publish processing request: %v", err)
	}
	message := ProcessingMessage{
		EventID: request.EventID, BodySHA256: request.BodySHA256, Topic: ProcessingTopic,
		EventType: "media.processing.request.v1", AggregateType: "PROCESSING_JOB",
		AggregateID: request.RecordKey, AggregateVersion: 1, RecordKey: request.RecordKey,
		CorrelationID: finalize.CorrelationID, ExpectedMediaID: mediaID, ExpectedWarehouseID: warehouseID,
		ExpectedKind: media.KindImage, ExpectedProcessingKind: media.ProcessingInitial,
		ExpectedGeneration: 1, ExpectedRotation: media.Rotation0,
		ExpectedSourceVersionID: finalizeParts[2].ObjectVersionID,
	}
	claim, err := repository.ClaimProcessingJob(ctx, message, "client-image-worker", time.Minute)
	if err != nil || claim.Duplicate || claim.Job.UploadMode != UploadModeImageVariants ||
		len(claim.Job.ImageVariants) != 3 || claim.Job.SourceSizeBytes != parts[2].ContentLength {
		t.Fatalf("ClaimProcessingJob() = %#v, %v", claim, err)
	}
	processed := clientImageVariantsForJob(t, claim.Job)
	if err := repository.CompleteProcessingJob(ctx, claim.Job, processed); err != nil {
		t.Fatalf("CompleteProcessingJob(): %v", err)
	}
	var variantRows int
	var originalKey, largeKey string
	if err := database.Pool.QueryRow(ctx, `select count(*),
		max(object_key) filter (where variant='ORIGINAL'),
		max(object_key) filter (where variant='LARGE')
		from media_variant where media_id=$1 and generation=1`, mediaID).
		Scan(&variantRows, &originalKey, &largeKey); err != nil {
		t.Fatalf("read completed variants: %v", err)
	}
	if variantRows != 4 || originalKey != parts[2].ObjectKey || largeKey != parts[2].ObjectKey {
		t.Fatalf("completed variants rows=%d original=%q large=%q", variantRows, originalKey, largeKey)
	}
}

func clientImageVariantsForJob(t *testing.T, job WorkerJob) []media.ProcessedVariant {
	t.Helper()
	variants := make([]media.ProcessedVariant, 0, 4)
	var large *media.ProcessedVariant
	for _, part := range job.ImageVariants {
		variant := media.ProcessedVariant{
			Variant: part.Variant, ObjectKey: part.ObjectKey, ObjectVersionID: part.ObjectVersionID,
			ContentType: "image/webp", SizeBytes: part.ContentLength,
			Width: part.Width, Height: part.Height, ChecksumSHA256: part.ChecksumSHA256,
		}
		variants = append(variants, variant)
		if part.Variant == media.VariantLarge {
			copyOfLarge := variant
			large = &copyOfLarge
		}
	}
	if large == nil {
		t.Fatal("client image job has no LARGE part")
	}
	original := *large
	original.Variant = media.VariantOriginal
	return append([]media.ProcessedVariant{original}, variants...)
}
