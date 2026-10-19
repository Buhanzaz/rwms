package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/auth"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
)

func TestContractorTaskEvidenceUploadBindsWorkerProofAndFinalizesExactBytes(t *testing.T) {
	entryID, workerID, evidenceID, warehouseID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := contractorEvidenceJPEG()
	checksum := sha256Hex(body)
	repository := newContractorTaskMediaRepositoryStub()
	store := contractorEvidenceStore(body, "image/jpeg", checksum)
	server := newTestServer(t, repository, logisticsValidatorStub(), store)
	request := contractorEvidenceRequest(entryID, workerID, evidenceID, warehouseID, body, "image/jpeg", checksum)
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("upload response = %d %#v %s", response.Code, response.Header(), response.Body.String())
	}
	expectedMediaID := contractorTaskEvidenceMediaID(entryID, workerID, evidenceID)
	var receipt contractorTaskEvidenceReceipt
	if err := json.Unmarshal(response.Body.Bytes(), &receipt); err != nil {
		t.Fatalf("decode receipt: %v", err)
	}
	if receipt.MediaID != expectedMediaID || receipt.Generation != 0 || receipt.Status != media.StatusProcessing {
		t.Fatalf("receipt = %#v", receipt)
	}
	command := repository.createCommand
	if repository.createCalls != 1 || command.MediaID != expectedMediaID || command.FolderID != expectedMediaID ||
		command.OwnerType != persistence.OwnerTypeTaskBoardEntry || command.OwnerID != entryID.String() ||
		command.WarehouseID != warehouseID || command.ClientReferenceID == nil || *command.ClientReferenceID != evidenceID ||
		command.SubjectID != workerID || command.PrincipalType != persistence.PrincipalTypeWorker ||
		command.WorkerID == nil || *command.WorkerID != workerID || command.Actor.SubjectID != workerID ||
		command.Actor.PrincipalType != persistence.PrincipalTypeWorker || command.IdempotencyKey != evidenceID ||
		command.ContentType != "image/jpeg" || command.ContentLength != int64(len(body)) ||
		command.ChecksumSHA256 != checksum || command.Kind != media.KindImage || command.UploadMode != persistence.UploadModeSource {
		t.Fatalf("create command = %#v", command)
	}
	if repository.finalizeCalls != 1 || repository.finalizeCommand.WorkerID == nil ||
		*repository.finalizeCommand.WorkerID != workerID || repository.finalizeCommand.IdempotencyKey != evidenceID ||
		repository.finalizeCommand.ChecksumSHA256 != checksum || repository.finalizeCommand.SizeBytes != int64(len(body)) {
		t.Fatalf("finalize command = %#v", repository.finalizeCommand)
	}
	if store.putCalls != 1 || !bytes.Equal(store.putBody, body) || store.putChecksum != checksum ||
		store.putType != "image/jpeg" || store.putKey != command.SourceObjectKey ||
		strings.Contains(store.putKey, entryID.String()) || strings.Contains(store.putKey, workerID.String()) ||
		strings.Contains(store.putKey, evidenceID.String()) {
		t.Fatalf("storage ingress = calls:%d key:%q type:%q checksum:%q body:%x",
			store.putCalls, store.putKey, store.putType, store.putChecksum, store.putBody)
	}
	for _, forbidden := range []string{"objectKey", "uploadSession", "token", "http://", "https://", store.putKey} {
		if strings.Contains(response.Body.String(), forbidden) {
			t.Fatalf("receipt leaked %q: %s", forbidden, response.Body.String())
		}
	}
}

