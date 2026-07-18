package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/auth"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
)

func TestUnauthenticatedAPIRequestNeverTouchesStorage(t *testing.T) {
	store := &storeStub{}
	server := newTestServer(t, &repositoryStub{}, validatorStub{err: auth.ErrUnauthorized}, store)
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(`{}`))
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d; body=%s", response.Code, http.StatusUnauthorized, response.Body.String())
	}
	if store.ensureCalls != 0 || store.putCalls != 0 || store.statCalls != 0 || store.getCalls != 0 {
		t.Fatalf("unauthenticated storage calls = ensure:%d put:%d stat:%d get:%d",
			store.ensureCalls, store.putCalls, store.statCalls, store.getCalls)
	}
}

func TestUnknownRouteAndWrongMethodUseProblemDetails(t *testing.T) {
	server := newTestServer(t, &repositoryStub{}, validatorStub{err: auth.ErrUnauthorized}, &storeStub{})
	for _, testCase := range []struct {
		name       string
		method     string
		path       string
		wantStatus int
		wantCode   string
	}{
		{name: "unknown", method: http.MethodGet, path: "/does-not-exist", wantStatus: http.StatusNotFound, wantCode: "MEDIA_NOT_FOUND"},
		{name: "wrong method", method: http.MethodPut, path: "/api/media/v1/upload-sessions", wantStatus: http.StatusMethodNotAllowed, wantCode: "MEDIA_METHOD_NOT_ALLOWED"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, httptest.NewRequest(testCase.method, testCase.path, nil))
			if response.Code != testCase.wantStatus || response.Header().Get("Content-Type") != "application/problem+json" ||
				!strings.Contains(response.Body.String(), `"code":"`+testCase.wantCode+`"`) {
				t.Fatalf("response = %d %q %s", response.Code, response.Header().Get("Content-Type"), response.Body.String())
			}
		})
	}
}

func TestCreateUploadReturnsOnlySameOriginContentPath(t *testing.T) {
	warehouseID, ownerID, subjectID := uuid.New(), uuid.New(), uuid.New()
	sessionID, mediaID := uuid.New(), uuid.New()
	repository := &repositoryStub{createAsset: persistence.AssetRecord{
		ID: mediaID, UploadSessionID: sessionID, UploadExpiresAt: time.Now().Add(time.Minute),
	}}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	checksum := strings.Repeat("a", 64)
	body := fmt.Sprintf(`{"ownerType":"INVENTORY_FINDING","ownerId":"%s","warehouseId":"%s","context":"INSPECTION","fileName":"finding.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s","sortOrder":0}`,
		ownerID, warehouseID, checksum)
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
	wantPath := "/api/media/v1/upload-sessions/" + sessionID.String() + "/content"
	if !strings.Contains(response.Body.String(), `"contentUploadUrl":"`+wantPath+`"`) {
		t.Fatalf("same-origin content path missing: %s", response.Body.String())
	}
	for _, forbidden := range []string{`"uploadUrl":`, `"formFields":`, `"sourceObjectKey":`, "minio", "http://", "https://"} {
		if strings.Contains(response.Body.String(), forbidden) {
			t.Fatalf("create response leaked %q: %s", forbidden, response.Body.String())
		}
	}
	if repository.createCalls != 1 || repository.createCommand.OwnerID != ownerID.String() ||
		repository.createCommand.WarehouseID != warehouseID {
		t.Fatalf("create command = %#v, calls=%d", repository.createCommand, repository.createCalls)
	}

	invalidBody := strings.Replace(body, `"INVENTORY_FINDING"`, `"MAINTENANCE_ESTIMATE"`, 1)
	invalidBody = strings.Replace(invalidBody, `"INSPECTION"`, `"ESTIMATE"`, 1)
	invalidRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(invalidBody))
	invalidRequest.Header.Set("Authorization", "Bearer test")
	invalidRequest.Header.Set("Idempotency-Key", uuid.NewString())
	invalidResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(invalidResponse, invalidRequest)
	if invalidResponse.Code != http.StatusBadRequest || repository.createCalls != 1 {
		t.Fatalf("unproved owner response = %d %s; create calls=%d", invalidResponse.Code, invalidResponse.Body.String(), repository.createCalls)
	}
}

