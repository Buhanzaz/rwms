package persistence

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/santhosh-tekuri/jsonschema/v6"
)

func TestServiceOwnedOutboxWireBytesMatchCanonicalSchemasIntegration(t *testing.T) {
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

	factSchema := compileWireContractSchema(t, "media-facts-v1.schema.json")
	requestSchema := compileWireContractSchema(t, "media-processing-requests-v1.schema.json")
	dltSchema := compileWireContractSchema(t, "media-processing-dlt-v1.schema.json")

	ownerID := uuid.New()
	warehouseID := uuid.New()
	proof := ValidatedOwnerProof{
		ConsumerName:     InventoryOwnerConsumerGroup,
		EventID:          uuid.New(),
		BodySHA256:       wireHex64('1'),
		AggregateType:    InventoryFindingAggregate,
		AggregateID:      ownerID,
		AggregateVersion: 1,
		OwnerType:        OwnerTypeInventoryFinding,
		OwnerID:          ownerID.String(),
		WarehouseID:      warehouseID,
		OwnerRevision:    1,
		Active:           true,
		RecordedAt:       time.Now().UTC(),
	}
	if replayed, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil || replayed {
		t.Fatalf("ApplyValidatedOwnerProof() = %v, %v; want new proof", replayed, err)
	}

	mediaID := uuid.New()
	create := CreateUploadCommand{
		MediaID:         mediaID,
		UploadSessionID: uuid.New(),
		SubjectID:       uuid.New(),
		IdempotencyKey:  uuid.New(),
		RequestSHA256:   wireHex64('2'),
		OwnerType:       OwnerTypeInventoryFinding,
		OwnerID:         ownerID.String(),
		WarehouseID:     warehouseID,
		Kind:            media.KindImage,
		FileName:        "wire-contract.jpg",
		ContentType:     "image/jpeg",
		ContentLength:   257,
		ChecksumSHA256:  wireHex64('3'),
		SortOrder:       7,
		SourceObjectKey: "media/" + mediaID.String() + "/source/upload.jpg",
		UploadExpiresAt: time.Now().UTC().Add(time.Hour),
		CorrelationID:   uuid.New(),
	}
	if _, replayed, err := repository.CreateUpload(ctx, create); err != nil || replayed {
		t.Fatalf("CreateUpload() = %v, %v; want new upload", replayed, err)
	}
	finalize := FinalizeCommand{
		SessionID:       create.UploadSessionID,
		SubjectID:       create.SubjectID,
		IdempotencyKey:  uuid.New(),
		RequestSHA256:   wireHex64('4'),
		ObjectVersionID: "immutable-minio-version-wire-contract",
		ETag:            "wire-contract-etag",
		ChecksumSHA256:  create.ChecksumSHA256,
		ContentType:     create.ContentType,
		SizeBytes:       create.ContentLength,
		CorrelationID:   uuid.New(),
	}
	finalized, replayed, err := repository.FinalizeUpload(ctx, finalize)
	if err != nil || replayed {
		t.Fatalf("FinalizeUpload() = %#v, %v, %v; want new finalize", finalized, replayed, err)
	}

	fact := readWireOutboxRecord(t, ctx, database.Pool, `
		select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
			record_key,publication_ordinal,depends_on_event_id,wire_body,envelope_sha256,event_status
		from media_transport_outbox
		where aggregate_type='MEDIA' and aggregate_id=$1 and event_type='media.media.uploaded.v1'`, mediaID)
	assertWireRecordHashAndSchema(t, factSchema, fact)
	assertWireRecordMetadata(t, fact, "MEDIA", mediaID, finalized.Version,
		"media.media.uploaded.v1", MediaTopic, mediaID, finalized.Version, nil)
	factEnvelope := decodeWireObject(t, fact.Body)
	assertWireEnvelopeIdentity(t, factEnvelope, fact.EventID, "MEDIA", mediaID, finalized.Version)
	if got := wireString(t, factEnvelope, "eventType"); got != "media.media.uploaded.v1" {
		t.Fatalf("fact envelope eventType = %q", got)
	}
	actor := wireObject(t, factEnvelope, "actorRef")
	if got := wireString(t, actor, "subjectId"); got != create.SubjectID.String() {
		t.Fatalf("fact actor subjectId = %q, want %s", got, create.SubjectID)
	}
	factPayload := wireObject(t, factEnvelope, "payload")
	assertWireUUID(t, factPayload, "mediaId", mediaID)
	assertWireUUID(t, factPayload, "ownerId", ownerID)
	assertWireUUID(t, factPayload, "warehouseId", warehouseID)
	if got := wireString(t, factPayload, "status"); got != string(media.StatusProcessing) {
		t.Fatalf("fact payload status = %q", got)
	}

	request := readWireOutboxRecord(t, ctx, database.Pool, `
		select outbox.event_id,outbox.aggregate_type,outbox.aggregate_id,outbox.aggregate_version,
			outbox.event_type,outbox.topic,outbox.record_key,outbox.publication_ordinal,
			outbox.depends_on_event_id,outbox.wire_body,outbox.envelope_sha256,outbox.event_status
		from media_transport_outbox outbox
		join media_processing_job job on job.processing_job_id=outbox.aggregate_id
		where outbox.aggregate_type='PROCESSING_JOB' and job.media_id=$1
		  and outbox.event_type='media.processing.request.v1'`, mediaID)
	assertWireRecordHashAndSchema(t, requestSchema, request)
	assertWireRecordMetadata(t, request, "PROCESSING_JOB", request.AggregateID, 1,
		"media.processing.request.v1", ProcessingTopic, request.AggregateID, 1, &fact.EventID)
	requestEnvelope := decodeWireObject(t, request.Body)
	assertWireEnvelopeIdentity(t, requestEnvelope, request.EventID, "PROCESSING_JOB", request.AggregateID, 1)
	if requestEnvelope["actorRef"] != nil {
		t.Fatalf("processing request actorRef = %#v, want null", requestEnvelope["actorRef"])
	}
	requestPayload := wireObject(t, requestEnvelope, "payload")
	assertWireUUID(t, requestPayload, "processingJobId", request.AggregateID)
	assertWireUUID(t, requestPayload, "mediaId", mediaID)
	assertWireUUID(t, requestPayload, "warehouseId", warehouseID)
	if got := wireString(t, requestPayload, "sourceVersionId"); got != finalize.ObjectVersionID {
		t.Fatalf("processing request sourceVersionId = %q, want %q", got, finalize.ObjectVersionID)
	}

	markWireRecordPublished(t, ctx, database.Pool, fact.EventID)
	markWireRecordPublished(t, ctx, database.Pool, request.EventID)
	invalidMessage := ProcessingMessage{
		EventID:                 request.EventID,
		BodySHA256:              request.BodySHA256,
		Topic:                   request.Topic,
		EventType:               request.EventType,
		AggregateType:           request.AggregateType,
		AggregateID:             request.AggregateID,
		AggregateVersion:        request.AggregateVersion,
		RecordKey:               request.RecordKey,
		CorrelationID:           finalize.CorrelationID,
		ExpectedMediaID:         mediaID,
		ExpectedWarehouseID:     warehouseID,
		ExpectedKind:            media.KindImage,
		ExpectedProcessingKind:  media.ProcessingInitial,
		ExpectedGeneration:      1,
		ExpectedRotation:        media.Rotation0,
		ExpectedSourceVersionID: finalize.ObjectVersionID,
	}
	if err := repository.RecordInvalidProcessingMessage(ctx, invalidMessage, "INVALID_PROCESSING_REQUEST"); err != nil {
		t.Fatalf("RecordInvalidProcessingMessage() error = %v", err)
	}
	dlt := readWireOutboxRecord(t, ctx, database.Pool, `
		select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
			record_key,publication_ordinal,depends_on_event_id,wire_body,envelope_sha256,event_status
		from media_transport_outbox
		where aggregate_type='PROCESSING_DLT' and aggregate_id=$1
		  and event_type='media.processing.dlt.v1'`, request.AggregateID)
	assertWireRecordHashAndSchema(t, dltSchema, dlt)
	assertWireRecordMetadata(t, dlt, "PROCESSING_DLT", request.AggregateID, 1,
		"media.processing.dlt.v1", ProcessingDLTTopic, request.AggregateID, 1, &request.EventID)
	dltBody := decodeWireObject(t, dlt.Body)
	if len(dltBody) != 3 || wireString(t, dltBody, "failureCode") != "INVALID_PROCESSING_REQUEST" ||
		wireString(t, dltBody, "messageSha256") != request.BodySHA256 {
		t.Fatalf("sanitized DLT body = %#v", dltBody)
	}
	for _, forbidden := range []string{"ownerId", "warehouseId", "objectKey", "sourceVersionId", "actorRef", "payload"} {
		if _, exists := dltBody[forbidden]; exists {
			t.Fatalf("sanitized DLT contains forbidden %q: %#v", forbidden, dltBody)
		}
	}
	markWireRecordPublished(t, ctx, database.Pool, dlt.EventID)
}