func TestContractorTaskEvidenceUploadExactReplayDoesNotWriteAnotherObject(t *testing.T) {
	entryID, workerID, evidenceID, warehouseID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := contractorEvidenceJPEG()
	checksum := sha256Hex(body)
	repository := newContractorTaskMediaRepositoryStub()
	repository.replay = true
	repository.completed = true
	repository.replayGeneration = 3
	store := &storeStub{}
	server := newTestServer(t, repository, logisticsValidatorStub(), store)
	request := contractorEvidenceRequest(entryID, workerID, evidenceID, warehouseID, body, "image/jpeg", checksum)
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("replay response = %d %#v %s", response.Code, response.Header(), response.Body.String())
	}
	var receipt contractorTaskEvidenceReceipt
	if err := json.Unmarshal(response.Body.Bytes(), &receipt); err != nil {
		t.Fatalf("decode replay receipt: %v", err)
	}
	if receipt.MediaID != contractorTaskEvidenceMediaID(entryID, workerID, evidenceID) ||
		receipt.Generation != 3 || receipt.Status != media.StatusReady {
		t.Fatalf("replay receipt = %#v", receipt)
	}
	if repository.createCalls != 1 || repository.finalizeCalls != 0 || store.putCalls != 0 ||
		store.statCalls != 0 || store.getCalls != 0 {
		t.Fatalf("replay effects = create:%d finalize:%d put:%d stat:%d get:%d",
			repository.createCalls, repository.finalizeCalls, store.putCalls, store.statCalls, store.getCalls)
	}
}