func TestUploadContentStreamsFinalizesAndReplaysExactlyOnce(t *testing.T) {
	warehouseID, subjectID, sessionID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte{0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01}
	sum := sha256.Sum256(body)
	checksum := hex.EncodeToString(sum[:])
	asset := persistence.AssetRecord{
		ID: mediaID, WarehouseID: warehouseID, SourceObjectKey: "private/ingress/source.jpg",
		ContentType: "image/jpeg", ExpectedLength: int64(len(body)), ExpectedChecksum: checksum,
		UploadSessionID: sessionID, UploadExpiresAt: time.Now().Add(time.Minute),
	}
	metadata := media.ObjectMetadata{
		SizeBytes: int64(len(body)), ContentType: "image/jpeg", ETag: "etag-1", VersionID: "version-1",
		UserMetadata: map[string]string{"sha256": checksum},
	}
	store := &storeStub{putMetadata: metadata, statMetadata: metadata, objectBody: body}
	repository := &repositoryStub{sessionAsset: asset}
	var acceptedKey uuid.UUID
	var acceptedFingerprint string
	repository.finalizeFunc = func(command persistence.FinalizeCommand) (persistence.AssetRecord, bool, error) {
		if acceptedKey == uuid.Nil {
			acceptedKey, acceptedFingerprint = command.IdempotencyKey, command.RequestSHA256
			completed := time.Now()
			asset.UploadCompletedAt = &completed
			asset.SourceVersionID, asset.SourceETag, asset.SourceChecksum = command.ObjectVersionID, command.ETag, command.ChecksumSHA256
			asset.Status, asset.Version = media.StatusProcessing, 2
			repository.sessionAsset = asset
			return asset, false, nil
		}
		if command.IdempotencyKey != acceptedKey || command.RequestSHA256 != acceptedFingerprint {
			return persistence.AssetRecord{}, false, persistence.ErrConflict
		}
		return asset, true, nil
	}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, store)
	contentKey := uuid.New()
	contentPath := "/api/media/v1/upload-sessions/" + sessionID.String() + "/content"
	doContent := func(key uuid.UUID) *httptest.ResponseRecorder {
		request := httptest.NewRequest(http.MethodPut, contentPath, bytes.NewReader(body))
		request.Header.Set("Authorization", "Bearer test")
		request.Header.Set("Idempotency-Key", key.String())
		request.Header.Set("Content-Type", "image/jpeg")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		return response
	}

	start := make(chan struct{})
	concurrentResponses := make(chan *httptest.ResponseRecorder, 2)
	var wait sync.WaitGroup
	for range 2 {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			concurrentResponses <- doContent(contentKey)
		}()
	}
	close(start)
	wait.Wait()
	close(concurrentResponses)
	var first, replay *httptest.ResponseRecorder
	for candidate := range concurrentResponses {
		switch candidate.Code {
		case http.StatusCreated:
			first = candidate
		case http.StatusOK:
			replay = candidate
		default:
			t.Fatalf("concurrent content response = %d %s", candidate.Code, candidate.Body.String())
		}
	}
	if first == nil || replay == nil {
		t.Fatalf("concurrent responses created=%v replay=%v", first != nil, replay != nil)
	}
	if first.Code != http.StatusCreated || first.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("first content response = %d %s", first.Code, first.Body.String())
	}
	var uploaded uploadedObjectResponse
	if err := json.Unmarshal(first.Body.Bytes(), &uploaded); err != nil {
		t.Fatalf("decode uploaded object: %v; body=%s", err, first.Body.String())
	}
	if uploaded.ObjectVersionID != metadata.VersionID || uploaded.ETag != metadata.ETag || uploaded.ChecksumSHA256 != checksum {
		t.Fatalf("uploaded metadata = %#v", uploaded)
	}
	if store.putCalls != 1 || !bytes.Equal(store.putBody, body) || store.putSize != int64(len(body)) ||
		store.putType != "image/jpeg" || store.putChecksum != checksum || repository.finalizeCalls != 2 {
		t.Fatalf("stream calls put=%d body=%x finalize=%d", store.putCalls, store.putBody, repository.finalizeCalls)
	}
	if replay.Code != http.StatusOK || replay.Body.String() != first.Body.String() || store.putCalls != 1 || repository.finalizeCalls != 2 {
		t.Fatalf("content replay = %d %s; put=%d finalize=%d", replay.Code, replay.Body.String(), store.putCalls, repository.finalizeCalls)
	}
	conflict := doContent(uuid.New())
	if conflict.Code != http.StatusConflict || !strings.Contains(conflict.Body.String(), `"code":"MEDIA_CONFLICT"`) || store.putCalls != 1 {
		t.Fatalf("different-key replay = %d %s; put=%d", conflict.Code, conflict.Body.String(), store.putCalls)
	}

	completeBody, err := json.Marshal(map[string]string{
		"objectVersionId": uploaded.ObjectVersionID, "etag": uploaded.ETag, "checksumSha256": uploaded.ChecksumSHA256,
	})
	if err != nil {
		t.Fatal(err)
	}
	completeRequest := httptest.NewRequest(http.MethodPost,
		"/api/media/v1/upload-sessions/"+sessionID.String()+"/complete", bytes.NewReader(completeBody))
	completeRequest.Header.Set("Authorization", "Bearer test")
	completeRequest.Header.Set("Idempotency-Key", contentKey.String())
	completeResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(completeResponse, completeRequest)
	if completeResponse.Code != http.StatusOK || store.putCalls != 1 {
		t.Fatalf("completion confirmation = %d %s; put=%d", completeResponse.Code, completeResponse.Body.String(), store.putCalls)
	}
}

