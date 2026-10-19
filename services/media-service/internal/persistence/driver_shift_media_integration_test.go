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

func TestValidateDriverShiftOwnerProofRejectsAudienceBeyondCanonicalSingleDriver(t *testing.T) {
	warehouseID, shiftID := uuid.New(), uuid.New()
	message := driverShiftOwnerProofMessage(t, shiftID, warehouseID, 0, true,
		[]uuid.UUID{uuid.New(), uuid.New()}, []uuid.UUID{uuid.New()})
	if err := validateDriverShiftOwnerProofMessage(message); !errors.Is(err, ErrConflict) {
		t.Fatalf("oversized driver-shift proof validation error = %v, want conflict", err)
	}
}

func TestDriverShiftProofGatesStableEvidenceAndFailsClosedIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media database: %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	warehouseID, shiftID := uuid.New(), uuid.New()
	driverID, readerID, foreignID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	proof0 := driverShiftOwnerProofMessage(t, shiftID, warehouseID, 0, true,
		[]uuid.UUID{driverID}, []uuid.UUID{driverID})
	if result, applyErr := repository.ApplyDriverShiftOwnerProofMessage(ctx, proof0); applyErr != nil || result.Duplicate || result.Quarantined {
		t.Fatalf("apply driver-shift proof v0 = %#v, %v", result, applyErr)
	}
	if result, applyErr := repository.ApplyDriverShiftOwnerProofMessage(ctx, proof0); applyErr != nil || !result.Duplicate || result.Quarantined {
		t.Fatalf("replay driver-shift proof v0 = %#v, %v", result, applyErr)
	}
	if err := repository.AuthorizeDriverShiftWorker(ctx, shiftID, warehouseID, driverID); err != nil {
		t.Fatalf("authorize shift driver: %v", err)
	}
	if err := repository.AuthorizeDriverShiftWorker(ctx, shiftID, warehouseID, readerID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("reader-only create access = %v, want owner proof missing", err)
	}
	if err := repository.AuthorizeDriverShiftWorker(ctx, shiftID, warehouseID, foreignID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("foreign create access = %v, want owner proof missing", err)
	}

	photoID := uuid.New()
	command := driverShiftEvidenceCommand(shiftID, warehouseID, subjectID, driverID, photoID)
	asset, replayed, err := repository.CreateUpload(ctx, command)
	if err != nil || replayed || asset.OwnerType != OwnerTypeDriverShift ||
		asset.ClientReferenceID == nil || *asset.ClientReferenceID != photoID {
		t.Fatalf("create shift evidence = %#v replayed=%v error=%v", asset, replayed, err)
	}
	retry := command
	retry.MediaID, retry.FolderID, retry.UploadSessionID = uuid.New(), uuid.New(), uuid.New()
	replayedAsset, replayed, err := repository.CreateUpload(ctx, retry)
	if err != nil || !replayed || replayedAsset.ID != asset.ID {
		t.Fatalf("replay shift evidence = %#v replayed=%v error=%v", replayedAsset, replayed, err)
	}
	finalize := FinalizeCommand{
		SessionID: asset.UploadSessionID, SubjectID: subjectID, PrincipalType: PrincipalTypeWorker,
		Actor: ActorReference{SubjectID: driverID, PrincipalType: PrincipalTypeWorker}, WorkerID: &driverID,
		IdempotencyKey: uuid.New(), RequestSHA256: hex64('f'), ObjectVersionID: "shift-source-v1",
		ETag: "shift-etag-v1", ChecksumSHA256: command.ChecksumSHA256, ContentType: "image/jpeg",
		SizeBytes: command.ContentLength, CorrelationID: uuid.New(),
	}
	if _, finalizeReplayed, finalizeErr := repository.FinalizeUpload(ctx, finalize); finalizeErr != nil || finalizeReplayed {
		t.Fatalf("finalize shift evidence replayed=%v error=%v", finalizeReplayed, finalizeErr)
	}
	readyFact := completeWorkerEvidenceAndClaimReadyFact(t, ctx, repository, warehouseID, command, finalize)
	var readyEnvelope map[string]any
	if err := json.Unmarshal(readyFact.Body, &readyEnvelope); err != nil {
		t.Fatalf("decode shift ready fact: %v", err)
	}
	readyPayload, payloadOK := readyEnvelope["payload"].(map[string]any)
	readyActor, actorOK := readyEnvelope["actorRef"].(map[string]any)
	if !payloadOK || !actorOK || readyActor["subjectId"] != driverID.String() ||
		readyActor["principalType"] != PrincipalTypeWorker || readyPayload["ownerType"] != OwnerTypeDriverShift ||
		readyPayload["ownerId"] != shiftID.String() || readyPayload["clientReferenceId"] != photoID.String() {
		t.Fatalf("shift ready fact actor=%#v payload=%#v", readyEnvelope["actorRef"], readyEnvelope["payload"])
	}
	if err := repository.MarkOutboxPublished(ctx, *readyFact); err != nil {
		t.Fatalf("publish shift ready fact: %v", err)
	}
	readCalls := 0
	if err := repository.ReadDriverShiftAssetsForWorker(ctx, shiftID, warehouseID, driverID, 50, nil,
		func(records []AssetWithVariants) error {
			readCalls++
			if len(records) != 1 || records[0].Asset.ID != asset.ID {
				t.Fatalf("shift evidence records = %#v", records)
			}
			return nil
		}); err != nil || readCalls != 1 {
		t.Fatalf("driver shift evidence error=%v calls=%d", err, readCalls)
	}
	if err := repository.ReadDriverShiftAssetsForWorker(ctx, shiftID, warehouseID, readerID, 50, nil,
		func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("foreign read access = %v, want owner proof missing", err)
	}

	proof1 := driverShiftOwnerProofMessage(t, shiftID, warehouseID, 1, false,
		[]uuid.UUID{}, []uuid.UUID{})
	if result, applyErr := repository.ApplyDriverShiftOwnerProofMessage(ctx, proof1); applyErr != nil || result.Quarantined {
		t.Fatalf("apply inactive driver-shift proof v1 = %#v, %v", result, applyErr)
	}
	if err := repository.AuthorizeDriverShiftWorker(ctx, shiftID, warehouseID, driverID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("inactive create access = %v, want owner proof missing", err)
	}
	if _, _, err := repository.FinalizeUpload(ctx, finalize); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("inactive finalize replay = %v, want owner proof missing", err)
	}
	if err := repository.ReadDriverShiftAssetsForWorker(ctx, shiftID, warehouseID, driverID, 50, nil,
		func([]AssetWithVariants) error { return nil }); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("inactive read access = %v, want owner proof missing", err)
	}
}