type wireOutboxRecord struct {
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
	BodySHA256       string
	Status           string
}

func readWireOutboxRecord(
	t *testing.T,
	ctx context.Context,
	pool *pgxpool.Pool,
	query string,
	arguments ...any,
) wireOutboxRecord {
	t.Helper()
	var record wireOutboxRecord
	if err := pool.QueryRow(ctx, query, arguments...).Scan(
		&record.EventID,
		&record.AggregateType,
		&record.AggregateID,
		&record.AggregateVersion,
		&record.EventType,
		&record.Topic,
		&record.RecordKey,
		&record.Ordinal,
		&record.DependsOn,
		&record.Body,
		&record.BodySHA256,
		&record.Status,
	); err != nil {
		t.Fatalf("read service-owned outbox record: %v", err)
	}
	return record
}

func assertWireRecordHashAndSchema(t *testing.T, schema *jsonschema.Schema, record wireOutboxRecord) {
	t.Helper()
	sum := sha256.Sum256(record.Body)
	if got := hex.EncodeToString(sum[:]); got != record.BodySHA256 {
		t.Fatalf("wire_body SHA-256 = %s, stored %s", got, record.BodySHA256)
	}
	if record.Status != "PENDING" {
		t.Fatalf("new outbox status = %q, want PENDING", record.Status)
	}
	value, err := jsonschema.UnmarshalJSON(bytes.NewReader(record.Body))
	if err != nil {
		t.Fatalf("decode wire_body: %v", err)
	}
	if err := schema.Validate(value); err != nil {
		t.Fatalf("actual wire_body violates canonical schema: %v\nbody: %s", err, record.Body)
	}
	canonical, err := json.Marshal(decodeWireObject(t, record.Body))
	if err != nil {
		t.Fatalf("re-encode wire_body: %v", err)
	}
	if !bytes.Equal(canonical, record.Body) {
		t.Fatalf("wire_body is not the exact compact canonical JSON bytes\nstored: %s\ncanonical: %s", record.Body, canonical)
	}
}