func TestUploadContentFailsClosedBeforeStorage(t *testing.T) {
	warehouseID, subjectID, sessionID := uuid.New(), uuid.New(), uuid.New()
	asset := persistence.AssetRecord{
		ID: uuid.New(), WarehouseID: warehouseID, SourceObjectKey: "private/source.jpg",
		ContentType: "image/jpeg", ExpectedLength: 4, ExpectedChecksum: strings.Repeat("a", 64),
		UploadExpiresAt: time.Now().Add(time.Minute),
	}
	validPrincipal := auth.Principal{SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}}}
	tests := []struct {
		name       string
		repository *repositoryStub
		principal  auth.Principal
		content    []byte
		mime       string
		wantStatus int
	}{
		{name: "wrong subject", repository: &repositoryStub{sessionErr: persistence.ErrNotFound}, principal: validPrincipal,
			content: []byte("jpeg"), mime: "image/jpeg", wantStatus: http.StatusNotFound},
		{name: "wrong warehouse grant", repository: &repositoryStub{sessionAsset: asset},
			principal: auth.Principal{SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}}},
			content:   []byte("jpeg"), mime: "image/jpeg", wantStatus: http.StatusForbidden},
		{name: "wrong MIME", repository: &repositoryStub{sessionAsset: asset}, principal: validPrincipal,
			content: []byte("jpeg"), mime: "image/png", wantStatus: http.StatusConflict},
		{name: "wrong length", repository: &repositoryStub{sessionAsset: asset}, principal: validPrincipal,
			content: []byte("too-long"), mime: "image/jpeg", wantStatus: http.StatusConflict},
	}
	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			store := &storeStub{}
			server := newTestServer(t, testCase.repository, validatorStub{principal: testCase.principal}, store)
			request := httptest.NewRequest(http.MethodPut,
				"/api/media/v1/upload-sessions/"+sessionID.String()+"/content", bytes.NewReader(testCase.content))
			request.Header.Set("Authorization", "Bearer test")
			request.Header.Set("Idempotency-Key", uuid.NewString())
			request.Header.Set("Content-Type", testCase.mime)
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != testCase.wantStatus || store.putCalls != 0 {
				t.Fatalf("response = %d %s; put calls=%d", response.Code, response.Body.String(), store.putCalls)
			}
		})
	}
}

func TestOwnerMediaListReturnsOnlyRelativeAuthorizedContentPaths(t *testing.T) {
	warehouseID, ownerID, mediaID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	asset := persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, FileName: "finding.jpg", ContentType: "image/jpeg",
		Kind: media.KindImage, Status: media.StatusReady, Version: 3, Generation: 2,
		CreatedAt: time.Now(),
	}
	repository := &repositoryStub{ownerRecords: []persistence.AssetWithVariants{{
		Asset: asset,
		Variants: []persistence.VariantRecord{{
			Variant: media.VariantSmall, ObjectKey: "private/minio-secret-key.webp",
			ObjectVersionID: "secret-version", ContentType: "image/webp", SizeBytes: 7,
		}},
	}}}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	path := ownerScopedPath("/api/media/v1/assets", ownerID, warehouseID)
	response := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, path, nil)
	request.Header.Set("Authorization", "Bearer test")
	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("list response = %d %s", response.Code, response.Body.String())
	}
	wantPrefix := `"contentPath":"/api/media/v1/assets/` + mediaID.String() + `/variants/SMALL/content?`
	if !strings.Contains(response.Body.String(), wantPrefix) || !strings.Contains(response.Body.String(), `generation=2`) {
		t.Fatalf("relative content path missing: %s", response.Body.String())
	}
	for _, forbidden := range []string{"private/minio-secret-key", "secret-version", "http://", "https://", `"url"`} {
		if strings.Contains(strings.ToLower(response.Body.String()), strings.ToLower(forbidden)) {
			t.Fatalf("list response leaked %q: %s", forbidden, response.Body.String())
		}
	}
}