func TestContractorTaskEvidenceUploadRejectsNonExactAuthorityAndMetadataBeforeEffects(t *testing.T) {
	entryID, workerID, evidenceID, warehouseID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := contractorEvidenceJPEG()
	checksum := sha256Hex(body)
	tests := []struct {
		name       string
		validator  validatorStub
		mutate     func(*http.Request)
		wantStatus int
		wantCode   string
	}{
		{name: "unauthorized", validator: validatorStub{serviceErr: auth.ErrUnauthorized},
			wantStatus: http.StatusUnauthorized, wantCode: "MEDIA_UNAUTHORIZED"},
		{name: "wrong service", validator: validatorStub{servicePrincipal: auth.ServicePrincipal{
			Subject: "task-board-service", ClientID: "task-board-service", Scopes: map[string]struct{}{"media.logistics": {}},
		}}, wantStatus: http.StatusForbidden, wantCode: "MEDIA_FORBIDDEN"},
		{name: "extra scope", validator: validatorStub{servicePrincipal: auth.ServicePrincipal{
			Subject: "logistics-service", ClientID: "logistics-service",
			Scopes: map[string]struct{}{"media.logistics": {}, "media.asset": {}},
		}}, wantStatus: http.StatusForbidden, wantCode: "MEDIA_FORBIDDEN"},
		{name: "idempotency differs from evidence", validator: logisticsValidatorStub(), mutate: func(request *http.Request) {
			request.Header.Set("Idempotency-Key", uuid.NewString())
		}, wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_IDEMPOTENCY_KEY"},
		{name: "unsupported png", validator: logisticsValidatorStub(), mutate: func(request *http.Request) {
			request.Header.Set("Content-Type", "image/png")
		}, wantStatus: http.StatusUnsupportedMediaType, wantCode: "MEDIA_UNSUPPORTED_TYPE"},
		{name: "uppercase checksum", validator: logisticsValidatorStub(), mutate: func(request *http.Request) {
			request.Header.Set("X-Content-SHA256", strings.ToUpper(checksum))
		}, wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_REQUEST"},
		{name: "unknown content length", validator: logisticsValidatorStub(), mutate: func(request *http.Request) {
			request.ContentLength = -1
		}, wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_REQUEST"},
		{name: "ambiguous warehouse", validator: logisticsValidatorStub(), mutate: func(request *http.Request) {
			query := request.URL.Query()
			query.Add("warehouseId", uuid.NewString())
			request.URL.RawQuery = query.Encode()
		}, wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_REQUEST"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			repository := newContractorTaskMediaRepositoryStub()
			store := &storeStub{}
			server := newTestServer(t, repository, test.validator, store)
			request := contractorEvidenceRequest(entryID, workerID, evidenceID, warehouseID, body, "image/jpeg", checksum)
			if test.mutate != nil {
				test.mutate(request)
			}
			response := httptest.NewRecorder()

			server.Handler().ServeHTTP(response, request)

			if response.Code != test.wantStatus || !strings.Contains(response.Body.String(), `"code":"`+test.wantCode+`"`) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if repository.createCalls != 0 || repository.finalizeCalls != 0 || store.putCalls != 0 || store.getCalls != 0 {
				t.Fatalf("rejected request effects = create:%d finalize:%d put:%d get:%d",
					repository.createCalls, repository.finalizeCalls, store.putCalls, store.getCalls)
			}
		})
	}
}

func TestContractorTaskEvidenceUploadRejectsBodyChecksumMismatchWithoutFinalize(t *testing.T) {
	entryID, workerID, evidenceID, warehouseID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := contractorEvidenceJPEG()
	declaredChecksum := strings.Repeat("a", 64)
	repository := newContractorTaskMediaRepositoryStub()
	store := contractorEvidenceStore(body, "image/jpeg", declaredChecksum)
	server := newTestServer(t, repository, logisticsValidatorStub(), store)
	request := contractorEvidenceRequest(entryID, workerID, evidenceID, warehouseID, body, "image/jpeg", declaredChecksum)
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusConflict || !strings.Contains(response.Body.String(), `"code":"MEDIA_OBJECT_MISMATCH"`) {
		t.Fatalf("mismatch response = %d %s", response.Code, response.Body.String())
	}
	if repository.createCalls != 1 || repository.finalizeCalls != 0 || store.putCalls != 1 || store.statCalls != 0 {
		t.Fatalf("mismatch effects = create:%d finalize:%d put:%d stat:%d",
			repository.createCalls, repository.finalizeCalls, store.putCalls, store.statCalls)
	}
}

func TestContractorTaskEvidenceUploadFailsClosedWhenWorkerProofIsRevokedBeforeFinalize(t *testing.T) {
	entryID, workerID, evidenceID, warehouseID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := contractorEvidenceJPEG()
	checksum := sha256Hex(body)
	repository := newContractorTaskMediaRepositoryStub()
	repository.finalizeErr = persistence.ErrOwnerProofMissing
	store := contractorEvidenceStore(body, "image/jpeg", checksum)
	server := newTestServer(t, repository, logisticsValidatorStub(), store)
	request := contractorEvidenceRequest(entryID, workerID, evidenceID, warehouseID, body, "image/jpeg", checksum)
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusForbidden ||
		!strings.Contains(response.Body.String(), `"code":"MEDIA_OWNER_PROOF_REQUIRED"`) {
		t.Fatalf("revoked-proof response = %d %s", response.Code, response.Body.String())
	}
	if repository.createCalls != 1 || repository.finalizeCalls != 1 || store.putCalls != 1 ||
		store.statCalls != 1 || store.getCalls != 1 {
		t.Fatalf("revoked-proof effects = create:%d finalize:%d put:%d stat:%d get:%d",
			repository.createCalls, repository.finalizeCalls, store.putCalls, store.statCalls, store.getCalls)
	}
}

func TestContractorTaskExecutionVariantStreamsExactWorkerScopedGeneration(t *testing.T) {
	entryID, workerID, warehouseID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte("derived-webp")
	repository := newContractorTaskMediaRepositoryStub()
	repository.readAsset = persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeTaskBoardEntry, OwnerID: entryID.String(),
		WarehouseID: warehouseID, Kind: media.KindImage, FileName: "private-original-name.jpg",
	}
	repository.readVariant = &persistence.VariantRecord{
		Variant: media.VariantMedium, ObjectKey: "private/task/object.webp", ObjectVersionID: "immutable-v7",
		ContentType: "image/webp", SizeBytes: int64(len(body)),
	}
	store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
		VersionID: "immutable-v7", SizeBytes: int64(len(body)), ContentType: "image/webp",
	}}
	server := newTestServer(t, repository, logisticsValidatorStub(), store)
	path := contractorTaskVariantPath(entryID, workerID, warehouseID, mediaID, 7, media.VariantMedium)
	request := httptest.NewRequest(http.MethodGet, path, nil)
	request.Header.Set("Authorization", "Bearer logistics")
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "private, no-store" ||
		response.Header().Get("Content-Type") != "image/webp" || response.Header().Get("X-Content-Type-Options") != "nosniff" ||
		!bytes.Equal(response.Body.Bytes(), body) {
		t.Fatalf("variant response = %d %#v %q", response.Code, response.Header(), response.Body.Bytes())
	}
	if repository.readCalls != 1 || repository.readEntryID != entryID || repository.readWorkerID != workerID ||
		repository.readWarehouseID != warehouseID || repository.readMediaID != mediaID ||
		repository.readGeneration != 7 || repository.readRequestedVariant != media.VariantMedium {
		t.Fatalf("variant read = %#v", repository)
	}
	if store.getCalls != 1 || store.getKey != repository.readVariant.ObjectKey ||
		store.getVersion != repository.readVariant.ObjectVersionID {
		t.Fatalf("storage read = calls:%d key:%q version:%q", store.getCalls, store.getKey, store.getVersion)
	}
	for _, forbidden := range []string{"private-original-name", repository.readVariant.ObjectKey, mediaID.String()} {
		if strings.Contains(response.Header().Get("Content-Disposition"), forbidden) {
			t.Fatalf("content disposition leaked %q: %q", forbidden, response.Header().Get("Content-Disposition"))
		}
	}
}

