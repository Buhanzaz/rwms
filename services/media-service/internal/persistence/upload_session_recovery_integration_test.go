package persistence

import (
	"context"
	"errors"
	"os"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestRepositoryCreateUploadReplayRecoversOnlyExpiredUncompletedSessionIntegration(t *testing.T) {
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

	newExpiredUpload := func(t *testing.T) (CreateUploadCommand, AssetRecord) {
		t.Helper()
		warehouseID, ownerID, subjectID := uuid.New(), uuid.New(), uuid.New()
		proof := ValidatedOwnerProof{
			ConsumerName: InventoryOwnerConsumerGroup, EventID: uuid.New(),
			BodySHA256: hex64('1'), AggregateType: InventoryFindingAggregate,
			AggregateID: ownerID, AggregateVersion: 1, OwnerType: OwnerTypeInventoryFinding,
			OwnerID: ownerID.String(), WarehouseID: warehouseID, OwnerRevision: 1,
			Active: true, RecordedAt: time.Now().UTC(),
		}
		if replayed, applyErr := repository.ApplyValidatedOwnerProof(ctx, proof); applyErr != nil || replayed {
			t.Fatalf("ApplyValidatedOwnerProof() = replayed:%v error:%v", replayed, applyErr)
		}
		command := createCommand(ownerID, warehouseID, media.KindImage, 0)
		command.SubjectID = subjectID
		command.PrincipalType = PrincipalTypeUser
		command.Actor = ActorReference{SubjectID: subjectID, PrincipalType: PrincipalTypeUser}
		asset, replayed, createErr := repository.CreateUpload(ctx, command)
		if createErr != nil || replayed {
			t.Fatalf("CreateUpload() = asset:%#v replayed:%v error:%v", asset, replayed, createErr)
		}
		if _, expireErr := database.Pool.Exec(ctx, `
			update media_upload_session
			set expires_at=clock_timestamp()-interval '1 minute'
			where upload_session_id=$1`, asset.UploadSessionID); expireErr != nil {
			t.Fatalf("expire upload session: %v", expireErr)
		}
		return command, asset
	}

	retryCommand := func(command CreateUploadCommand) CreateUploadCommand {
		command.MediaID = uuid.New()
		command.FolderID = uuid.New()
		command.UploadSessionID = uuid.New()
		command.SourceObjectKey = "media/" + command.MediaID.String() + "/source/retry.jpg"
		command.UploadExpiresAt = time.Now().UTC().Add(time.Hour)
		command.CorrelationID = uuid.New()
		return command
	}

	t.Run("exact replay reopens the same logical asset", func(t *testing.T) {
		command, expired := newExpiredUpload(t)
		retry := retryCommand(command)
		recovered, replayed, recoverErr := repository.CreateUpload(ctx, retry)
		if recoverErr != nil || !replayed {
			t.Fatalf("replay expired upload = asset:%#v replayed:%v error:%v", recovered, replayed, recoverErr)
		}
		if recovered.ID != expired.ID || recovered.FolderID != expired.FolderID ||
			recovered.SourceObjectKey != expired.SourceObjectKey || recovered.UploadSessionID != retry.UploadSessionID ||
			recovered.UploadSessionID == expired.UploadSessionID || !time.Now().Before(recovered.UploadExpiresAt) {
			t.Fatalf("recovered asset changed immutable identity or did not receive an open session: %#v", recovered)
		}
		if _, lookupErr := repository.UploadSessionForPrincipal(ctx, expired.UploadSessionID, command.SubjectID, PrincipalTypeUser); !errors.Is(lookupErr, ErrNotFound) {
			t.Fatalf("old upload session error = %v, want ErrNotFound", lookupErr)
		}
		if session, lookupErr := repository.UploadSessionForPrincipal(ctx, recovered.UploadSessionID, command.SubjectID, PrincipalTypeUser); lookupErr != nil || session.ID != expired.ID {
			t.Fatalf("replacement upload session = %#v, %v", session, lookupErr)
		}
		assertSingleRecoveredUploadIdentity(t, ctx, database, expired.ID, command.SubjectID, command.IdempotencyKey)
		finalize := FinalizeCommand{
			SessionID: recovered.UploadSessionID, SubjectID: command.SubjectID, PrincipalType: PrincipalTypeUser,
			Actor:          ActorReference{SubjectID: command.SubjectID, PrincipalType: PrincipalTypeUser},
			IdempotencyKey: uuid.New(), RequestSHA256: hex64('2'), ObjectVersionID: "replacement-version", ETag: "replacement-etag",
			ChecksumSHA256: command.ChecksumSHA256, ContentType: command.ContentType, SizeBytes: command.ContentLength,
			CorrelationID: uuid.New(),
		}
		if finalized, finalizeReplayed, finalizeErr := repository.FinalizeUpload(ctx, finalize); finalizeErr != nil || finalizeReplayed || finalized.Status != media.StatusProcessing {
			t.Fatalf("replacement session FinalizeUpload() = asset:%#v replayed:%v error:%v", finalized, finalizeReplayed, finalizeErr)
		}
	})

	t.Run("completed replay cannot replace finalized content", func(t *testing.T) {
		command, created := newExpiredUpload(t)
		// Restore a valid session solely to prepare a completed asset through the
		// repository; the retry below must still leave that completed session
		// untouched.
		if _, updateErr := database.Pool.Exec(ctx, `
			update media_upload_session set expires_at=clock_timestamp()+interval '1 hour'
			where upload_session_id=$1`, created.UploadSessionID); updateErr != nil {
			t.Fatalf("restore upload session: %v", updateErr)
		}
		finalize := FinalizeCommand{
			SessionID: created.UploadSessionID, SubjectID: command.SubjectID, PrincipalType: PrincipalTypeUser,
			Actor:          ActorReference{SubjectID: command.SubjectID, PrincipalType: PrincipalTypeUser},
			IdempotencyKey: uuid.New(), RequestSHA256: hex64('2'), ObjectVersionID: "version-1", ETag: "etag-1",
			ChecksumSHA256: command.ChecksumSHA256, ContentType: command.ContentType, SizeBytes: command.ContentLength,
			CorrelationID: uuid.New(),
		}
		if asset, replayed, finalizeErr := repository.FinalizeUpload(ctx, finalize); finalizeErr != nil || replayed {
			t.Fatalf("FinalizeUpload() = asset:%#v replayed:%v error:%v", asset, replayed, finalizeErr)
		}
		if _, _, replayErr := repository.CreateUpload(ctx, retryCommand(command)); !errors.Is(replayErr, ErrConflict) {
			t.Fatalf("completed CreateUpload replay error = %v, want ErrConflict", replayErr)
		}
		var sessionID uuid.UUID
		var completedAt *time.Time
		var status string
		var sourceVersion string
		if queryErr := database.Pool.QueryRow(ctx, `
			select s.upload_session_id,s.completed_at,a.processing_status,a.source_version_id
			from media_asset a join media_upload_session s on s.media_id=a.media_id
			where a.media_id=$1`, created.ID).Scan(&sessionID, &completedAt, &status, &sourceVersion); queryErr != nil {
			t.Fatalf("read completed upload: %v", queryErr)
		}
		if sessionID != created.UploadSessionID || completedAt == nil || status != string(media.StatusProcessing) || sourceVersion != finalize.ObjectVersionID {
			t.Fatalf("completed replay changed stored content/session: session=%s completed=%v status=%s source=%s", sessionID, completedAt, status, sourceVersion)
		}
	})

	t.Run("different payload with the same key does not reopen the session", func(t *testing.T) {
		command, expired := newExpiredUpload(t)
		mismatch := retryCommand(command)
		mismatch.RequestSHA256 = hex64('9')
		if _, _, replayErr := repository.CreateUpload(ctx, mismatch); !errors.Is(replayErr, ErrIdempotencyMismatch) {
			t.Fatalf("mismatched CreateUpload replay error = %v, want ErrIdempotencyMismatch", replayErr)
		}
		if session, lookupErr := repository.UploadSessionForPrincipal(ctx, expired.UploadSessionID, command.SubjectID, PrincipalTypeUser); lookupErr != nil || session.UploadSessionID != expired.UploadSessionID {
			t.Fatalf("mismatched replay changed expired session: %#v, %v", session, lookupErr)
		}
	})

	t.Run("concurrent exact replays converge on one replacement session", func(t *testing.T) {
		command, expired := newExpiredUpload(t)
		attempts := []CreateUploadCommand{retryCommand(command), retryCommand(command)}
		type result struct {
			asset    AssetRecord
			replayed bool
			err      error
		}
		results := make(chan result, len(attempts))
		var wait sync.WaitGroup
		for _, attempt := range attempts {
			attempt := attempt
			wait.Add(1)
			go func() {
				defer wait.Done()
				asset, replayed, replayErr := repository.CreateUpload(ctx, attempt)
				results <- result{asset: asset, replayed: replayed, err: replayErr}
			}()
		}
		wait.Wait()
		close(results)
		var replacementSessionID uuid.UUID
		for result := range results {
			if result.err != nil || !result.replayed || result.asset.ID != expired.ID {
				t.Fatalf("concurrent replay = asset:%#v replayed:%v error:%v", result.asset, result.replayed, result.err)
			}
			if replacementSessionID == uuid.Nil {
				replacementSessionID = result.asset.UploadSessionID
			} else if replacementSessionID != result.asset.UploadSessionID {
				t.Fatalf("concurrent retries returned different sessions: %s and %s", replacementSessionID, result.asset.UploadSessionID)
			}
		}
		if replacementSessionID == uuid.Nil || (replacementSessionID != attempts[0].UploadSessionID && replacementSessionID != attempts[1].UploadSessionID) {
			t.Fatalf("replacement session %s was not produced by either exact replay", replacementSessionID)
		}
		assertSingleRecoveredUploadIdentity(t, ctx, database, expired.ID, command.SubjectID, command.IdempotencyKey)
	})
}

func assertSingleRecoveredUploadIdentity(t *testing.T, ctx context.Context, database *Database, mediaID, subjectID, idempotencyKey uuid.UUID) {
	t.Helper()
	var assets, sessions, commands, events int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_asset where media_id=$1`, mediaID).Scan(&assets); err != nil {
		t.Fatalf("count media assets: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_upload_session where media_id=$1`, mediaID).Scan(&sessions); err != nil {
		t.Fatalf("count upload sessions: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `
		select count(*) from media_command_idempotency
		where principal_type='USER' and subject_id=$1 and command_type='CREATE_UPLOAD' and idempotency_key=$2 and media_id=$3`,
		subjectID, idempotencyKey, mediaID).Scan(&commands); err != nil {
		t.Fatalf("count create idempotency records: %v", err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_domain_event where aggregate_id=$1`, mediaID).Scan(&events); err != nil {
		t.Fatalf("count media domain events: %v", err)
	}
	if assets != 1 || sessions != 1 || commands != 1 || events != 1 {
		t.Fatalf("recovery duplicated media identity: assets=%d sessions=%d commands=%d events=%d", assets, sessions, commands, events)
	}
}