func TestOriginalAndDerivedContentStreamThroughAuthenticatedAPI(t *testing.T) {
	warehouseID, ownerID, mediaID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte("webp-content")
	asset := persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, FileName: "finding.webp", ContentType: "image/webp",
		Kind: media.KindImage, Status: media.StatusReady, Version: 3, Generation: 2,
	}
	original := &persistence.VariantRecord{
		Variant: media.VariantOriginal, ObjectKey: "private/original-key",
		ObjectVersionID: "original-version", ContentType: "image/webp", SizeBytes: int64(len(body)),
	}
	derived := &persistence.VariantRecord{
		Variant: media.VariantSmall, ObjectKey: "private/derived-key",
		ObjectVersionID: "derived-version", ContentType: "image/webp", SizeBytes: int64(len(body)),
	}
	repository := &repositoryStub{
		originalAsset: asset, originalVariant: original,
		currentAsset: asset, currentVariant: derived,
	}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	for _, testCase := range []struct {
		name, path, version string
	}{
		{name: "original", path: ownerScopedPath("/api/media/v1/assets/"+mediaID.String()+"/original", ownerID, warehouseID), version: original.ObjectVersionID},
		{name: "derived", path: ownerScopedPath("/api/media/v1/assets/"+mediaID.String()+"/variants/SMALL/content", ownerID, warehouseID) + "&generation=2", version: derived.ObjectVersionID},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
				SizeBytes: int64(len(body)), ContentType: "image/webp", VersionID: testCase.version,
			}}
			server := newTestServer(t, repository, validatorStub{principal: principal}, store)
			request := httptest.NewRequest(http.MethodGet, testCase.path, nil)
			request.Header.Set("Authorization", "Bearer test")
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), body) {
				t.Fatalf("stream response = %d %q", response.Code, response.Body.Bytes())
			}
			if response.Header().Get("Cache-Control") != "private, no-store" ||
				response.Header().Get("Content-Type") != "image/webp" ||
				response.Header().Get("Content-Length") != fmt.Sprint(len(body)) ||
				!strings.HasPrefix(response.Header().Get("Content-Disposition"), "inline;") ||
				response.Header().Get("X-Content-Type-Options") != "nosniff" {
				t.Fatalf("stream headers = %#v", response.Header())
			}
			wire := response.Body.String() + response.Header().Get("Content-Disposition")
			for _, forbidden := range []string{"private/", "minio", "original-version", "derived-version", "http://", "https://"} {
				if strings.Contains(strings.ToLower(wire), strings.ToLower(forbidden)) {
					t.Fatalf("stream leaked %q: %s", forbidden, wire)
				}
			}
		})
	}
}

func TestOriginalContentForbiddenNotFoundAndStorageOutage(t *testing.T) {
	warehouseID, ownerID, mediaID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte("original")
	asset := persistence.AssetRecord{
		ID: mediaID, WarehouseID: warehouseID, FileName: "finding.jpg",
		Status: media.StatusReady, Generation: 1,
	}
	original := &persistence.VariantRecord{
		Variant: media.VariantOriginal, ObjectKey: "private/original",
		ObjectVersionID: "version-1", ContentType: "image/jpeg", SizeBytes: int64(len(body)),
	}
	validPrincipal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	tests := []struct {
		name       string
		repository *repositoryStub
		principal  auth.Principal
		store      *storeStub
		wantStatus int
		wantCode   string
	}{
		{name: "forbidden warehouse", repository: &repositoryStub{},
			principal: auth.Principal{SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.read": {}}},
			store:     &storeStub{}, wantStatus: http.StatusForbidden, wantCode: "MEDIA_FORBIDDEN"},
		{name: "owner scoped not found", repository: &repositoryStub{originalReadErr: persistence.ErrNotFound},
			principal: validPrincipal, store: &storeStub{}, wantStatus: http.StatusNotFound, wantCode: "MEDIA_NOT_FOUND"},
		{name: "private storage outage", repository: &repositoryStub{originalAsset: asset, originalVariant: original},
			principal: validPrincipal, store: &storeStub{getErr: errors.New("MinIO offline")},
			wantStatus: http.StatusServiceUnavailable, wantCode: "MEDIA_STORAGE_UNAVAILABLE"},
	}
	path := ownerScopedPath("/api/media/v1/assets/"+mediaID.String()+"/original", ownerID, warehouseID)
	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			server := newTestServer(t, testCase.repository, validatorStub{principal: testCase.principal}, testCase.store)
			request := httptest.NewRequest(http.MethodGet, path, nil)
			request.Header.Set("Authorization", "Bearer test")
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != testCase.wantStatus || !strings.Contains(response.Body.String(), `"code":"`+testCase.wantCode+`"`) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if strings.Contains(strings.ToLower(response.Body.String()), "private/original") ||
				strings.Contains(strings.ToLower(response.Body.String()), "minio offline") {
				t.Fatalf("problem leaked storage detail: %s", response.Body.String())
			}
		})
	}
}