func assertWireRecordMetadata(
	t *testing.T,
	record wireOutboxRecord,
	aggregateType string,
	aggregateID uuid.UUID,
	aggregateVersion int64,
	eventType, topic string,
	recordKey uuid.UUID,
	ordinal int64,
	dependsOn *uuid.UUID,
) {
	t.Helper()
	if record.AggregateType != aggregateType || record.AggregateID != aggregateID ||
		record.AggregateVersion != aggregateVersion || record.EventType != eventType ||
		record.Topic != topic || record.RecordKey != recordKey || record.Ordinal != ordinal {
		t.Fatalf("outbox metadata = %#v", record)
	}
	if dependsOn == nil {
		if record.DependsOn != nil {
			t.Fatalf("outbox dependency = %s, want null", *record.DependsOn)
		}
	} else if record.DependsOn == nil || *record.DependsOn != *dependsOn {
		t.Fatalf("outbox dependency = %v, want %s", record.DependsOn, *dependsOn)
	}
}

func assertWireEnvelopeIdentity(
	t *testing.T,
	envelope map[string]any,
	eventID uuid.UUID,
	aggregateType string,
	aggregateID uuid.UUID,
	aggregateVersion int64,
) {
	t.Helper()
	assertWireUUID(t, envelope, "eventId", eventID)
	assertWireUUID(t, envelope, "aggregateId", aggregateID)
	if got := wireString(t, envelope, "aggregateType"); got != aggregateType {
		t.Fatalf("wire aggregateType = %q, want %q", got, aggregateType)
	}
	if got := wireInteger(t, envelope, "aggregateVersion"); got != aggregateVersion {
		t.Fatalf("wire aggregateVersion = %d, want %d", got, aggregateVersion)
	}
}