func TestContractorTaskExecutionVariantFoldsWorkerProofMismatchIntoNotFound(t *testing.T) {
	entryID, workerID, warehouseID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := newContractorTaskMediaRepositoryStub()
	repository.readErr = persistence.ErrOwnerProofMissing
	store := &storeStub{}
	server := newTestServer(t, repository, logisticsValidatorStub(), store)
	request := httptest.NewRequest(http.MethodGet,
		contractorTaskVariantPath(entryID, workerID, warehouseID, mediaID, 1, media.VariantSmall), nil)
	request.Header.Set("Authorization", "Bearer logistics")
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusNotFound || !strings.Contains(response.Body.String(), `"code":"MEDIA_NOT_FOUND"`) {
		t.Fatalf("mismatch response = %d %s", response.Code, response.Body.String())
	}
	if repository.readCalls != 1 || store.getCalls != 0 {
		t.Fatalf("mismatch effects = reads:%d storage:%d", repository.readCalls, store.getCalls)
	}
}

func TestContractorTaskExecutionRoutesRejectWrongMethods(t *testing.T) {
	entryID, workerID, evidenceID, warehouseID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	server := newTestServer(t, newContractorTaskMediaRepositoryStub(), logisticsValidatorStub(), &storeStub{})
	path := "/api/internal/media/v1/logistics/contractor-task-executions/" + entryID.String() +
		"/workers/" + workerID.String() + "/evidence/" + evidenceID.String() + "?warehouseId=" + warehouseID.String()
	request := httptest.NewRequest(http.MethodPut, path, nil)
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusMethodNotAllowed || !strings.Contains(response.Body.String(), `"code":"MEDIA_METHOD_NOT_ALLOWED"`) {
		t.Fatalf("wrong-method response = %d %s", response.Code, response.Body.String())
	}
}

// contractorTaskMediaRepositoryStub isolates the new service boundary while
// the embedded shared stub supplies every unchanged media repository method.
type contractorTaskMediaRepositoryStub struct {
	*repositoryStub
	createCalls          int
	createCommand        persistence.CreateUploadCommand
	createErr            error
	replay               bool
	completed            bool
	replayGeneration     int
	currentAsset         persistence.AssetRecord
	finalizeCalls        int
	finalizeCommand      persistence.FinalizeCommand
	finalizeErr          error
	readCalls            int
	readEntryID          uuid.UUID
	readWarehouseID      uuid.UUID
	readWorkerID         uuid.UUID
	readMediaID          uuid.UUID
	readGeneration       int
	readRequestedVariant media.Variant
	readAsset            persistence.AssetRecord
	readVariant          *persistence.VariantRecord
	readErr              error
}

func newContractorTaskMediaRepositoryStub() *contractorTaskMediaRepositoryStub {
	return &contractorTaskMediaRepositoryStub{repositoryStub: &repositoryStub{}}
}