func ownerScopedPath(base string, ownerID, warehouseID uuid.UUID) string {
	return base + "?ownerType=INVENTORY_FINDING&ownerId=" + ownerID.String() +
		"&warehouseId=" + warehouseID.String() + "&context=INSPECTION"
}

func TestFinalizeSeparatesObjectMismatchFromStorageFailure(t *testing.T) {
	warehouseID := uuid.New()
	sessionID := uuid.New()
	subjectID := uuid.New()
	expectedChecksum := strings.Repeat("a", 64)
	asset := persistence.AssetRecord{
		ID: uuid.New(), WarehouseID: warehouseID, SourceObjectKey: "media/source.jpg",
		ContentType: "image/jpeg", ExpectedLength: 128, ExpectedChecksum: expectedChecksum,
		UploadExpiresAt: time.Now().Add(time.Minute),
	}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	body := `{"objectVersionId":"version-1","etag":"etag-1","checksumSha256":"` + expectedChecksum + `"}`
	for _, testCase := range []struct {
		name       string
		store      *storeStub
		wantStatus int
		wantCode   string
	}{
		{
			name: "dependency", store: &storeStub{statErr: errors.New("MinIO offline")},
			wantStatus: http.StatusServiceUnavailable, wantCode: "MEDIA_STORAGE_UNAVAILABLE",
		},
		{
			name: "mismatch", store: &storeStub{statMetadata: media.ObjectMetadata{
				SizeBytes: 127, ContentType: "image/jpeg", ETag: "etag-1", VersionID: "version-1",
			}}, wantStatus: http.StatusConflict, wantCode: "MEDIA_OBJECT_MISMATCH",
		},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			repository := &repositoryStub{sessionAsset: asset}
			server := newTestServer(t, repository, validatorStub{principal: principal}, testCase.store)
			request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions/"+sessionID.String()+"/complete", strings.NewReader(body))
			request.Header.Set("Authorization", "Bearer test")
			request.Header.Set("Idempotency-Key", uuid.NewString())
			response := httptest.NewRecorder()

			server.Handler().ServeHTTP(response, request)

			if response.Code != testCase.wantStatus || !strings.Contains(response.Body.String(), `"code":"`+testCase.wantCode+`"`) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if repository.finalizeCalls != 0 {
				t.Fatalf("FinalizeUpload calls = %d, want 0", repository.finalizeCalls)
			}
		})
	}
}

func TestLogisticsReferenceValidationReturnsOnlyOpaqueReadyReferences(t *testing.T) {
	documentID, lineID, warehouseID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{}
	server := newTestServer(t, repository, validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: "logistics-service", ClientID: "logistics-service",
		Scopes: map[string]struct{}{"media.logistics": {}},
	}}, &storeStub{})
	body := logisticsReferenceBody(t, persistence.OwnerTypeLogisticsReturn, documentID, lineID, warehouseID,
		[]persistence.ReadyMediaReference{{MediaID: mediaID, Generation: 3}})

	for range 2 {
		request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/logistics/references/validate", bytes.NewReader(body))
		request.Header.Set("Authorization", "Bearer test")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK {
			t.Fatalf("status = %d, want %d; body=%s", response.Code, http.StatusOK, response.Body.String())
		}
		if response.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("Cache-Control = %q, want no-store", response.Header().Get("Cache-Control"))
		}
		for _, forbidden := range []string{"url", "objectKey", "source", "fileName", "contentType", "status", "ownerId"} {
			if strings.Contains(response.Body.String(), `"`+forbidden+`"`) {
				t.Fatalf("opaque response leaked %q: %s", forbidden, response.Body.String())
			}
		}
	}
	calls, command := repository.validationSnapshot()
	if calls != 2 {
		t.Fatalf("validation calls = %d, want two harmless revalidations", calls)
	}
	if command.OwnerType != persistence.OwnerTypeLogisticsReturn ||
		command.OwnerID != persistence.LogisticsOwnerID(documentID, lineID) ||
		command.WarehouseID != warehouseID || len(command.References) != 1 ||
		command.References[0].MediaID != mediaID || command.References[0].Generation != 3 {
		t.Fatalf("validation command = %#v", command)
	}
}