func markWireRecordPublished(t *testing.T, ctx context.Context, pool *pgxpool.Pool, eventID uuid.UUID) {
	t.Helper()
	command, err := pool.Exec(ctx, `update media_transport_outbox
		set event_status='PUBLISHED',published_at=clock_timestamp()
		where event_id=$1 and event_status='PENDING'`, eventID)
	if err != nil {
		t.Fatalf("mark isolated outbox record %s published: %v", eventID, err)
	}
	if command.RowsAffected() != 1 {
		t.Fatalf("mark isolated outbox record %s published affected %d rows", eventID, command.RowsAffected())
	}
}

func compileWireContractSchema(t *testing.T, fileName string) *jsonschema.Schema {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller() did not return the persistence test path")
	}
	path := filepath.Join(filepath.Dir(currentFile), "..", "..", "..", "..", "contracts", "events", "media", fileName)
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read JSON schema %s: %v", path, err)
	}
	document, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("decode JSON schema %s: %v", path, err)
	}
	compiler := jsonschema.NewCompiler()
	compiler.AssertFormat()
	if err := compiler.AddResource(path, document); err != nil {
		t.Fatalf("add JSON schema resource %s: %v", path, err)
	}
	schema, err := compiler.Compile(path)
	if err != nil {
		t.Fatalf("compile JSON schema %s: %v", path, err)
	}
	return schema
}

func decodeWireObject(t *testing.T, body []byte) map[string]any {
	t.Helper()
	var value map[string]any
	if err := json.Unmarshal(body, &value); err != nil {
		t.Fatalf("decode wire JSON object: %v", err)
	}
	return value
}

func wireObject(t *testing.T, object map[string]any, name string) map[string]any {
	t.Helper()
	value, ok := object[name].(map[string]any)
	if !ok {
		t.Fatalf("wire %s = %T, want object", name, object[name])
	}
	return value
}

func wireString(t *testing.T, object map[string]any, name string) string {
	t.Helper()
	value, ok := object[name].(string)
	if !ok {
		t.Fatalf("wire %s = %T, want string", name, object[name])
	}
	return value
}

func wireInteger(t *testing.T, object map[string]any, name string) int64 {
	t.Helper()
	value, ok := object[name].(float64)
	if !ok || value != float64(int64(value)) {
		t.Fatalf("wire %s = %#v, want integer", name, object[name])
	}
	return int64(value)
}

func assertWireUUID(t *testing.T, object map[string]any, name string, expected uuid.UUID) {
	t.Helper()
	if got := wireString(t, object, name); got != expected.String() {
		t.Fatalf("wire %s = %q, want %s", name, got, expected)
	}
}

func wireHex64(character byte) string {
	value := make([]byte, 64)
	for index := range value {
		value[index] = character
	}
	return string(value)
}