func TestDriverShiftProofVersionGapQuarantinesOnlyShiftAggregateIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media database: %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)
	warehouseID, shiftID, driverID := uuid.New(), uuid.New(), uuid.New()
	proof0 := driverShiftOwnerProofMessage(t, shiftID, warehouseID, 0, true,
		[]uuid.UUID{driverID}, []uuid.UUID{driverID})
	if _, err := repository.ApplyDriverShiftOwnerProofMessage(ctx, proof0); err != nil {
		t.Fatalf("apply proof v0: %v", err)
	}
	proof2 := driverShiftOwnerProofMessage(t, shiftID, warehouseID, 2, true,
		[]uuid.UUID{driverID}, []uuid.UUID{driverID})
	result, err := repository.ApplyDriverShiftOwnerProofMessage(ctx, proof2)
	if err != nil || !result.Quarantined {
		t.Fatalf("apply gap proof = %#v, %v", result, err)
	}
	if err := repository.AuthorizeDriverShiftWorker(ctx, shiftID, warehouseID, driverID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("quarantined access = %v, want owner proof missing", err)
	}
	var reason string
	if err := database.Pool.QueryRow(ctx, `select reason_code from media_quarantined_aggregate
		where consumer_name=$1 and aggregate_type=$2 and aggregate_id=$3`, DriverShiftOwnerProofConsumer,
		DriverShiftOwnerProofAggregate, shiftID).Scan(&reason); err != nil || reason != "VERSION_GAP" {
		t.Fatalf("driver-shift quarantine reason=%q error=%v", reason, err)
	}
}