func TestLogisticsReferenceValidationFailsClosed(t *testing.T) {
	documentID, lineID, warehouseID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	validBody := logisticsReferenceBody(t, persistence.OwnerTypeLogisticsTransfer, documentID, lineID, warehouseID,
		[]persistence.ReadyMediaReference{{MediaID: mediaID, Generation: 1}})
	tests := []struct {
		name          string
		validator     validatorStub
		body          []byte
		repository    *repositoryStub
		wantStatus    int
		wantCode      string
		wantCallCount int
	}{
		{
			name: "missing service token", validator: validatorStub{serviceErr: auth.ErrUnauthorized},
			body: validBody, repository: &repositoryStub{}, wantStatus: http.StatusUnauthorized,
			wantCode: "MEDIA_UNAUTHORIZED",
		},
		{
			name: "wrong exact scope", validator: validatorStub{servicePrincipal: auth.ServicePrincipal{
				Subject: "logistics-service", ClientID: "logistics-service", Scopes: map[string]struct{}{"asset.logistics": {}},
			}}, body: validBody, repository: &repositoryStub{}, wantStatus: http.StatusForbidden, wantCode: "MEDIA_FORBIDDEN",
		},
		{
			name: "unknown logistics owner", validator: logisticsValidatorStub(),
			body: logisticsReferenceBody(t, "INVENTORY_FINDING", documentID, lineID, warehouseID,
				[]persistence.ReadyMediaReference{{MediaID: mediaID, Generation: 1}}),
			repository: &repositoryStub{}, wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_LOGISTICS_REFERENCE",
		},
		{
			name: "duplicate media reference", validator: logisticsValidatorStub(),
			body: logisticsReferenceBody(t, persistence.OwnerTypeLogisticsReturn, documentID, lineID, warehouseID,
				[]persistence.ReadyMediaReference{{MediaID: mediaID, Generation: 1}, {MediaID: mediaID, Generation: 2}}),
			repository: &repositoryStub{}, wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_LOGISTICS_REFERENCE",
		},
		{
			name: "not ready or wrong owner is opaque", validator: logisticsValidatorStub(), body: validBody,
			repository: &repositoryStub{validationErr: persistence.ErrReferenceNotReady},
			wantStatus: http.StatusConflict, wantCode: "MEDIA_REFERENCE_NOT_READY", wantCallCount: 1,
		},
		{
			name: "repository outage", validator: logisticsValidatorStub(), body: validBody,
			repository: &repositoryStub{validationErr: errors.New("database unavailable")},
			wantStatus: http.StatusServiceUnavailable, wantCode: "MEDIA_DEPENDENCY_UNAVAILABLE", wantCallCount: 1,
		},
	}
	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			server := newTestServer(t, testCase.repository, testCase.validator, &storeStub{})
			request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/logistics/references/validate", bytes.NewReader(testCase.body))
			request.Header.Set("Authorization", "Bearer test")
			response := httptest.NewRecorder()

			server.Handler().ServeHTTP(response, request)

			if response.Code != testCase.wantStatus || !strings.Contains(response.Body.String(), `"code":"`+testCase.wantCode+`"`) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			calls, _ := testCase.repository.validationSnapshot()
			if calls != testCase.wantCallCount {
				t.Fatalf("validation calls = %d, want %d", calls, testCase.wantCallCount)
			}
		})
	}
}

func TestLogisticsReferenceValidationIsReadOnlyAcrossConcurrentRequests(t *testing.T) {
	documentID, lineID, warehouseID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{}
	server := newTestServer(t, repository, logisticsValidatorStub(), &storeStub{})
	body := logisticsReferenceBody(t, persistence.OwnerTypeLogisticsShipment, documentID, lineID, warehouseID,
		[]persistence.ReadyMediaReference{{MediaID: mediaID, Generation: 1}})
	const calls = 20
	start := make(chan struct{})
	failures := make(chan error, calls)
	var wait sync.WaitGroup
	for range calls {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/logistics/references/validate", bytes.NewReader(body))
			request.Header.Set("Authorization", "Bearer test")
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusOK {
				failures <- fmt.Errorf("status %d: %s", response.Code, response.Body.String())
			}
		}()
	}
	close(start)
	wait.Wait()
	close(failures)
	for failure := range failures {
		t.Error(failure)
	}
	gotCalls, _ := repository.validationSnapshot()
	if gotCalls != calls {
		t.Fatalf("validation calls = %d, want %d", gotCalls, calls)
	}
}