func (stub *contractorTaskMediaRepositoryStub) CreateUpload(
	_ context.Context,
	command persistence.CreateUploadCommand,
) (persistence.AssetRecord, bool, error) {
	stub.createCalls++
	stub.createCommand = command
	if stub.createErr != nil {
		return persistence.AssetRecord{}, false, stub.createErr
	}
	completedAt := (*time.Time)(nil)
	status := media.StatusUploading
	generation := 0
	if stub.completed {
		now := time.Now().UTC()
		completedAt = &now
		status = media.StatusReady
		generation = stub.replayGeneration
	}
	clientReferenceID := *command.ClientReferenceID
	actor := command.Actor
	stub.currentAsset = persistence.AssetRecord{
		ID: command.MediaID, FolderID: command.FolderID, ClientReferenceID: &clientReferenceID,
		OwnerType: command.OwnerType, OwnerID: command.OwnerID, WarehouseID: command.WarehouseID,
		Kind: command.Kind, FileName: command.FileName, ContentType: command.ContentType,
		SourceObjectKey: command.SourceObjectKey, Status: status, Version: 1, Generation: generation,
		SortOrder: command.SortOrder, UploadSessionID: command.UploadSessionID,
		UploadExpiresAt: command.UploadExpiresAt, ExpectedLength: command.ContentLength,
		ExpectedChecksum: command.ChecksumSHA256, UploadMode: command.UploadMode,
		UploadCompletedAt: completedAt, CreatedBy: &actor,
	}
	return stub.currentAsset, stub.replay, nil
}

func (stub *contractorTaskMediaRepositoryStub) UploadSessionForPrincipal(
	_ context.Context,
	_, _ uuid.UUID,
	_ string,
) (persistence.AssetRecord, error) {
	return stub.currentAsset, nil
}

func (stub *contractorTaskMediaRepositoryStub) FinalizeUpload(
	_ context.Context,
	command persistence.FinalizeCommand,
) (persistence.AssetRecord, bool, error) {
	stub.finalizeCalls++
	stub.finalizeCommand = command
	if stub.finalizeErr != nil {
		return persistence.AssetRecord{}, false, stub.finalizeErr
	}
	stub.currentAsset.Status = media.StatusProcessing
	stub.currentAsset.Generation = 0
	now := time.Now().UTC()
	stub.currentAsset.UploadCompletedAt = &now
	return stub.currentAsset, false, nil
}

func (stub *contractorTaskMediaRepositoryStub) ReadTaskBoardEntryVariantForWorker(
	_ context.Context,
	entryID, warehouseID, workerID, mediaID uuid.UUID,
	generation int,
	variant media.Variant,
	consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	stub.readCalls++
	stub.readEntryID, stub.readWarehouseID, stub.readWorkerID = entryID, warehouseID, workerID
	stub.readMediaID, stub.readGeneration, stub.readRequestedVariant = mediaID, generation, variant
	if stub.readErr != nil {
		return stub.readErr
	}
	if stub.readAsset.ID == uuid.Nil || stub.readVariant == nil {
		return errors.New("unexpected contractor task media read")
	}
	return consume(stub.readAsset, stub.readVariant)
}

func contractorEvidenceRequest(
	entryID, workerID, evidenceID, warehouseID uuid.UUID,
	body []byte,
	contentType, checksum string,
) *http.Request {
	path := "/api/internal/media/v1/logistics/contractor-task-executions/" + entryID.String() +
		"/workers/" + workerID.String() + "/evidence/" + evidenceID.String() +
		"?warehouseId=" + warehouseID.String()
	request := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer logistics")
	request.Header.Set("Idempotency-Key", evidenceID.String())
	request.Header.Set("Content-Type", contentType)
	request.Header.Set("X-Content-SHA256", checksum)
	return request
}

func contractorTaskVariantPath(
	entryID, workerID, warehouseID, mediaID uuid.UUID,
	generation int,
	variant media.Variant,
) string {
	return "/api/internal/media/v1/logistics/contractor-task-executions/" + entryID.String() +
		"/workers/" + workerID.String() + "/assets/" + mediaID.String() +
		"/generations/" + strconv.Itoa(generation) + "/variants/" + string(variant) +
		"/content?warehouseId=" + warehouseID.String()
}

func contractorEvidenceJPEG() []byte {
	return []byte{0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01}
}

func contractorEvidenceStore(body []byte, contentType, checksum string) *storeStub {
	metadata := media.ObjectMetadata{
		VersionID: "immutable-evidence-v1", ETag: "evidence-etag", SizeBytes: int64(len(body)),
		ContentType: contentType, UserMetadata: map[string]string{"sha256": checksum},
	}
	return &storeStub{putMetadata: metadata, statMetadata: metadata, objectBody: body}
}

func sha256Hex(body []byte) string {
	digest := sha256.Sum256(body)
	return hex.EncodeToString(digest[:])
}