func TestDriverShiftProofEventIDConflictCreatesOneBoundedDeadLetterIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("open media database: %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)
	warehouseID, shiftID, driverID := uuid.New(), uuid.New(), uuid.New()
	proof := driverShiftOwnerProofMessage(t, shiftID, warehouseID, 0, true,
		[]uuid.UUID{driverID}, []uuid.UUID{driverID})
	if _, err := repository.ApplyDriverShiftOwnerProofMessage(ctx, proof); err != nil {
		t.Fatalf("apply proof v0: %v", err)
	}
	conflict := proof
	conflict.WireBody = append(append([]byte(nil), proof.WireBody...), '\n')
	sum := sha256.Sum256(conflict.WireBody)
	conflict.BodySHA256 = hex.EncodeToString(sum[:])
	for attempt := 0; attempt < 2; attempt++ {
		result, applyErr := repository.ApplyDriverShiftOwnerProofMessage(ctx, conflict)
		if applyErr != nil || !result.Quarantined {
			t.Fatalf("apply conflicting replay attempt %d = %#v, %v", attempt+1, result, applyErr)
		}
	}
	if err := repository.AuthorizeDriverShiftWorker(ctx, shiftID, warehouseID, driverID); !errors.Is(err, ErrOwnerProofMissing) {
		t.Fatalf("event-ID-conflicted access = %v, want owner proof missing", err)
	}
	var deadLetters, conflicts int
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_dead_letter
		where consumer_name=$1 and event_id=$2 and failure_code='DRIVER_SHIFT_OWNER_EVENT_ID_CONFLICT'
		and body_sha256=$3 and attempt_count=1`, DriverShiftOwnerProofConsumer, proof.EventID,
		conflict.BodySHA256).Scan(&deadLetters); err != nil || deadLetters != 1 {
		t.Fatalf("bounded driver-shift dead letters=%d error=%v", deadLetters, err)
	}
	if err := database.Pool.QueryRow(ctx, `select count(*) from media_driver_shift_owner_proof_conflict
		where consumer_name=$1 and event_id=$2 and incoming_body_sha256=$3`,
		DriverShiftOwnerProofConsumer, proof.EventID, conflict.BodySHA256).Scan(&conflicts); err != nil || conflicts != 1 {
		t.Fatalf("bounded driver-shift conflicts=%d error=%v", conflicts, err)
	}
}

func driverShiftOwnerProofMessage(t *testing.T, shiftID, warehouseID uuid.UUID, version int64, active bool,
	allowed, readers []uuid.UUID,
) DriverShiftOwnerProofMessage {
	t.Helper()
	recordedAt := time.Now().UTC()
	eventID := uuid.New()
	wire, err := json.Marshal(map[string]any{
		"envelopeVersion": 2, "eventId": eventID.String(),
		"eventType": "task-board.driver-shift-owner-proof.changed.v1", "eventVersion": 1,
		"occurredAt": nil, "recordedAt": recordedAt.Format(time.RFC3339Nano), "producer": "task-board-service",
		"aggregateType": DriverShiftOwnerProofAggregate, "aggregateId": shiftID.String(),
		"aggregateVersion": version,
		"correlation":      map[string]any{"correlationId": uuid.NewString(), "causationId": nil},
		"actorRef":         nil,
		"payload": map[string]any{
			"ownerType": OwnerTypeDriverShift, "ownerId": shiftID.String(),
			"warehouseId": warehouseID.String(), "active": active,
			"allowedWorkerIds": allowed, "readerWorkerIds": readers,
		},
	})
	if err != nil {
		t.Fatalf("marshal driver-shift proof: %v", err)
	}
	sum := sha256.Sum256(wire)
	return DriverShiftOwnerProofMessage{
		EventID: eventID, BodySHA256: hex.EncodeToString(sum[:]), WireBody: wire,
		Topic: DriverShiftOwnerProofTopic, EventType: "task-board.driver-shift-owner-proof.changed.v1",
		AggregateType: DriverShiftOwnerProofAggregate, AggregateID: shiftID, AggregateVersion: version,
		RecordKey: shiftID, RecordedAt: recordedAt, WarehouseID: warehouseID, Active: active,
		AllowedWorkerIDs: append([]uuid.UUID(nil), allowed...), ReaderWorkerIDs: append([]uuid.UUID(nil), readers...),
	}
}

func driverShiftEvidenceCommand(shiftID, warehouseID, subjectID, driverID, photoID uuid.UUID) CreateUploadCommand {
	mediaID := uuid.New()
	return CreateUploadCommand{
		MediaID: mediaID, FolderID: mediaID, UploadSessionID: uuid.New(), SubjectID: subjectID,
		PrincipalType: PrincipalTypeWorker, Actor: ActorReference{SubjectID: driverID, PrincipalType: PrincipalTypeWorker},
		WorkerID: &driverID, IdempotencyKey: photoID, RequestSHA256: hex64('a'), OwnerType: OwnerTypeDriverShift,
		OwnerID: shiftID.String(), WarehouseID: warehouseID, ClientReferenceID: &photoID, Kind: media.KindImage,
		FileName: "shift.jpg", ContentType: "image/jpeg", ContentLength: 128, ChecksumSHA256: hex64('b'),
		SortOrder: 0, SourceObjectKey: "media/" + mediaID.String() + "/source/shift.jpg",
		UploadExpiresAt: time.Now().UTC().Add(time.Hour), CorrelationID: uuid.New(),
	}
}