func logisticsValidatorStub() validatorStub {
	return validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: "logistics-service", ClientID: "logistics-service",
		Scopes: map[string]struct{}{"media.logistics": {}},
	}}
}

func logisticsReferenceBody(
	t *testing.T,
	ownerType string,
	documentID, lineID, warehouseID uuid.UUID,
	references []persistence.ReadyMediaReference,
) []byte {
	t.Helper()
	body := map[string]any{
		"ownerType": ownerType, "documentId": documentID.String(), "lineId": lineID.String(),
		"warehouseId": warehouseID.String(), "references": references,
	}
	result, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal logistics body: %v", err)
	}
	return result
}

func newTestServer(t *testing.T, repository repository, validator tokenValidator, store objectStore) *Server {
	t.Helper()
	server, err := NewServer(repository, readyStub{}, validator, store, Configuration{
		MaxUploadBytes: 1 << 20, AllowedMIMETypes: map[string]struct{}{"image/jpeg": {}},
		UploadExpiry: time.Minute,
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("NewServer() error = %v", err)
	}
	return server
}

type readyStub struct{ err error }

func (stub readyStub) Ready(context.Context) error { return stub.err }

type validatorStub struct {
	principal        auth.Principal
	err              error
	servicePrincipal auth.ServicePrincipal
	serviceErr       error
}

func (stub validatorStub) Validate(context.Context, string) (auth.Principal, error) {
	return stub.principal, stub.err
}

func (stub validatorStub) ValidateService(context.Context, string) (auth.ServicePrincipal, error) {
	return stub.servicePrincipal, stub.serviceErr
}

type repositoryStub struct {
	mutex             sync.Mutex
	contentMutex      sync.Mutex
	contentLockErr    error
	createAsset       persistence.AssetRecord
	createReplay      bool
	createErr         error
	createCalls       int
	createCommand     persistence.CreateUploadCommand
	sessionAsset      persistence.AssetRecord
	sessionErr        error
	finalizeAsset     persistence.AssetRecord
	finalizeReplay    bool
	finalizeErr       error
	finalizeFunc      func(persistence.FinalizeCommand) (persistence.AssetRecord, bool, error)
	finalizeCalls     int
	finalizeCommands  []persistence.FinalizeCommand
	ownerRecords      []persistence.AssetWithVariants
	ownerReadErr      error
	originalAsset     persistence.AssetRecord
	originalVariant   *persistence.VariantRecord
	originalReadErr   error
	currentAsset      persistence.AssetRecord
	currentVariant    *persistence.VariantRecord
	currentReadErr    error
	validationCalls   int
	validationCommand persistence.ValidateLogisticsReferencesCommand
	validationErr     error
}

func (stub *repositoryStub) CreateUpload(_ context.Context, command persistence.CreateUploadCommand) (persistence.AssetRecord, bool, error) {
	stub.createCalls++
	stub.createCommand = command
	if stub.createErr != nil {
		return persistence.AssetRecord{}, false, stub.createErr
	}
	if stub.createAsset.ID == uuid.Nil {
		return persistence.AssetRecord{}, false, errors.New("unexpected CreateUpload")
	}
	return stub.createAsset, stub.createReplay, nil
}

func (stub *repositoryStub) AcquireUploadSessionContentLock(context.Context, uuid.UUID) (func() error, error) {
	if stub.contentLockErr != nil {
		return nil, stub.contentLockErr
	}
	stub.contentMutex.Lock()
	return func() error {
		stub.contentMutex.Unlock()
		return nil
	}, nil
}

func (stub *repositoryStub) UploadSessionForSubject(context.Context, uuid.UUID, uuid.UUID) (persistence.AssetRecord, error) {
	return stub.sessionAsset, stub.sessionErr
}

func (stub *repositoryStub) FinalizeUpload(_ context.Context, command persistence.FinalizeCommand) (persistence.AssetRecord, bool, error) {
	stub.finalizeCalls++
	stub.finalizeCommands = append(stub.finalizeCommands, command)
	if stub.finalizeFunc != nil {
		return stub.finalizeFunc(command)
	}
	if stub.finalizeErr != nil {
		return persistence.AssetRecord{}, false, stub.finalizeErr
	}
	if stub.finalizeAsset.ID == uuid.Nil {
		return persistence.AssetRecord{}, false, errors.New("unexpected FinalizeUpload")
	}
	return stub.finalizeAsset, stub.finalizeReplay, nil
}

func (stub *repositoryStub) ReadOwnerAssets(_ context.Context, _, _ string, _ uuid.UUID, _ int,
	_ *uuid.UUID, consume func([]persistence.AssetWithVariants) error,
) error {
	if stub.ownerReadErr != nil {
		return stub.ownerReadErr
	}
	if stub.ownerRecords == nil {
		return errors.New("unexpected ReadOwnerAssets")
	}
	return consume(stub.ownerRecords)
}

func (stub *repositoryStub) ReadOriginal(_ context.Context, _ uuid.UUID, _, _ string, _ uuid.UUID,
	consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	if stub.originalReadErr != nil {
		return stub.originalReadErr
	}
	if stub.originalAsset.ID == uuid.Nil {
		return errors.New("unexpected ReadOriginal")
	}
	return consume(stub.originalAsset, stub.originalVariant)
}

func (stub *repositoryStub) ReadCurrentVariant(_ context.Context, _ uuid.UUID, _, _ string, _ uuid.UUID, _ int,
	_ media.Variant, consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	if stub.currentReadErr != nil {
		return stub.currentReadErr
	}
	if stub.currentAsset.ID == uuid.Nil {
		return errors.New("unexpected ReadCurrentVariant")
	}
	return consume(stub.currentAsset, stub.currentVariant)
}

func (stub *repositoryStub) GetAssetScoped(context.Context, uuid.UUID, string, string, uuid.UUID) (persistence.AssetRecord, error) {
	return persistence.AssetRecord{}, errors.New("unexpected GetAssetScoped")
}

func (stub *repositoryStub) ValidateLogisticsReferences(_ context.Context, command persistence.ValidateLogisticsReferencesCommand) error {
	stub.mutex.Lock()
	defer stub.mutex.Unlock()
	stub.validationCalls++
	stub.validationCommand = command
	return stub.validationErr
}

func (stub *repositoryStub) validationSnapshot() (int, persistence.ValidateLogisticsReferencesCommand) {
	stub.mutex.Lock()
	defer stub.mutex.Unlock()
	return stub.validationCalls, stub.validationCommand
}

func (stub *repositoryStub) Rotate(context.Context, persistence.RotateCommand) (persistence.AssetRecord, bool, error) {
	return persistence.AssetRecord{}, false, errors.New("unexpected Rotate")
}

type storeStub struct {
	ensureCalls  int
	putCalls     int
	putBody      []byte
	putKey       string
	putSize      int64
	putType      string
	putChecksum  string
	putMetadata  media.ObjectMetadata
	putErr       error
	statCalls    int
	getCalls     int
	getKey       string
	getVersion   string
	getErr       error
	statMetadata media.ObjectMetadata
	statErr      error
	objectBody   []byte
}

func (stub *storeStub) EnsureVersioning(context.Context) error {
	stub.ensureCalls++
	return nil
}

func (stub *storeStub) PutIngressVersion(_ context.Context, key string, source io.Reader, size int64,
	contentType, checksum string,
) (media.ObjectMetadata, error) {
	stub.putCalls++
	stub.putKey, stub.putSize, stub.putType, stub.putChecksum = key, size, contentType, checksum
	var body bytes.Buffer
	buffer := make([]byte, 3)
	if _, err := io.CopyBuffer(&body, source, buffer); err != nil {
		return media.ObjectMetadata{}, err
	}
	stub.putBody = body.Bytes()
	return stub.putMetadata, stub.putErr
}

func (stub *storeStub) StatVersion(context.Context, string, string) (media.ObjectMetadata, error) {
	stub.statCalls++
	return stub.statMetadata, stub.statErr
}

func (stub *storeStub) GetVersion(_ context.Context, key, version string) (io.ReadCloser, media.ObjectMetadata, error) {
	stub.getCalls++
	stub.getKey, stub.getVersion = key, version
	if stub.getErr != nil {
		return nil, media.ObjectMetadata{}, stub.getErr
	}
	return io.NopCloser(bytes.NewReader(stub.objectBody)), stub.statMetadata, nil
}
