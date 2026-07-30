package persistence

import (
	"context"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/assetimport"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestAssetImportCreatesDurableMediaAggregateAndProcessingOutboxIntegration(t *testing.T) {
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

	warehouseID := uuid.MustParse("00000000-0000-0000-0000-000000000001")
	cabinID := uuid.MustParse("51000000-0000-4000-8000-000000000001")
	jobID, assetImportID, sourceRowID := uuid.New(), uuid.New(), uuid.New()
	source := assetimport.Source{SourceRowID: sourceRowID, PublicKey: "AbCdEfGhIjKlMn"}
	job, replayed, err := repository.CreateAssetImport(ctx, assetimport.CreateCommand{
		JobID: jobID, AssetImportID: assetImportID, WarehouseID: warehouseID,
		IdempotencyKey: uuid.New(), RequestSHA256: assetimport.CanonicalPreflightSHA(assetImportID, warehouseID, []assetimport.Source{source}),
		Sources: []assetimport.Source{source},
	})
	if err != nil || replayed || job.ID != jobID || job.Status != assetimport.StatusPreflightPending {
		t.Fatalf("CreateAssetImport() = %#v replayed=%v error=%v", job, replayed, err)
	}

	preflight, claimed, err := repository.ClaimAssetImport(ctx, "asset-import-integration", time.Minute)
	if err != nil || !claimed || preflight.Job.ID != jobID || preflight.Job.Status != assetimport.StatusPreflightRunning {
		t.Fatalf("ClaimAssetImport(preflight) = %#v claimed=%v error=%v", preflight, claimed, err)
	}
	entryID := uuid.New()
	size := int64(4)
	if err := repository.CompleteAssetImportPreflight(ctx, jobID, preflight.LeaseToken, []assetimport.DiscoveredEntry{{
		ID: entryID, SourceRowID: sourceRowID, ResourcePath: "disk:/cabin/photo.jpg", FileName: "photo.jpg",
		ContentType: "image/jpeg", SizeBytes: &size, Status: assetimport.EntryPrepared,
	}}); err != nil {
		t.Fatalf("CompleteAssetImportPreflight() error = %v", err)
	}

	job, replayed, err = repository.ActivateAssetImport(ctx, assetimport.ActivateCommand{
		JobID: jobID, IdempotencyKey: uuid.New(),
		RequestSHA256: assetimport.CanonicalActivationSHA(jobID, []assetimport.ActivationBinding{{SourceRowID: sourceRowID, CabinID: cabinID}}),
		Bindings:      []assetimport.ActivationBinding{{SourceRowID: sourceRowID, CabinID: cabinID}},
	})
	if err != nil || replayed || job.Status != assetimport.StatusActivationPending {
		t.Fatalf("ActivateAssetImport() = %#v replayed=%v error=%v", job, replayed, err)
	}
	activation, claimed, err := repository.ClaimAssetImport(ctx, "asset-import-integration", time.Minute)
	if err != nil || !claimed || activation.Job.Status != assetimport.StatusActivationRunning {
		t.Fatalf("ClaimAssetImport(activation) = %#v claimed=%v error=%v", activation, claimed, err)
	}
	if err := repository.SetAssetImportEntryStatus(
		ctx, jobID, activation.LeaseToken, entryID, assetimport.EntryDownloading, ""); err != nil {
		t.Fatalf("SetAssetImportEntryStatus(DOWNLOADING) error = %v", err)
	}

	mediaID := uuid.New()
	objectKey := media.IngressObjectKey(mediaID.String(), ".jpg")
	command := assetimport.ImportAssetCommand{
		JobID: jobID, LeaseToken: activation.LeaseToken, EntryID: entryID, MediaID: mediaID,
		WarehouseID: warehouseID, CabinID: cabinID, FileName: "photo.jpg", ContentType: "image/jpeg",
		SizeBytes: size, ChecksumSHA256: hex64('a'), ObjectKey: objectKey, ObjectVersionID: "first-version",
		ETag: "first-etag", CorrelationID: uuid.New(),
	}
	if result, importErr := repository.ImportAsset(ctx, command); importErr != nil || result.MediaID != mediaID {
		t.Fatalf("ImportAsset() = %#v, %v", result, importErr)
	}
	// An object-store client can receive an ambiguous response after the database
	// commit and upload another immutable version on retry. The media aggregate
	// remains idempotent by its deterministic media/object identity and checksum.
	command.ObjectVersionID, command.ETag = "retry-version", "retry-etag"
	if result, importErr := repository.ImportAsset(ctx, command); importErr != nil || result.MediaID != mediaID {
		t.Fatalf("ImportAsset(ambiguous retry) = %#v, %v", result, importErr)
	}
	if err := repository.CompleteAssetImportActivation(ctx, jobID, activation.LeaseToken); err != nil {
		t.Fatalf("CompleteAssetImportActivation() error = %v", err)
	}

	completed, err := repository.GetAssetImport(ctx, jobID)
	if err != nil || completed.Status != assetimport.StatusCompleted || len(completed.Entries) != 1 ||
		completed.Entries[0].Status != assetimport.EntryImported || completed.Entries[0].MediaID == nil ||
		*completed.Entries[0].MediaID != mediaID {
		t.Fatalf("GetAssetImport(completed) = %#v, %v", completed, err)
	}
	var status, sourceVersion string
	var currentGeneration, pendingGeneration int
	if err := database.Pool.QueryRow(ctx, `select processing_status,source_version_id,current_generation,pending_generation
		from media_asset where media_id=$1`, mediaID).Scan(&status, &sourceVersion, &currentGeneration, &pendingGeneration); err != nil ||
		status != "PROCESSING" || sourceVersion != "first-version" || currentGeneration != 0 || pendingGeneration != 1 {
		t.Fatalf("imported media aggregate = status:%s source:%s current:%d pending:%d error=%v", status, sourceVersion, currentGeneration, pendingGeneration, err)
	}
	var processingJobs, facts, processingRequests int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_processing_job where media_id=$1`, mediaID).Scan(&processingJobs); err != nil {
		t.Fatalf("count processing jobs: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_transport_outbox
		where aggregate_id=$1 and event_type='media.media.uploaded.v1'`, mediaID).Scan(&facts); err != nil {
		t.Fatalf("count uploaded facts: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_transport_outbox
		where event_type='media.processing.request.v1'`).Scan(&processingRequests); err != nil {
		t.Fatalf("count processing requests: %v", err)
	}
	if processingJobs != 1 || facts != 1 || processingRequests != 1 {
		t.Fatalf("normal media runtime records = jobs:%d facts:%d requests:%d", processingJobs, facts, processingRequests)
	}

	otherJobID, otherAssetImportID := uuid.New(), uuid.New()
	otherJob, otherReplayed, otherErr := repository.CreateAssetImport(ctx, assetimport.CreateCommand{
		JobID: otherJobID, AssetImportID: otherAssetImportID, WarehouseID: warehouseID, IdempotencyKey: uuid.New(),
		RequestSHA256: assetimport.CanonicalPreflightSHA(otherAssetImportID, warehouseID, []assetimport.Source{source}),
		Sources:       []assetimport.Source{source},
	})
	if otherErr != nil || otherReplayed || otherJob.ID != otherJobID {
		t.Fatalf("independent same-link import = %#v replayed=%v error=%v", otherJob, otherReplayed, otherErr)
	}
	otherPreflight, otherClaimed, otherErr := repository.ClaimAssetImport(ctx, "asset-import-integration", time.Minute)
	if otherErr != nil || !otherClaimed || otherPreflight.Job.ID != otherJobID {
		t.Fatalf("ClaimAssetImport(independent preflight) = %#v claimed=%v error=%v", otherPreflight, otherClaimed, otherErr)
	}
	otherEntryID := uuid.New()
	if otherErr = repository.CompleteAssetImportPreflight(ctx, otherJobID, otherPreflight.LeaseToken, []assetimport.DiscoveredEntry{{
		ID: otherEntryID, SourceRowID: sourceRowID, ResourcePath: "disk:/cabin/photo.jpg", FileName: "photo.jpg",
		ContentType: "image/jpeg", SizeBytes: &size, Status: assetimport.EntryPrepared,
	}}); otherErr != nil {
		t.Fatalf("CompleteAssetImportPreflight(independent) error = %v", otherErr)
	}
	otherCabinID := uuid.MustParse("51000000-0000-4000-8000-000000000002")
	if _, otherReplayed, otherErr = repository.ActivateAssetImport(ctx, assetimport.ActivateCommand{
		JobID: otherJobID, IdempotencyKey: uuid.New(),
		RequestSHA256: assetimport.CanonicalActivationSHA(otherJobID, []assetimport.ActivationBinding{{SourceRowID: sourceRowID, CabinID: otherCabinID}}),
		Bindings:      []assetimport.ActivationBinding{{SourceRowID: sourceRowID, CabinID: otherCabinID}},
	}); otherErr != nil || otherReplayed {
		t.Fatalf("ActivateAssetImport(independent) replayed=%v error=%v", otherReplayed, otherErr)
	}
	otherActivation, otherClaimed, otherErr := repository.ClaimAssetImport(ctx, "asset-import-integration", time.Minute)
	if otherErr != nil || !otherClaimed || otherActivation.Job.ID != otherJobID {
		t.Fatalf("ClaimAssetImport(independent activation) = %#v claimed=%v error=%v", otherActivation, otherClaimed, otherErr)
	}
	if otherErr = repository.FailAssetImport(
		ctx, otherJobID, otherActivation.LeaseToken, assetimport.PhaseActivation, "YANDEX_UNAVAILABLE"); otherErr != nil {
		t.Fatalf("FailAssetImport(independent activation) = %v", otherErr)
	}
	retryKey := uuid.New()
	retried, retryReplayed, retryErr := repository.RetryAssetImport(ctx, assetimport.RetryCommand{
		JobID: otherJobID, IdempotencyKey: retryKey,
		RequestSHA256: assetimport.CanonicalRetrySHA(otherJobID, retryKey),
	})
	if retryErr != nil || retryReplayed || retried.Status != assetimport.StatusActivationPending || retried.ActivationAttempts != 0 {
		t.Fatalf("RetryAssetImport(activation) = %#v replayed=%v error=%v", retried, retryReplayed, retryErr)
	}
	otherActivation, otherClaimed, otherErr = repository.ClaimAssetImport(ctx, "asset-import-integration", time.Minute)
	if otherErr != nil || !otherClaimed || otherActivation.Job.ID != otherJobID || otherActivation.Job.ActivationAttempts != 1 {
		t.Fatalf("ClaimAssetImport(retried activation) = %#v claimed=%v error=%v", otherActivation, otherClaimed, otherErr)
	}
	otherMediaID := uuid.New()
	if result, importErr := repository.ImportAsset(ctx, assetimport.ImportAssetCommand{
		JobID: otherJobID, LeaseToken: otherActivation.LeaseToken, EntryID: otherEntryID, MediaID: otherMediaID,
		WarehouseID: warehouseID, CabinID: otherCabinID, FileName: "photo.jpg", ContentType: "image/jpeg",
		SizeBytes: size, ChecksumSHA256: hex64('a'), ObjectKey: media.IngressObjectKey(otherMediaID.String(), ".jpg"),
		ObjectVersionID: "other-owner-version", ETag: "other-owner-etag", CorrelationID: uuid.New(),
	}); importErr != nil || result.MediaID != otherMediaID {
		t.Fatalf("ImportAsset(independent same link) = %#v, %v", result, importErr)
	}
	if otherErr = repository.CompleteAssetImportActivation(ctx, otherJobID, otherActivation.LeaseToken); otherErr != nil {
		t.Fatalf("CompleteAssetImportActivation(independent) error = %v", otherErr)
	}
	if otherMediaID == mediaID {
		t.Fatal("same public link import reused a media asset across cabin owners")
	}
}

func TestAssetImportReplacementRequeuesOnlyFailedPreflightIntegration(t *testing.T) {
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

	warehouseID, jobID, assetImportID, sourceRowID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	original := assetimport.Source{SourceRowID: sourceRowID, PublicKey: "AbCdEfGhIjKlMn"}
	if _, replayed, err := repository.CreateAssetImport(ctx, assetimport.CreateCommand{
		JobID: jobID, AssetImportID: assetImportID, WarehouseID: warehouseID, IdempotencyKey: uuid.New(),
		RequestSHA256: assetimport.CanonicalPreflightSHA(assetImportID, warehouseID, []assetimport.Source{original}),
		Sources:       []assetimport.Source{original},
	}); err != nil || replayed {
		t.Fatalf("CreateAssetImport() replayed=%v error=%v", replayed, err)
	}
	work, claimed, err := repository.ClaimAssetImport(ctx, "asset-import-replacement", time.Minute)
	if err != nil || !claimed || work.Job.ID != jobID {
		t.Fatalf("ClaimAssetImport() = %#v claimed=%v error=%v", work, claimed, err)
	}
	if err := repository.FailAssetImport(ctx, jobID, work.LeaseToken, assetimport.PhasePreflight, "YANDEX_RESOURCE_REJECTED"); err != nil {
		t.Fatalf("FailAssetImport() error = %v", err)
	}

	replacements := []assetimport.SourceReplacement{{SourceRowID: sourceRowID, PublicKey: "QrStUvWxYz0123"}}
	idempotencyKey := uuid.New()
	command := assetimport.ReplaceSourcesCommand{
		JobID: jobID, IdempotencyKey: idempotencyKey,
		RequestSHA256: assetimport.CanonicalReplaceSourcesSHA(jobID, replacements), Replacements: replacements,
	}
	requeued, replayed, err := repository.ReplaceAssetImportSources(ctx, command)
	if err != nil || replayed || requeued.Status != assetimport.StatusPreflightPending ||
		requeued.FailureCode != "" || requeued.FailurePhase != "" || len(requeued.Sources) != 1 ||
		requeued.Sources[0].PublicKey != "QrStUvWxYz0123" {
		t.Fatalf("ReplaceAssetImportSources() = %#v replayed=%v error=%v", requeued, replayed, err)
	}
	replayedJob, replayed, err := repository.ReplaceAssetImportSources(ctx, command)
	if err != nil || !replayed || replayedJob.Status != assetimport.StatusPreflightPending ||
		len(replayedJob.Sources) != 1 || replayedJob.Sources[0].PublicKey != "QrStUvWxYz0123" {
		t.Fatalf("ReplaceAssetImportSources(replay) = %#v replayed=%v error=%v", replayedJob, replayed, err)
	}
}
