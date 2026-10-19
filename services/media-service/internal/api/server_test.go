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
	"dev.buhanzaz.rwms/media-service/internal/realtime"
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
		{name: "event stream wrong method", method: http.MethodPost, path: "/api/media/v1/events", wantStatus: http.StatusMethodNotAllowed, wantCode: "MEDIA_METHOD_NOT_ALLOWED"},
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

func TestWarehouseEventStreamRequiresReadViewAndStartsWithResync(t *testing.T) {
	warehouseID := uuid.New()
	readPrincipal := auth.Principal{
		SubjectID: uuid.New(), Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	tests := []struct {
		name       string
		path       string
		validator  validatorStub
		wantStatus int
	}{
		{name: "unauthenticated", path: "/api/media/v1/events?warehouseId=" + warehouseID.String(), validator: validatorStub{err: auth.ErrUnauthorized}, wantStatus: http.StatusUnauthorized},
		{name: "invalid warehouse", path: "/api/media/v1/events?warehouseId=invalid", validator: validatorStub{principal: readPrincipal}, wantStatus: http.StatusBadRequest},
		{name: "missing read scope", path: "/api/media/v1/events?warehouseId=" + warehouseID.String(), validator: validatorStub{principal: auth.Principal{
			SubjectID: uuid.New(), Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
		}}, wantStatus: http.StatusForbidden},
		{name: "missing warehouse view", path: "/api/media/v1/events?warehouseId=" + warehouseID.String(), validator: validatorStub{principal: auth.Principal{
			SubjectID: uuid.New(), Scopes: map[string]struct{}{"rwms.read": {}},
		}}, wantStatus: http.StatusForbidden},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := newTestServer(t, &repositoryStub{}, test.validator, &storeStub{})
			request := httptest.NewRequest(http.MethodGet, test.path, nil)
			request.Header.Set("Authorization", "Bearer test")
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != test.wantStatus || response.Header().Get("Content-Type") != "application/problem+json" {
				t.Fatalf("response = %d %q %s", response.Code, response.Header().Get("Content-Type"), response.Body.String())
			}
		})
	}

	server := newTestServer(t, &repositoryStub{}, validatorStub{principal: readPrincipal}, &storeStub{})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	request := httptest.NewRequest(http.MethodGet,
		"/api/media/v1/events?warehouseId="+warehouseID.String(), nil).WithContext(ctx)
	request.Header.Set("Authorization", "Bearer test")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Content-Type") != "text/event-stream" ||
		response.Header().Get("Cache-Control") != "no-cache, no-store, must-revalidate" ||
		!strings.Contains(response.Body.String(), "event: warehouse-invalidation\n") ||
		!strings.Contains(response.Body.String(), `"warehouseId":"`+warehouseID.String()+`"`) ||
		!strings.Contains(response.Body.String(), `"scope":"RESYNC"`) {
		t.Fatalf("event stream response = %d %#v %q", response.Code, response.Header(), response.Body.String())
	}
}

func TestCreateUploadReturnsOnlySameOriginContentPath(t *testing.T) {
	warehouseID, ownerID, subjectID := uuid.New(), uuid.New(), uuid.New()
	sessionID, mediaID := uuid.New(), uuid.New()
	repository := &repositoryStub{createAsset: persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, Version: 1, UploadSessionID: sessionID,
		UploadExpiresAt: time.Now().Add(time.Minute),
	}}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	invalidations := &invalidationRecorder{}
	server.invalidations = invalidations
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
		repository.createCommand.WarehouseID != warehouseID ||
		repository.createCommand.FolderID != repository.createCommand.MediaID {
		t.Fatalf("create command = %#v, calls=%d", repository.createCommand, repository.createCalls)
	}

	invalidBody := strings.Replace(body, `"INVENTORY_FINDING"`, `"MAINTENANCE_ESTIMATE"`, 1)
	invalidRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(invalidBody))
	invalidRequest.Header.Set("Authorization", "Bearer test")
	invalidRequest.Header.Set("Idempotency-Key", uuid.NewString())
	invalidResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(invalidResponse, invalidRequest)
	if invalidResponse.Code != http.StatusBadRequest || repository.createCalls != 1 {
		t.Fatalf("unproved owner response = %d %s; create calls=%d", invalidResponse.Code, invalidResponse.Body.String(), repository.createCalls)
	}
	events := invalidations.snapshot()
	if len(events) != 1 || events[0].WarehouseID != warehouseID || events[0].MediaID != mediaID ||
		events[0].Scope != "MEDIA_CHANGED" || events[0].Revision != 1 {
		t.Fatalf("create invalidations = %#v", events)
	}
}

func TestCustomerRentalCreatesOnlyItsSubjectBoundShipmentUpload(t *testing.T) {
	documentID, lineID, warehouseID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	sessionID, mediaID := uuid.New(), uuid.New()
	repository := &repositoryStub{createAsset: persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeLogisticsShipment,
		OwnerID: persistence.LogisticsOwnerID(documentID, lineID), WarehouseID: warehouseID,
		Version: 1, UploadSessionID: sessionID, UploadExpiresAt: time.Now().Add(time.Minute),
	}}
	principal := auth.Principal{
		SubjectID: subjectID, Role: "CUSTOMER", ClientID: "rwms-customer-android",
		Scopes: map[string]struct{}{"openid": {}, "profile": {}, "offline_access": {}, "customer.rental": {}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"LOGISTICS_SHIPMENT","documentId":"%s","lineId":"%s","warehouseId":"%s","context":"SHIPMENT","fileName":"delivery.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s","sortOrder":0}`,
		documentID, lineID, warehouseID, strings.Repeat("a", 64))
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusCreated || repository.createCalls != 1 ||
		repository.createCommand.AuthorizedSubjectID == nil ||
		*repository.createCommand.AuthorizedSubjectID != subjectID {
		t.Fatalf("customer create response = %d %s; command=%#v", response.Code,
			response.Body.String(), repository.createCommand)
	}

	foreignBody := strings.Replace(body, `"LOGISTICS_SHIPMENT"`, `"LOGISTICS_RETURN"`, 1)
	foreignBody = strings.Replace(foreignBody, `"SHIPMENT"`, `"RETURN_INSPECTION"`, 1)
	foreignRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(foreignBody))
	foreignRequest.Header.Set("Authorization", "Bearer test")
	foreignRequest.Header.Set("Idempotency-Key", uuid.NewString())
	foreignResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(foreignResponse, foreignRequest)
	if foreignResponse.Code != http.StatusForbidden || repository.createCalls != 1 {
		t.Fatalf("customer non-shipment response = %d %s; calls=%d", foreignResponse.Code,
			foreignResponse.Body.String(), repository.createCalls)
	}
}

func TestCustomerRentalCreatesProfileAvatarButManagerCannotUseProfileOwner(t *testing.T) {
	profileID, warehouseID, subjectID := uuid.New(), uuid.New(), uuid.New()
	sessionID, mediaID := uuid.New(), uuid.New()
	repository := &repositoryStub{createAsset: persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeLogisticsCustomerProfile,
		OwnerID: profileID.String(), WarehouseID: warehouseID, Version: 1,
		UploadSessionID: sessionID, UploadExpiresAt: time.Now().Add(time.Minute),
	}}
	customer := auth.Principal{
		SubjectID: subjectID, Role: "CUSTOMER", ClientID: "rwms-customer-android",
		Scopes: map[string]struct{}{"openid": {}, "profile": {}, "offline_access": {}, "customer.rental": {}},
	}
	body := fmt.Sprintf(`{"ownerType":"LOGISTICS_CUSTOMER_PROFILE","ownerId":"%s","warehouseId":"%s","context":"PROFILE_AVATAR","fileName":"avatar.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s","sortOrder":0}`,
		profileID, warehouseID, strings.Repeat("a", 64))
	server := newTestServer(t, repository, validatorStub{principal: customer}, &storeStub{})
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer customer")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusCreated || repository.createCalls != 1 ||
		repository.createCommand.OwnerType != persistence.OwnerTypeLogisticsCustomerProfile ||
		repository.createCommand.OwnerID != profileID.String() ||
		repository.createCommand.AuthorizedSubjectID == nil ||
		*repository.createCommand.AuthorizedSubjectID != subjectID {
		t.Fatalf("profile avatar create = %d %s; command=%#v", response.Code,
			response.Body.String(), repository.createCommand)
	}

	wrongContext := strings.Replace(body, `"PROFILE_AVATAR"`, `"SHIPMENT"`, 1)
	wrongRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(wrongContext))
	wrongRequest.Header.Set("Authorization", "Bearer customer")
	wrongRequest.Header.Set("Idempotency-Key", uuid.NewString())
	wrongResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(wrongResponse, wrongRequest)
	if wrongResponse.Code != http.StatusBadRequest || repository.createCalls != 1 {
		t.Fatalf("wrong profile context = %d %s; calls=%d", wrongResponse.Code,
			wrongResponse.Body.String(), repository.createCalls)
	}

	managerRepository := &repositoryStub{createAsset: repository.createAsset}
	manager := auth.Principal{
		SubjectID: uuid.New(), Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	managerServer := newTestServer(t, managerRepository, validatorStub{principal: manager}, &storeStub{})
	managerRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
	managerRequest.Header.Set("Authorization", "Bearer manager")
	managerRequest.Header.Set("Idempotency-Key", uuid.NewString())
	managerResponse := httptest.NewRecorder()
	managerServer.Handler().ServeHTTP(managerResponse, managerRequest)
	if managerResponse.Code != http.StatusForbidden || managerRepository.createCalls != 0 {
		t.Fatalf("manager profile create = %d %s; calls=%d", managerResponse.Code,
			managerResponse.Body.String(), managerRepository.createCalls)
	}
}

func TestCustomerProfileUploadRejectsAdditionalDomainScopesBeforeRepository(t *testing.T) {
	for _, extra := range []string{"rwms.read", "rwms.write", "admin.manage", "worker.tasks", "driver.tasks", "unknown.scope"} {
		t.Run(extra, func(t *testing.T) {
			profileID, warehouseID := uuid.New(), uuid.New()
			repository := &repositoryStub{}
			customer := auth.Principal{
				SubjectID: uuid.New(), Role: "CUSTOMER", ClientID: "rwms-customer-android",
				Scopes: map[string]struct{}{"openid": {}, "profile": {}, "offline_access": {}, "customer.rental": {}, extra: {}},
				Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
			}
			server := newTestServer(t, repository, validatorStub{principal: customer}, &storeStub{})
			body := fmt.Sprintf(`{"ownerType":"LOGISTICS_CUSTOMER_PROFILE","ownerId":"%s","warehouseId":"%s","context":"PROFILE_AVATAR","fileName":"avatar.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s","sortOrder":0}`,
				profileID, warehouseID, strings.Repeat("a", 64))
			request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
			request.Header.Set("Authorization", "Bearer customer")
			request.Header.Set("Idempotency-Key", uuid.NewString())
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusForbidden || repository.createCalls != 0 ||
				!strings.Contains(response.Body.String(), `"code":"MEDIA_FORBIDDEN"`) {
				t.Fatalf("over-scoped profile create = %d %s; calls=%d", response.Code,
					response.Body.String(), repository.createCalls)
			}
		})
	}
}

func TestCustomerRentalFinalizeCarriesItsAuthorizedSubject(t *testing.T) {
	documentID, lineID, warehouseID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	sessionID, mediaID := uuid.New(), uuid.New()
	completedAt := time.Now().UTC()
	asset := persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeLogisticsShipment,
		OwnerID: persistence.LogisticsOwnerID(documentID, lineID), WarehouseID: warehouseID,
		UploadSessionID: sessionID, UploadCompletedAt: &completedAt, UploadMode: persistence.UploadModeSource,
		ContentType: "image/jpeg", ExpectedLength: 128, ExpectedChecksum: strings.Repeat("a", 64),
		Status: media.StatusProcessing, Version: 2,
	}
	repository := &repositoryStub{sessionAsset: asset, finalizeAsset: asset, finalizeReplay: true}
	principal := auth.Principal{
		SubjectID: subjectID, Role: "CUSTOMER", ClientID: "rwms-customer-android",
		Scopes: map[string]struct{}{"openid": {}, "profile": {}, "offline_access": {}, "customer.rental": {}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	body := fmt.Sprintf(`{"objectVersionId":"version-1","etag":"etag-1","checksumSha256":"%s"}`,
		strings.Repeat("a", 64))
	request := httptest.NewRequest(http.MethodPost,
		"/api/media/v1/upload-sessions/"+sessionID.String()+"/complete", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK || repository.finalizeCalls != 1 ||
		len(repository.finalizeCommands) != 1 || repository.finalizeCommands[0].AuthorizedSubjectID == nil ||
		*repository.finalizeCommands[0].AuthorizedSubjectID != subjectID {
		t.Fatalf("customer finalize response = %d %s; commands=%#v", response.Code,
			response.Body.String(), repository.finalizeCommands)
	}
}

func TestCustomerProfileFinalizeCarriesItsAuthorizedSubject(t *testing.T) {
	profileID, warehouseID, subjectID := uuid.New(), uuid.New(), uuid.New()
	sessionID, mediaID := uuid.New(), uuid.New()
	completedAt := time.Now().UTC()
	asset := persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeLogisticsCustomerProfile,
		OwnerID: profileID.String(), WarehouseID: warehouseID,
		UploadSessionID: sessionID, UploadCompletedAt: &completedAt, UploadMode: persistence.UploadModeSource,
		ContentType: "image/jpeg", ExpectedLength: 128, ExpectedChecksum: strings.Repeat("a", 64),
		Status: media.StatusProcessing, Version: 2,
	}
	repository := &repositoryStub{sessionAsset: asset, finalizeAsset: asset, finalizeReplay: true}
	principal := auth.Principal{
		SubjectID: subjectID, Role: "CUSTOMER", ClientID: "rwms-customer-android",
		Scopes: map[string]struct{}{"openid": {}, "profile": {}, "offline_access": {}, "customer.rental": {}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	body := fmt.Sprintf(`{"objectVersionId":"version-1","etag":"etag-1","checksumSha256":"%s"}`,
		strings.Repeat("a", 64))
	request := httptest.NewRequest(http.MethodPost,
		"/api/media/v1/upload-sessions/"+sessionID.String()+"/complete", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer customer")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK || repository.finalizeCalls != 1 ||
		repository.finalizeCommands[0].AuthorizedSubjectID == nil ||
		*repository.finalizeCommands[0].AuthorizedSubjectID != subjectID {
		t.Fatalf("profile finalize = %d %s; commands=%#v", response.Code,
			response.Body.String(), repository.finalizeCommands)
	}
}

func TestCreateImageVariantUploadReturnsThreeSameOriginPartPaths(t *testing.T) {
	warehouseID, ownerID, subjectID := uuid.New(), uuid.New(), uuid.New()
	sessionID, mediaID := uuid.New(), uuid.New()
	repository := &repositoryStub{createAsset: persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, Version: 1, UploadSessionID: sessionID,
		UploadMode: persistence.UploadModeImageVariants, UploadExpiresAt: time.Now().Add(time.Minute),
	}}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	checksums := []string{strings.Repeat("a", 64), strings.Repeat("b", 64), strings.Repeat("c", 64)}
	body := fmt.Sprintf(`{"ownerType":"INVENTORY_FINDING","ownerId":"%s","warehouseId":"%s","context":"INSPECTION","fileName":"finding.jpg","imageVariants":[{"kind":"LARGE","contentLength":300,"checksumSha256":"%s","width":1200,"height":800},{"kind":"SMALL","contentLength":100,"checksumSha256":"%s","width":300,"height":200},{"kind":"MEDIUM","contentLength":200,"checksumSha256":"%s","width":600,"height":400}]}`,
		ownerID, warehouseID, checksums[2], checksums[0], checksums[1])
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated || !strings.Contains(response.Body.String(), `"contentUploadUrl":null`) {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
	for _, variant := range []media.Variant{media.VariantSmall, media.VariantMedium, media.VariantLarge} {
		want := "/api/media/v1/upload-sessions/" + sessionID.String() + "/variants/" + string(variant) + "/content"
		if !strings.Contains(response.Body.String(), want) {
			t.Fatalf("variant path %q missing from %s", want, response.Body.String())
		}
	}
	command := repository.createCommand
	if command.UploadMode != persistence.UploadModeImageVariants || command.ContentType != "image/webp" ||
		command.ContentLength != 600 || len(command.ImageVariants) != 3 ||
		command.SourceObjectKey != media.ImageVariantObjectKey(command.MediaID.String(), 1, media.VariantLarge) {
		t.Fatalf("image bundle command = %#v", command)
	}
	manifest := "rwms-image-variants-v1\n" +
		"SMALL:100:" + checksums[0] + ":300x200\n" +
		"MEDIUM:200:" + checksums[1] + ":600x400\n" +
		"LARGE:300:" + checksums[2] + ":1200x800\n"
	digest := sha256.Sum256([]byte(manifest))
	if command.ChecksumSHA256 != hex.EncodeToString(digest[:]) {
		t.Fatalf("manifest checksum = %s", command.ChecksumSHA256)
	}
}

func TestImageVariantContentStreamsWithoutReadbackOrServerTransformation(t *testing.T) {
	warehouseID, ownerID, subjectID, sessionID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte("already-oriented-webp")
	digest := sha256.Sum256(body)
	checksum := hex.EncodeToString(digest[:])
	asset := persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, Kind: media.KindImage, Status: media.StatusUploading,
		UploadMode: persistence.UploadModeImageVariants, UploadSessionID: sessionID,
		UploadExpiresAt: time.Now().Add(time.Minute),
	}
	part := persistence.UploadImageVariantPart{UploadImageVariantExpectation: persistence.UploadImageVariantExpectation{
		Variant: media.VariantSmall, ContentLength: int64(len(body)), ChecksumSHA256: checksum,
		Width: 320, Height: 180, ObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantSmall),
	}}
	completed := part
	completed.ObjectVersionID, completed.ETag = "small-version", "small-etag"
	uploadedAt := time.Now()
	completed.UploadedAt = &uploadedAt
	repository := &repositoryStub{variantAsset: asset, variantPart: part, completeVariantPart: completed}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	store := &storeStub{
		putMetadata:  media.ObjectMetadata{VersionID: "small-version", ETag: "small-etag", SizeBytes: int64(len(body)), ContentType: "image/webp"},
		statMetadata: media.ObjectMetadata{VersionID: "small-version", ETag: "small-etag", SizeBytes: int64(len(body)), ContentType: "image/webp"},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, store)
	request := httptest.NewRequest(http.MethodPut,
		"/api/media/v1/upload-sessions/"+sessionID.String()+"/variants/SMALL/content", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	request.Header.Set("Content-Type", "image/webp")
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated || store.putCalls != 1 || store.statCalls != 1 || store.getCalls != 0 ||
		!bytes.Equal(store.putBody, body) || store.putKey != part.ObjectKey || store.putType != "image/webp" ||
		repository.completeVariantCommand.ChecksumSHA256 != checksum {
		t.Fatalf("response=%d %s store=%#v command=%#v", response.Code, response.Body.String(), store, repository.completeVariantCommand)
	}
}

func TestFinalizeImageVariantBundleUsesPersistedPartMetadataWithoutStorageRead(t *testing.T) {
	warehouseID, ownerID, subjectID, sessionID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	asset := persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, Kind: media.KindImage, Status: media.StatusUploading,
		UploadMode: persistence.UploadModeImageVariants, UploadSessionID: sessionID,
		UploadExpiresAt: time.Now().Add(time.Minute),
	}
	repository := &repositoryStub{sessionAsset: asset, finalizeAsset: persistence.AssetRecord{
		ID: mediaID, WarehouseID: warehouseID, Status: media.StatusProcessing,
	}}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	store := &storeStub{}
	server := newTestServer(t, repository, validatorStub{principal: principal}, store)
	body := fmt.Sprintf(`{"variants":[{"kind":"LARGE","objectVersionId":"large-v1","etag":"large-etag","checksumSha256":"%s"},{"kind":"SMALL","objectVersionId":"small-v1","etag":"small-etag","checksumSha256":"%s"},{"kind":"MEDIUM","objectVersionId":"medium-v1","etag":"medium-etag","checksumSha256":"%s"}]}`,
		strings.Repeat("c", 64), strings.Repeat("a", 64), strings.Repeat("b", 64))
	request := httptest.NewRequest(http.MethodPost,
		"/api/media/v1/upload-sessions/"+sessionID.String()+"/complete", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusAccepted || store.statCalls != 0 || store.getCalls != 0 ||
		repository.finalizeCalls != 1 || len(repository.finalizeCommands[0].ImageVariants) != 3 {
		t.Fatalf("response=%d %s store=%#v commands=%#v", response.Code, response.Body.String(), store, repository.finalizeCommands)
	}
	for index, kind := range []media.Variant{media.VariantSmall, media.VariantMedium, media.VariantLarge} {
		if repository.finalizeCommands[0].ImageVariants[index].Variant != kind {
			t.Fatalf("finalize variants = %#v", repository.finalizeCommands[0].ImageVariants)
		}
	}
}

func TestTaskScopedWorkerCreatesTaskBoardEvidenceWithServerDerivedWorkerActor(t *testing.T) {
	for _, taskScope := range []string{"worker.tasks", "driver.tasks"} {
		t.Run(taskScope, func(t *testing.T) {
			warehouseID, entryID, workerID, subjectID, evidenceID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
			repository := &repositoryStub{createAsset: persistence.AssetRecord{
				ID: uuid.New(), UploadSessionID: uuid.New(), UploadExpiresAt: time.Now().Add(time.Minute),
			}}
			worker := auth.WorkerPrincipal{
				SubjectID: subjectID, WorkerID: workerID, WarehouseID: warehouseID,
				Scopes: map[string]struct{}{taskScope: {}},
			}
			server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, &storeStub{})
			body := fmt.Sprintf(`{"ownerType":"TASK_BOARD_ENTRY","ownerId":"%s","clientReferenceId":"%s","warehouseId":"%s","context":"WORK_RESULT","fileName":"result.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s"}`,
				entryID, evidenceID, warehouseID, strings.Repeat("a", 64))
			request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
			request.Header.Set("Authorization", "Bearer worker")
			request.Header.Set("Idempotency-Key", evidenceID.String())
			response := httptest.NewRecorder()

			server.Handler().ServeHTTP(response, request)

			if response.Code != http.StatusCreated {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			command := repository.createCommand
			if command.PrincipalType != persistence.PrincipalTypeWorker || command.SubjectID != subjectID ||
				command.WorkerID == nil || *command.WorkerID != workerID || command.Actor.SubjectID != workerID ||
				command.ClientReferenceID == nil || *command.ClientReferenceID != evidenceID || command.OwnerID != entryID.String() {
				t.Fatalf("worker evidence command = %#v", command)
			}
		})
	}
}

func TestDriverCreatesOnlyImageShiftEvidenceWithDriverScope(t *testing.T) {
	warehouseID, shiftID, workerID, subjectID, photoID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	for scope, expectedStatus := range map[string]int{
		"driver.tasks": http.StatusCreated,
		"worker.tasks": http.StatusForbidden,
	} {
		t.Run(scope, func(t *testing.T) {
			repository := &repositoryStub{createAsset: persistence.AssetRecord{
				ID: uuid.New(), UploadSessionID: uuid.New(), UploadExpiresAt: time.Now().Add(time.Minute),
			}}
			worker := auth.WorkerPrincipal{
				SubjectID: subjectID, WorkerID: workerID, WarehouseID: warehouseID,
				Scopes: map[string]struct{}{scope: {}},
			}
			server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, &storeStub{})
			body := fmt.Sprintf(`{"ownerType":"DRIVER_SHIFT","ownerId":"%s","clientReferenceId":"%s","warehouseId":"%s","context":"SHIFT_EVIDENCE","fileName":"shift.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s"}`,
				shiftID, photoID, warehouseID, strings.Repeat("a", 64))
			request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
			request.Header.Set("Authorization", "Bearer driver")
			request.Header.Set("Idempotency-Key", photoID.String())
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != expectedStatus {
				t.Fatalf("response = %d %s, want %d", response.Code, response.Body.String(), expectedStatus)
			}
			if expectedStatus == http.StatusCreated {
				command := repository.createCommand
				if command.OwnerType != persistence.OwnerTypeDriverShift || command.OwnerID != shiftID.String() ||
					command.WorkerID == nil || *command.WorkerID != workerID || command.Actor.SubjectID != workerID ||
					command.ClientReferenceID == nil || *command.ClientReferenceID != photoID || command.Kind != media.KindImage {
					t.Fatalf("driver-shift evidence command = %#v", command)
				}
			}
		})
	}

	worker := auth.WorkerPrincipal{
		SubjectID: subjectID, WorkerID: workerID, WarehouseID: warehouseID,
		Scopes: map[string]struct{}{"driver.tasks": {}},
	}
	server := newTestServer(t, &repositoryStub{}, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"DRIVER_SHIFT","ownerId":"%s","clientReferenceId":"%s","warehouseId":"%s","context":"SHIFT_EVIDENCE","fileName":"shift.mp4","contentType":"video/mp4","contentLength":128,"checksumSha256":"%s"}`,
		shiftID, photoID, warehouseID, strings.Repeat("a", 64))
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer driver")
	request.Header.Set("Idempotency-Key", photoID.String())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("video shift evidence response = %d %s", response.Code, response.Body.String())
	}

	for name, extraField := range map[string]string{
		"structured logistics identity": `,"documentId":"` + uuid.NewString() + `"`,
		"authorized customer subject":   `,"authorizedSubjectId":"` + subjectID.String() + `"`,
	} {
		t.Run(name, func(t *testing.T) {
			expectedCode := "MEDIA_INVALID_OWNER"
			if name == "authorized customer subject" {
				expectedCode = "MEDIA_INVALID_JSON"
			}
			server := newTestServer(t, &repositoryStub{}, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, &storeStub{})
			body := fmt.Sprintf(`{"ownerType":"DRIVER_SHIFT","ownerId":"%s","clientReferenceId":"%s","warehouseId":"%s","context":"SHIFT_EVIDENCE","fileName":"shift.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s"%s}`,
				shiftID, photoID, warehouseID, strings.Repeat("a", 64), extraField)
			request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
			request.Header.Set("Authorization", "Bearer driver")
			request.Header.Set("Idempotency-Key", photoID.String())
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), `"code":"`+expectedCode+`"`) {
				t.Fatalf("invalid shift owner claims response = %d %s", response.Code, response.Body.String())
			}
		})
	}
}

func TestDriverShiftListAndOriginalUseReaderProofAndRejectWorkerScope(t *testing.T) {
	warehouseID, shiftID, driverID, subjectID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	asset := persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeDriverShift, OwnerID: shiftID.String(),
		WarehouseID: warehouseID, Kind: media.KindImage, FileName: "shift.jpg",
		Status: media.StatusReady, Generation: 1,
	}
	driver := auth.WorkerPrincipal{
		SubjectID: subjectID, WorkerID: driverID, WarehouseID: warehouseID,
		Scopes: map[string]struct{}{"driver.tasks": {}},
	}
	query := "ownerType=DRIVER_SHIFT&ownerId=" + shiftID.String() +
		"&warehouseId=" + warehouseID.String() + "&context=SHIFT_EVIDENCE"

	t.Run("list", func(t *testing.T) {
		repository := &repositoryStub{
			driverListRecords: []persistence.AssetWithVariants{{Asset: asset}},
			workerListErr:     errors.New("task-board list must not serve driver shifts"),
		}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: driver}, &storeStub{})
		request := httptest.NewRequest(http.MethodGet, "/api/media/v1/assets?"+query, nil)
		request.Header.Set("Authorization", "Bearer driver")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK || repository.driverListCalls != 1 || repository.workerListCalls != 0 ||
			repository.driverListShiftID != shiftID || repository.driverListWarehouseID != warehouseID ||
			repository.driverListWorkerID != driverID {
			t.Fatalf("driver-shift list response=%d body=%s repository=%#v", response.Code, response.Body.String(), repository)
		}
	})

	t.Run("original", func(t *testing.T) {
		body := []byte("shift-original")
		original := &persistence.VariantRecord{
			Variant: media.VariantOriginal, ObjectKey: "private/shift-original", ObjectVersionID: "shift-v1",
			ContentType: "image/jpeg", SizeBytes: int64(len(body)),
		}
		repository := &repositoryStub{
			driverOriginalAsset: asset, driverOriginalVariant: original,
			workerOriginalErr: errors.New("task-board original must not serve driver shifts"),
		}
		store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
			VersionID: original.ObjectVersionID, SizeBytes: int64(len(body)), ContentType: original.ContentType,
		}}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: driver}, store)
		request := httptest.NewRequest(http.MethodGet,
			"/api/media/v1/assets/"+mediaID.String()+"/original?"+query+"&generation=1", nil)
		request.Header.Set("Authorization", "Bearer driver")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), body) ||
			repository.driverOriginalCalls != 1 || repository.workerOriginalCalls != 0 ||
			repository.driverReadShiftID != shiftID || repository.driverReadWarehouseID != warehouseID ||
			repository.driverReadWorkerID != driverID || repository.driverReadMediaID != mediaID ||
			repository.driverReadGeneration == nil || *repository.driverReadGeneration != 1 {
			t.Fatalf("driver-shift original response=%d body=%q repository=%#v", response.Code, response.Body.Bytes(), repository)
		}
	})

	t.Run("variant", func(t *testing.T) {
		body := []byte("shift-small")
		variant := &persistence.VariantRecord{
			Variant: media.VariantSmall, ObjectKey: "private/shift-small", ObjectVersionID: "shift-small-v1",
			ContentType: "image/webp", SizeBytes: int64(len(body)),
		}
		repository := &repositoryStub{
			driverCurrentAsset: asset, driverCurrentVariant: variant,
			workerCurrentErr: errors.New("task-board variant must not serve driver shifts"),
		}
		store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
			VersionID: variant.ObjectVersionID, SizeBytes: int64(len(body)), ContentType: variant.ContentType,
		}}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: driver}, store)
		request := httptest.NewRequest(http.MethodGet,
			"/api/media/v1/assets/"+mediaID.String()+"/variants/SMALL/content?"+query+"&generation=1", nil)
		request.Header.Set("Authorization", "Bearer driver")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), body) ||
			repository.driverCurrentCalls != 1 || repository.workerCurrentCalls != 0 ||
			repository.driverReadShiftID != shiftID || repository.driverReadWarehouseID != warehouseID ||
			repository.driverReadWorkerID != driverID || repository.driverReadMediaID != mediaID ||
			repository.driverReadGeneration == nil || *repository.driverReadGeneration != 1 ||
			repository.driverReadVariant != media.VariantSmall {
			t.Fatalf("driver-shift variant response=%d body=%q repository=%#v", response.Code, response.Body.Bytes(), repository)
		}
	})

	t.Run("worker app scope", func(t *testing.T) {
		worker := driver
		worker.Scopes = map[string]struct{}{"worker.tasks": {}}
		repository := &repositoryStub{driverListRecords: []persistence.AssetWithVariants{{Asset: asset}}}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, &storeStub{})
		request := httptest.NewRequest(http.MethodGet, "/api/media/v1/assets?"+query, nil)
		request.Header.Set("Authorization", "Bearer worker")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusForbidden || repository.driverListCalls != 0 || repository.workerListCalls != 0 {
			t.Fatalf("worker-app shift list response=%d body=%s driver calls=%d task calls=%d",
				response.Code, response.Body.String(), repository.driverListCalls, repository.workerListCalls)
		}
	})

	t.Run("missing proof", func(t *testing.T) {
		repository := &repositoryStub{
			driverListErr: persistence.ErrOwnerProofMissing,
			workerListErr: errors.New("task-board list must not serve driver shifts"),
		}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: driver}, &storeStub{})
		request := httptest.NewRequest(http.MethodGet, "/api/media/v1/assets?"+query, nil)
		request.Header.Set("Authorization", "Bearer driver")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), `"code":"MEDIA_OWNER_PROOF_REQUIRED"`) {
			t.Fatalf("missing shift proof response=%d body=%s", response.Code, response.Body.String())
		}
	})
}

func TestWorkerSourceMediaReadIsProofScopedAndRevocationFailsClosed(t *testing.T) {
	warehouseID, entryID, workerID, subjectID, sourceMediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte("source-original")
	asset := persistence.AssetRecord{ID: sourceMediaID, WarehouseID: warehouseID, FileName: "source.jpg", Status: media.StatusReady, Generation: 3}
	original := &persistence.VariantRecord{Variant: media.VariantOriginal, ObjectKey: "private/source", ObjectVersionID: "source-v3", ContentType: "image/jpeg", SizeBytes: int64(len(body))}
	worker := auth.WorkerPrincipal{SubjectID: subjectID, WorkerID: workerID, WarehouseID: warehouseID, Scopes: map[string]struct{}{"worker.tasks": {}}}
	path := "/api/media/v1/assets/" + sourceMediaID.String() + "/original?ownerType=TASK_BOARD_ENTRY&ownerId=" + entryID.String() +
		"&warehouseId=" + warehouseID.String() + "&context=WORK_RESULT&generation=3"

	t.Run("allowed exact source reference", func(t *testing.T) {
		repository := &repositoryStub{workerOriginalAsset: asset, workerOriginalVariant: original}
		store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{VersionID: "source-v3", SizeBytes: int64(len(body)), ContentType: "image/jpeg"}}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, store)
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.Header.Set("Authorization", "Bearer worker")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), body) {
			t.Fatalf("source response = %d %q", response.Code, response.Body.Bytes())
		}
		if repository.workerReadEntryID != entryID || repository.workerReadWarehouseID != warehouseID ||
			repository.workerReadWorkerID != workerID || repository.workerReadGeneration == nil || *repository.workerReadGeneration != 3 {
			t.Fatalf("worker source read scope = entry=%s warehouse=%s worker=%s generation=%v", repository.workerReadEntryID, repository.workerReadWarehouseID, repository.workerReadWorkerID, repository.workerReadGeneration)
		}
	})

	t.Run("proof revoked", func(t *testing.T) {
		repository := &repositoryStub{workerOriginalErr: persistence.ErrOwnerProofMissing}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, &storeStub{})
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.Header.Set("Authorization", "Bearer worker")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), `"code":"MEDIA_OWNER_PROOF_REQUIRED"`) {
			t.Fatalf("revoked source response = %d %s", response.Code, response.Body.String())
		}
	})
}

func TestUserTaskBoardSourceContentForwardsPinnedGeneration(t *testing.T) {
	warehouseID, entryID, sourceMediaID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte("task-board-source")
	asset := persistence.AssetRecord{
		ID: sourceMediaID, WarehouseID: warehouseID, FileName: "source.jpg",
		Kind: media.KindImage, Status: media.StatusReady, Generation: 1,
	}
	original := &persistence.VariantRecord{
		Variant: media.VariantOriginal, ObjectKey: "private/source-original", ObjectVersionID: "source-original-v1",
		ContentType: "image/jpeg", SizeBytes: int64(len(body)),
	}
	derived := &persistence.VariantRecord{
		Variant: media.VariantSmall, ObjectKey: "private/source-small", ObjectVersionID: "source-small-v1",
		ContentType: "image/webp", SizeBytes: int64(len(body)),
	}
	repository := &repositoryStub{
		originalAsset: asset, originalVariant: original,
		currentAsset: asset, currentVariant: derived,
	}
	user := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	base := "ownerType=TASK_BOARD_ENTRY&ownerId=" + entryID.String() +
		"&warehouseId=" + warehouseID.String() + "&context=WORK_RESULT&generation=1"

	t.Run("original", func(t *testing.T) {
		store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
			VersionID: original.ObjectVersionID, SizeBytes: int64(len(body)), ContentType: original.ContentType,
		}}
		server := newTestServer(t, repository, validatorStub{principal: user}, store)
		request := httptest.NewRequest(http.MethodGet,
			"/api/media/v1/assets/"+sourceMediaID.String()+"/original?"+base, nil)
		request.Header.Set("Authorization", "Bearer user")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), body) ||
			repository.originalReadCalls != 1 || repository.originalReadMediaID != sourceMediaID ||
			repository.originalReadOwnerType != persistence.OwnerTypeTaskBoardEntry ||
			repository.originalReadOwnerID != entryID.String() || repository.originalReadWarehouseID != warehouseID ||
			repository.originalReadGeneration == nil || *repository.originalReadGeneration != 1 {
			t.Fatalf("user source original response=%d scope=%#v", response.Code, repository)
		}
	})

	t.Run("derived variant", func(t *testing.T) {
		store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
			VersionID: derived.ObjectVersionID, SizeBytes: int64(len(body)), ContentType: derived.ContentType,
		}}
		server := newTestServer(t, repository, validatorStub{principal: user}, store)
		request := httptest.NewRequest(http.MethodGet,
			"/api/media/v1/assets/"+sourceMediaID.String()+"/variants/SMALL/content?"+base, nil)
		request.Header.Set("Authorization", "Bearer user")
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), body) ||
			repository.currentReadCalls != 1 || repository.currentReadMediaID != sourceMediaID ||
			repository.currentReadOwnerType != persistence.OwnerTypeTaskBoardEntry ||
			repository.currentReadOwnerID != entryID.String() || repository.currentReadWarehouseID != warehouseID ||
			repository.currentReadGeneration != 1 || repository.currentReadVariant != media.VariantSmall {
			t.Fatalf("user source variant response=%d scope=%#v", response.Code, repository)
		}
	})
}

func TestUserTaskBoardListUsesPinnedSourceGeneration(t *testing.T) {
	warehouseID, entryID, resultID, sourceID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{ownerRecords: []persistence.AssetWithVariants{
		{
			Asset: persistence.AssetRecord{
				ID: resultID, OwnerType: persistence.OwnerTypeTaskBoardEntry, OwnerID: entryID.String(),
				WarehouseID: warehouseID, Kind: media.KindImage, Status: media.StatusReady, Generation: 1,
			},
			Variants: []persistence.VariantRecord{{
				Variant: media.VariantSmall, ObjectVersionID: "result-small-v1", ContentType: "image/webp",
			}},
		},
		{
			// A source asset retains its original owner identity internally; its
			// public URLs must nevertheless remain in the task-board entry scope.
			Asset: persistence.AssetRecord{
				ID: sourceID, OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: uuid.NewString(),
				WarehouseID: warehouseID, Kind: media.KindImage, Status: media.StatusReady, Generation: 1,
			},
			Variants: []persistence.VariantRecord{{
				Variant: media.VariantSmall, ObjectVersionID: "source-small-v1", ContentType: "image/webp",
			}},
		},
	}}
	user := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	server := newTestServer(t, repository, validatorStub{principal: user}, &storeStub{})
	request := httptest.NewRequest(http.MethodGet,
		"/api/media/v1/assets?ownerType=TASK_BOARD_ENTRY&ownerId="+entryID.String()+
			"&warehouseId="+warehouseID.String()+"&context=WORK_RESULT", nil)
	request.Header.Set("Authorization", "Bearer user")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("task-board user list response = %d %s", response.Code, response.Body.String())
	}
	var body struct {
		Items []struct {
			ID         uuid.UUID `json:"id"`
			Generation int       `json:"generation"`
			Variants   []struct {
				ContentPath string `json:"contentPath"`
			} `json:"variants"`
		} `json:"items"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode task-board user list: %v", err)
	}
	if len(body.Items) != 2 {
		t.Fatalf("task-board user list = %#v", body)
	}
	for _, item := range body.Items {
		if item.ID != sourceID {
			continue
		}
		if item.Generation != 1 || len(item.Variants) != 1 ||
			!strings.Contains(item.Variants[0].ContentPath, "ownerType=TASK_BOARD_ENTRY") ||
			!strings.Contains(item.Variants[0].ContentPath, "ownerId="+entryID.String()) ||
			!strings.Contains(item.Variants[0].ContentPath, "generation=1") ||
			!strings.Contains(item.Variants[0].ContentPath, "context=WORK_RESULT") {
			t.Fatalf("pinned source list item = %#v", item)
		}
		return
	}
	t.Fatalf("pinned source %s missing from %#v", sourceID, body)
}

func TestWorkerListUsesOneCurrentProofScopedRead(t *testing.T) {
	warehouseID, entryID, workerID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	worker := auth.WorkerPrincipal{
		SubjectID: subjectID, WorkerID: workerID, WarehouseID: warehouseID,
		Scopes: map[string]struct{}{"worker.tasks": {}},
	}
	path := "/api/media/v1/assets?ownerType=TASK_BOARD_ENTRY&ownerId=" + entryID.String() +
		"&warehouseId=" + warehouseID.String() + "&context=WORK_RESULT"
	records := []persistence.AssetWithVariants{{
		Asset: persistence.AssetRecord{
			ID: uuid.New(), OwnerType: persistence.OwnerTypeTaskBoardEntry, OwnerID: entryID.String(),
			WarehouseID: warehouseID, Status: media.StatusReady, Generation: 1,
		},
	}}

	t.Run("active proof", func(t *testing.T) {
		repository := &repositoryStub{workerListRecords: records}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, &storeStub{})
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.Header.Set("Authorization", "Bearer worker")
		response := httptest.NewRecorder()

		server.Handler().ServeHTTP(response, request)

		if response.Code != http.StatusOK || repository.workerListCalls != 1 || repository.workerAuthorizeCalls != 0 ||
			repository.workerListEntryID != entryID || repository.workerListWarehouseID != warehouseID ||
			repository.workerListWorkerID != workerID {
			t.Fatalf("worker list response=%d calls=%d authorize=%d entry=%s warehouse=%s worker=%s body=%s",
				response.Code, repository.workerListCalls, repository.workerAuthorizeCalls,
				repository.workerListEntryID, repository.workerListWarehouseID, repository.workerListWorkerID,
				response.Body.String())
		}
	})

	t.Run("proof revoked before list", func(t *testing.T) {
		repository := &repositoryStub{workerListErr: persistence.ErrOwnerProofMissing}
		server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, &storeStub{})
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.Header.Set("Authorization", "Bearer worker")
		response := httptest.NewRecorder()

		server.Handler().ServeHTTP(response, request)

		if response.Code != http.StatusForbidden || repository.workerListCalls != 1 ||
			!strings.Contains(response.Body.String(), `"code":"MEDIA_OWNER_PROOF_REQUIRED"`) {
			t.Fatalf("revoked worker list response=%d calls=%d body=%s",
				response.Code, repository.workerListCalls, response.Body.String())
		}
	})
}

func TestWorkerUploadStagesRecheckCurrentTaskBoardProofBeforeStorage(t *testing.T) {
	warehouseID, entryID, workerID, subjectID, sessionID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	checksum := strings.Repeat("a", 64)
	asset := persistence.AssetRecord{
		ID: uuid.New(), OwnerType: persistence.OwnerTypeTaskBoardEntry, OwnerID: entryID.String(), WarehouseID: warehouseID,
		ContentType: "image/jpeg", ExpectedLength: 8, ExpectedChecksum: checksum, UploadExpiresAt: time.Now().Add(time.Minute),
	}
	worker := auth.WorkerPrincipal{SubjectID: subjectID, WorkerID: workerID, WarehouseID: warehouseID, Scopes: map[string]struct{}{"worker.tasks": {}}}
	for _, testCase := range []struct {
		name   string
		method string
		path   string
		body   string
	}{
		{name: "content", method: http.MethodPut, path: "/api/media/v1/upload-sessions/" + sessionID.String() + "/content", body: "12345678"},
		{name: "finalize", method: http.MethodPost, path: "/api/media/v1/upload-sessions/" + sessionID.String() + "/complete", body: `{"objectVersionId":"v1","etag":"etag","checksumSha256":"` + checksum + `"}`},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			repository := &repositoryStub{sessionAsset: asset, workerAuthorizeErr: persistence.ErrOwnerProofMissing}
			store := &storeStub{}
			server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: worker}, store)
			request := httptest.NewRequest(testCase.method, testCase.path, strings.NewReader(testCase.body))
			request.Header.Set("Authorization", "Bearer worker")
			request.Header.Set("Idempotency-Key", uuid.NewString())
			if testCase.name == "content" {
				request.Header.Set("Content-Type", "image/jpeg")
			}
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), `"code":"MEDIA_OWNER_PROOF_REQUIRED"`) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if store.putCalls != 0 || store.statCalls != 0 || store.getCalls != 0 || repository.finalizeCalls != 0 {
				t.Fatalf("rejected proof touched media: put=%d stat=%d get=%d finalize=%d", store.putCalls, store.statCalls, store.getCalls, repository.finalizeCalls)
			}
		})
	}
}

func TestDriverUploadStagesUseDriverShiftAuthorization(t *testing.T) {
	warehouseID, shiftID, driverID, subjectID, sessionID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	checksum := strings.Repeat("a", 64)
	asset := persistence.AssetRecord{
		ID: uuid.New(), OwnerType: persistence.OwnerTypeDriverShift, OwnerID: shiftID.String(), WarehouseID: warehouseID,
		ContentType: "image/jpeg", ExpectedLength: 8, ExpectedChecksum: checksum, UploadExpiresAt: time.Now().Add(time.Minute),
	}
	driver := auth.WorkerPrincipal{
		SubjectID: subjectID, WorkerID: driverID, WarehouseID: warehouseID,
		Scopes: map[string]struct{}{"driver.tasks": {}},
	}
	for _, testCase := range []struct {
		name   string
		method string
		path   string
		body   string
	}{
		{name: "content", method: http.MethodPut, path: "/api/media/v1/upload-sessions/" + sessionID.String() + "/content", body: "12345678"},
		{name: "finalize", method: http.MethodPost, path: "/api/media/v1/upload-sessions/" + sessionID.String() + "/complete", body: `{"objectVersionId":"v1","etag":"etag","checksumSha256":"` + checksum + `"}`},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			repository := &repositoryStub{
				sessionAsset:       asset,
				driverAuthorizeErr: persistence.ErrOwnerProofMissing,
				workerAuthorizeErr: errors.New("task-board authorization must not serve driver shifts"),
			}
			store := &storeStub{}
			server := newTestServer(t, repository, validatorStub{err: auth.ErrForbidden, workerPrincipal: driver}, store)
			request := httptest.NewRequest(testCase.method, testCase.path, strings.NewReader(testCase.body))
			request.Header.Set("Authorization", "Bearer driver")
			request.Header.Set("Idempotency-Key", uuid.NewString())
			if testCase.name == "content" {
				request.Header.Set("Content-Type", "image/jpeg")
			}
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), `"code":"MEDIA_OWNER_PROOF_REQUIRED"`) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if repository.driverAuthorizeCalls != 1 || repository.workerAuthorizeCalls != 0 ||
				repository.driverShiftID != shiftID || repository.driverWarehouseID != warehouseID ||
				repository.driverWorkerID != driverID {
				t.Fatalf("authorization repository = %#v", repository)
			}
			if store.putCalls != 0 || store.statCalls != 0 || store.getCalls != 0 || repository.finalizeCalls != 0 {
				t.Fatalf("rejected proof touched media: put=%d stat=%d get=%d finalize=%d",
					store.putCalls, store.statCalls, store.getCalls, repository.finalizeCalls)
			}
		})
	}
}

func TestCabinUploadUsesTheWarehouseOwnerScope(t *testing.T) {
	warehouseID, cabinID, subjectID := uuid.New(), uuid.New(), uuid.New()
	folderID := uuid.New()
	repository := &repositoryStub{createAsset: persistence.AssetRecord{
		ID: uuid.New(), UploadSessionID: uuid.New(), UploadExpiresAt: time.Now().Add(time.Minute),
	}}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"CABIN","ownerId":"%s","warehouseId":"%s","context":"WAREHOUSE","folderId":"%s","fileName":"cabin.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s","sortOrder":0}`,
		cabinID, warehouseID, folderID, strings.Repeat("a", 64))
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated {
		t.Fatalf("cabin upload response = %d %s", response.Code, response.Body.String())
	}
	if repository.createCalls != 1 || repository.createCommand.OwnerType != persistence.OwnerTypeCabin ||
		repository.createCommand.OwnerID != cabinID.String() || repository.createCommand.WarehouseID != warehouseID ||
		repository.createCommand.FolderID != folderID {
		t.Fatalf("cabin create command = %#v, calls=%d", repository.createCommand, repository.createCalls)
	}
}

func TestLogisticsTransferUploadDerivesOwnerAndRequiresDestinationWarehouse(t *testing.T) {
	destinationWarehouseID, sourceWarehouseID := uuid.New(), uuid.New()
	documentID, lineID, subjectID := uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{createAsset: persistence.AssetRecord{
		ID: uuid.New(), UploadSessionID: uuid.New(), UploadExpiresAt: time.Now().Add(time.Minute),
	}}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: destinationWarehouseID, Level: auth.Edit}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"LOGISTICS_TRANSFER","documentId":"%s","lineId":"%s","warehouseId":"%s","context":"TRANSFER","fileName":"arrival.jpg","contentType":"image/jpeg","contentLength":128,"checksumSha256":"%s"}`,
		documentID, lineID, destinationWarehouseID, strings.Repeat("a", 64))
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated {
		t.Fatalf("destination upload response = %d %s", response.Code, response.Body.String())
	}
	if repository.createCalls != 1 || repository.createCommand.OwnerType != persistence.OwnerTypeLogisticsTransfer ||
		repository.createCommand.OwnerID != persistence.LogisticsOwnerID(documentID, lineID) ||
		repository.createCommand.WarehouseID != destinationWarehouseID {
		t.Fatalf("logistics destination command = %#v, calls=%d", repository.createCommand, repository.createCalls)
	}

	wrongWarehouseBody := strings.Replace(body, destinationWarehouseID.String(), sourceWarehouseID.String(), 1)
	wrongRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(wrongWarehouseBody))
	wrongRequest.Header.Set("Authorization", "Bearer test")
	wrongRequest.Header.Set("Idempotency-Key", uuid.NewString())
	wrongResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(wrongResponse, wrongRequest)
	if wrongResponse.Code != http.StatusForbidden || repository.createCalls != 1 {
		t.Fatalf("source warehouse response = %d %s; create calls=%d", wrongResponse.Code,
			wrongResponse.Body.String(), repository.createCalls)
	}

	compositeBody := strings.Replace(body, `"documentId":"`+documentID.String()+`","lineId":"`+lineID.String()+`"`,
		`"ownerId":"`+persistence.LogisticsOwnerID(documentID, lineID)+`"`, 1)
	compositeRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/upload-sessions", strings.NewReader(compositeBody))
	compositeRequest.Header.Set("Authorization", "Bearer test")
	compositeRequest.Header.Set("Idempotency-Key", uuid.NewString())
	compositeResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(compositeResponse, compositeRequest)
	if compositeResponse.Code != http.StatusBadRequest || repository.createCalls != 1 {
		t.Fatalf("browser composite response = %d %s; create calls=%d", compositeResponse.Code,
			compositeResponse.Body.String(), repository.createCalls)
	}
}

func TestPublicOwnerScopeRequiresCanonicalOwnerContextPair(t *testing.T) {
	for _, testCase := range []struct {
		ownerType string
		context   string
		valid     bool
	}{
		{ownerType: persistence.OwnerTypeInventoryFinding, context: persistence.ViewerContextInspection, valid: true},
		{ownerType: persistence.OwnerTypeCabin, context: persistence.ViewerContextWarehouse, valid: true},
		{ownerType: persistence.OwnerTypeMaintenanceEstimate, context: persistence.ViewerContextEstimate, valid: true},
		{ownerType: persistence.OwnerTypeMaintenanceRepair, context: persistence.ViewerContextRepair, valid: true},
		{ownerType: persistence.OwnerTypeMaintenanceAcceptance, context: persistence.ViewerContextAcceptance, valid: true},
		{ownerType: persistence.OwnerTypeMaintenanceCatalogNode, context: persistence.ViewerContextCatalog, valid: true},
		{ownerType: persistence.OwnerTypeLogisticsReturn, context: persistence.ViewerContextReturnInspection, valid: true},
		{ownerType: persistence.OwnerTypeLogisticsShipment, context: persistence.ViewerContextShipment, valid: true},
		{ownerType: persistence.OwnerTypeLogisticsTransfer, context: persistence.ViewerContextTransfer, valid: true},
		{ownerType: persistence.OwnerTypeDriverShift, context: persistence.ViewerContextShiftEvidence, valid: true},
		{ownerType: persistence.OwnerTypeInventoryFinding, context: persistence.ViewerContextWarehouse},
		{ownerType: persistence.OwnerTypeCabin, context: persistence.ViewerContextInspection},
		{ownerType: persistence.OwnerTypeMaintenanceEstimate, context: persistence.ViewerContextRepair},
	} {
		if got := validPublicOwnerScope(testCase.ownerType, testCase.context); got != testCase.valid {
			t.Errorf("validPublicOwnerScope(%q, %q) = %v, want %v", testCase.ownerType, testCase.context, got, testCase.valid)
		}
	}
}

func TestCabinCoverBatchIsBoundedWarehouseScopedAndCountsAssets(t *testing.T) {
	warehouseID, foreignWarehouseID := uuid.New(), uuid.New()
	firstCabinID, secondCabinID := uuid.New(), uuid.New()
	firstMediaID, secondMediaID := uuid.New(), uuid.New()
	width, height := 360, 240
	firstVariant := persistence.VariantRecord{
		Variant: media.VariantSmall, ObjectVersionID: "small-version-1", ContentType: "image/webp",
		SizeBytes: 32, Width: &width, Height: &height, Checksum: strings.Repeat("a", 64),
	}
	secondVariant := persistence.VariantRecord{
		Variant: media.VariantSmall, ObjectVersionID: "small-version-2", ContentType: "image/webp",
		SizeBytes: 48, Width: &width, Height: &height, Checksum: strings.Repeat("b", 64),
	}
	repository := &repositoryStub{cabinCoverRecords: []persistence.CabinCoverRecord{
		{
			CabinID: firstCabinID, PhotoCount: 3,
			MediaID: firstMediaID, Generation: 3, Variant: &firstVariant,
			Previews: []persistence.CabinPreviewRecord{
				{MediaID: firstMediaID, Generation: 3, Variant: firstVariant},
				{MediaID: secondMediaID, Generation: 1, Variant: secondVariant},
			},
		},
		{CabinID: secondCabinID, PhotoCount: 1},
	}}
	principal := auth.Principal{
		SubjectID: uuid.New(), Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	body := fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s","%s"]}`,
		warehouseID, firstCabinID, secondCabinID)
	request := httptest.NewRequest(http.MethodPost, "/api/media/v1/cabin-covers", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("cabin covers response = %d %s", response.Code, response.Body.String())
	}
	if repository.cabinCoverCalls != 1 || repository.cabinCoverWarehouseID != warehouseID ||
		len(repository.cabinCoverIDs) != 2 || repository.cabinCoverIDs[0] != firstCabinID ||
		repository.cabinCoverIDs[1] != secondCabinID {
		t.Fatalf("cabin cover repository call = %d %s %#v", repository.cabinCoverCalls,
			repository.cabinCoverWarehouseID, repository.cabinCoverIDs)
	}
	var payload struct {
		Items []struct {
			CabinID    uuid.UUID        `json:"cabinId"`
			PhotoCount int64            `json:"photoCount"`
			Cover      map[string]any   `json:"cover"`
			Previews   []map[string]any `json:"previews"`
		} `json:"items"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode cabin covers: %v", err)
	}
	if len(payload.Items) != 2 || payload.Items[0].CabinID != firstCabinID ||
		payload.Items[0].PhotoCount != 3 || payload.Items[0].Cover["kind"] != "SMALL" ||
		len(payload.Items[0].Previews) != 2 ||
		payload.Items[0].Previews[0]["mediaId"] != firstMediaID.String() ||
		payload.Items[0].Previews[1]["mediaId"] != secondMediaID.String() ||
		payload.Items[1].CabinID != secondCabinID || payload.Items[1].PhotoCount != 1 ||
		payload.Items[1].Cover != nil || len(payload.Items[1].Previews) != 0 {
		t.Fatalf("cabin cover payload = %#v", payload.Items)
	}
	path, _ := payload.Items[0].Cover["contentPath"].(string)
	if !strings.Contains(path, "/api/media/v1/assets/"+firstMediaID.String()+"/variants/SMALL/content?") ||
		!strings.Contains(path, "ownerId="+firstCabinID.String()) || strings.Contains(path, "ORIGINAL") {
		t.Fatalf("cabin cover content path = %q", path)
	}
	if payload.Items[0].Cover["contentPath"] != payload.Items[0].Previews[0]["contentPath"] {
		t.Fatalf("cover is not first preview: %#v", payload.Items[0])
	}

	forbiddenBody := fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s"]}`,
		foreignWarehouseID, firstCabinID)
	forbiddenRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/cabin-covers", strings.NewReader(forbiddenBody))
	forbiddenRequest.Header.Set("Authorization", "Bearer test")
	forbiddenResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(forbiddenResponse, forbiddenRequest)
	if forbiddenResponse.Code != http.StatusForbidden || repository.cabinCoverCalls != 1 {
		t.Fatalf("foreign warehouse cover response = %d %s; calls=%d", forbiddenResponse.Code,
			forbiddenResponse.Body.String(), repository.cabinCoverCalls)
	}

	duplicateRequest := httptest.NewRequest(http.MethodPost, "/api/media/v1/cabin-covers",
		strings.NewReader(fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s","%s"]}`,
			warehouseID, firstCabinID, firstCabinID)))
	duplicateRequest.Header.Set("Authorization", "Bearer test")
	duplicateResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(duplicateResponse, duplicateRequest)
	if duplicateResponse.Code != http.StatusBadRequest || repository.cabinCoverCalls != 1 {
		t.Fatalf("duplicate cover response = %d %s; calls=%d", duplicateResponse.Code,
			duplicateResponse.Body.String(), repository.cabinCoverCalls)
	}
}

func TestUploadContentStreamsFinalizesAndReplaysExactlyOnce(t *testing.T) {
	warehouseID, cabinID, subjectID, sessionID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte{0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01}
	sum := sha256.Sum256(body)
	checksum := hex.EncodeToString(sum[:])
	asset := persistence.AssetRecord{
		ID: mediaID, OwnerType: persistence.OwnerTypeCabin, OwnerID: cabinID.String(),
		WarehouseID: warehouseID, SourceObjectKey: "private/ingress/source.jpg",
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
	invalidations := &invalidationRecorder{}
	server.invalidations = invalidations
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
	events := invalidations.snapshot()
	if len(events) != 1 || events[0].WarehouseID != warehouseID || events[0].MediaID != mediaID ||
		events[0].OwnerType != persistence.OwnerTypeCabin || events[0].OwnerID != cabinID.String() ||
		events[0].Scope != "MEDIA_CHANGED" || events[0].Revision != 2 {
		t.Fatalf("upload/finalize invalidations = %#v", events)
	}
}

func TestUploadContentAcceptsChunkedBodyOnlyWhenExact(t *testing.T) {
	warehouseID, subjectID := uuid.New(), uuid.New()
	exactBody := []byte{0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01}
	exactSum := sha256.Sum256(exactBody)
	exactChecksum := hex.EncodeToString(exactSum[:])
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	tests := []struct {
		name             string
		body             []byte
		expectedChecksum string
		contentLength    int64
		wantStatus       int
		wantPutCalls     int
		wantFinalize     int
	}{
		{name: "chunked exact", body: exactBody, expectedChecksum: exactChecksum, contentLength: -1,
			wantStatus: http.StatusCreated, wantPutCalls: 1, wantFinalize: 1},
		{name: "chunked short", body: exactBody[:len(exactBody)-1], expectedChecksum: exactChecksum, contentLength: -1,
			wantStatus: http.StatusConflict, wantPutCalls: 1},
		{name: "chunked long", body: append(append([]byte{}, exactBody...), 0xff), expectedChecksum: exactChecksum, contentLength: -1,
			wantStatus: http.StatusConflict, wantPutCalls: 1},
		{name: "chunked checksum mismatch", body: exactBody, expectedChecksum: strings.Repeat("0", 64), contentLength: -1,
			wantStatus: http.StatusConflict, wantPutCalls: 1},
		{name: "known mismatched length", body: exactBody, expectedChecksum: exactChecksum, contentLength: int64(len(exactBody) + 1),
			wantStatus: http.StatusConflict},
	}
	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			sessionID, mediaID := uuid.New(), uuid.New()
			asset := persistence.AssetRecord{
				ID: mediaID, WarehouseID: warehouseID, SourceObjectKey: "private/ingress/chunked.jpg",
				ContentType: "image/jpeg", ExpectedLength: int64(len(exactBody)), ExpectedChecksum: testCase.expectedChecksum,
				UploadSessionID: sessionID, UploadExpiresAt: time.Now().Add(time.Minute),
			}
			metadata := media.ObjectMetadata{
				SizeBytes: int64(len(exactBody)), ContentType: "image/jpeg", ETag: "etag-chunked", VersionID: "version-chunked",
				UserMetadata: map[string]string{"sha256": testCase.expectedChecksum},
			}
			finalized := asset
			finalized.SourceVersionID = metadata.VersionID
			finalized.SourceETag = metadata.ETag
			finalized.SourceChecksum = testCase.expectedChecksum
			store := &storeStub{putMetadata: metadata, statMetadata: metadata, objectBody: testCase.body}
			repository := &repositoryStub{sessionAsset: asset, finalizeAsset: finalized}
			server := newTestServer(t, repository, validatorStub{principal: principal}, store)

			request := httptest.NewRequest(http.MethodPut,
				"/api/media/v1/upload-sessions/"+sessionID.String()+"/content", bytes.NewReader(testCase.body))
			request.ContentLength = testCase.contentLength
			if testCase.contentLength == -1 {
				request.TransferEncoding = []string{"chunked"}
			}
			request.Header.Set("Authorization", "Bearer test")
			request.Header.Set("Idempotency-Key", uuid.NewString())
			request.Header.Set("Content-Type", "image/jpeg")
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)

			if response.Code != testCase.wantStatus {
				t.Fatalf("response = %d %s, want %d", response.Code, response.Body.String(), testCase.wantStatus)
			}
			if testCase.wantStatus == http.StatusConflict && !strings.Contains(response.Body.String(), `"code":"MEDIA_OBJECT_MISMATCH"`) {
				t.Fatalf("conflict response = %s", response.Body.String())
			}
			if store.putCalls != testCase.wantPutCalls || repository.finalizeCalls != testCase.wantFinalize {
				t.Fatalf("put calls = %d, finalize calls = %d; want %d, %d", store.putCalls, repository.finalizeCalls, testCase.wantPutCalls, testCase.wantFinalize)
			}
			if len(store.putBody) > len(exactBody) {
				t.Fatalf("streamed %d bytes, authorized %d", len(store.putBody), len(exactBody))
			}
			if testCase.wantStatus == http.StatusCreated && (store.statCalls != 1 || store.getCalls != 1) {
				t.Fatalf("immutable verification calls stat=%d get=%d", store.statCalls, store.getCalls)
			}
			if testCase.wantStatus != http.StatusCreated && (store.statCalls != 0 || store.getCalls != 0) {
				t.Fatalf("failed upload reached immutable verification: stat=%d get=%d", store.statCalls, store.getCalls)
			}
		})
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
	folderID := uuid.New()
	asset := persistence.AssetRecord{
		ID: mediaID, FolderID: folderID, OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
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
	if !strings.Contains(response.Body.String(), `"folderId":"`+folderID.String()+`"`) {
		t.Fatalf("logical folder missing: %s", response.Body.String())
	}
	for _, forbidden := range []string{"private/minio-secret-key", "secret-version", "http://", "https://", `"url"`} {
		if strings.Contains(strings.ToLower(response.Body.String()), strings.ToLower(forbidden)) {
			t.Fatalf("list response leaked %q: %s", forbidden, response.Body.String())
		}
	}
}

func TestCustomerRentalListsOnlyShipmentOwnerMedia(t *testing.T) {
	documentID, lineID, warehouseID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	ownerID := persistence.LogisticsOwnerID(documentID, lineID)
	repository := &repositoryStub{ownerRecords: []persistence.AssetWithVariants{{Asset: persistence.AssetRecord{
		ID: uuid.New(), FolderID: uuid.New(), OwnerType: persistence.OwnerTypeLogisticsShipment,
		OwnerID: ownerID, WarehouseID: warehouseID, FileName: "delivery.jpg",
		ContentType: "image/jpeg", Kind: media.KindImage, Status: media.StatusUploading,
		Version: 1, CreatedAt: time.Now(),
	}}}}
	principal := auth.Principal{
		SubjectID: subjectID, Role: "CUSTOMER", ClientID: "rwms-customer-android",
		Scopes: map[string]struct{}{"openid": {}, "profile": {}, "offline_access": {}, "customer.rental": {}},
	}
	server := newTestServer(t, repository, validatorStub{principal: principal}, &storeStub{})
	path := fmt.Sprintf("/api/media/v1/assets?ownerType=LOGISTICS_SHIPMENT&documentId=%s&lineId=%s&warehouseId=%s&context=SHIPMENT",
		documentID, lineID, warehouseID)
	request := httptest.NewRequest(http.MethodGet, path, nil)
	request.Header.Set("Authorization", "Bearer test")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"items":[{`) {
		t.Fatalf("customer shipment list response = %d %s", response.Code, response.Body.String())
	}

	foreignPath := strings.Replace(path, "LOGISTICS_SHIPMENT", "LOGISTICS_RETURN", 1)
	foreignPath = strings.Replace(foreignPath, "SHIPMENT", "RETURN_INSPECTION", 1)
	foreignRequest := httptest.NewRequest(http.MethodGet, foreignPath, nil)
	foreignRequest.Header.Set("Authorization", "Bearer test")
	foreignResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(foreignResponse, foreignRequest)
	if foreignResponse.Code != http.StatusForbidden {
		t.Fatalf("customer non-shipment list response = %d %s", foreignResponse.Code, foreignResponse.Body.String())
	}

	overScoped := principal
	overScoped.Scopes = map[string]struct{}{"customer.rental": {}, "rwms.read": {}}
	overScopedServer := newTestServer(t, repository, validatorStub{principal: overScoped}, &storeStub{})
	overScopedRequest := httptest.NewRequest(http.MethodGet, path, nil)
	overScopedRequest.Header.Set("Authorization", "Bearer test")
	overScopedResponse := httptest.NewRecorder()
	overScopedServer.Handler().ServeHTTP(overScopedResponse, overScopedRequest)
	if overScopedResponse.Code != http.StatusForbidden {
		t.Fatalf("over-scoped customer response = %d %s", overScopedResponse.Code,
			overScopedResponse.Body.String())
	}
}

func TestCustomerProfileMediaListReadDeleteAreCustomerOnly(t *testing.T) {
	profileID, warehouseID, subjectID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte("profile-avatar")
	asset := persistence.AssetRecord{
		ID: mediaID, FolderID: mediaID, OwnerType: persistence.OwnerTypeLogisticsCustomerProfile,
		OwnerID: profileID.String(), WarehouseID: warehouseID, FileName: "avatar.webp",
		ContentType: "image/webp", Kind: media.KindImage, Status: media.StatusReady,
		Version: 3, Generation: 2, CreatedAt: time.Now(),
	}
	small := persistence.VariantRecord{
		Variant: media.VariantSmall, ObjectKey: "private/profile-small",
		ObjectVersionID: "profile-small-v2", ContentType: "image/webp", SizeBytes: int64(len(body)),
	}
	original := persistence.VariantRecord{
		Variant: media.VariantOriginal, ObjectKey: "private/profile-original",
		ObjectVersionID: "profile-original-v2", ContentType: "image/webp", SizeBytes: int64(len(body)),
	}
	repository := &repositoryStub{
		ownerRecords:  []persistence.AssetWithVariants{{Asset: asset, Variants: []persistence.VariantRecord{small}}},
		originalAsset: asset, originalVariant: &original,
		deleteAsset: func() persistence.AssetRecord {
			deleted := asset
			deleted.Status, deleted.Version = media.StatusDeleted, 4
			return deleted
		}(),
	}
	customer := auth.Principal{
		SubjectID: subjectID, Role: "CUSTOMER", ClientID: "rwms-customer-android",
		Scopes: map[string]struct{}{"openid": {}, "profile": {}, "offline_access": {}, "customer.rental": {}},
	}
	store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
		VersionID: original.ObjectVersionID, SizeBytes: int64(len(body)), ContentType: original.ContentType,
	}}
	server := newTestServer(t, repository, validatorStub{principal: customer}, store)
	query := fmt.Sprintf("ownerType=LOGISTICS_CUSTOMER_PROFILE&ownerId=%s&warehouseId=%s&context=PROFILE_AVATAR",
		profileID, warehouseID)

	listRequest := httptest.NewRequest(http.MethodGet, "/api/media/v1/assets?"+query, nil)
	listRequest.Header.Set("Authorization", "Bearer customer")
	listResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(listResponse, listRequest)
	if listResponse.Code != http.StatusOK ||
		!strings.Contains(listResponse.Body.String(), "context=PROFILE_AVATAR") ||
		!strings.Contains(listResponse.Body.String(), "ownerId="+profileID.String()) {
		t.Fatalf("profile list = %d %s", listResponse.Code, listResponse.Body.String())
	}

	readRequest := httptest.NewRequest(http.MethodGet,
		"/api/media/v1/assets/"+mediaID.String()+"/original?"+query, nil)
	readRequest.Header.Set("Authorization", "Bearer customer")
	readResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(readResponse, readRequest)
	if readResponse.Code != http.StatusOK || !bytes.Equal(readResponse.Body.Bytes(), body) {
		t.Fatalf("profile original = %d %q", readResponse.Code, readResponse.Body.Bytes())
	}

	deleteRequest := httptest.NewRequest(http.MethodPost,
		"/api/media/v1/assets/"+mediaID.String()+"/deletion?"+query,
		strings.NewReader(`{"expectedVersion":3}`))
	deleteRequest.Header.Set("Authorization", "Bearer customer")
	deleteRequest.Header.Set("Idempotency-Key", uuid.NewString())
	deleteResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(deleteResponse, deleteRequest)
	if deleteResponse.Code != http.StatusOK || repository.deleteCalls != 1 ||
		repository.deleteCommand.AuthorizedSubjectID == nil ||
		*repository.deleteCommand.AuthorizedSubjectID != subjectID {
		t.Fatalf("profile delete = %d %s; command=%#v", deleteResponse.Code,
			deleteResponse.Body.String(), repository.deleteCommand)
	}

	manager := auth.Principal{
		SubjectID: uuid.New(), Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	managerServer := newTestServer(t, &repositoryStub{}, validatorStub{principal: manager}, &storeStub{})
	managerRequest := httptest.NewRequest(http.MethodGet, "/api/media/v1/assets?"+query, nil)
	managerRequest.Header.Set("Authorization", "Bearer manager")
	managerResponse := httptest.NewRecorder()
	managerServer.Handler().ServeHTTP(managerResponse, managerRequest)
	if managerResponse.Code != http.StatusForbidden {
		t.Fatalf("manager profile read = %d %s", managerResponse.Code, managerResponse.Body.String())
	}

	worker := auth.WorkerPrincipal{
		SubjectID: uuid.New(), WorkerID: uuid.New(), WarehouseID: warehouseID,
		Scopes: map[string]struct{}{"worker.tasks": {}},
	}
	workerServer := newTestServer(t, &repositoryStub{}, validatorStub{
		err: auth.ErrForbidden, workerPrincipal: worker,
	}, &storeStub{})
	workerRequest := httptest.NewRequest(http.MethodGet, "/api/media/v1/assets?"+query, nil)
	workerRequest.Header.Set("Authorization", "Bearer worker")
	workerResponse := httptest.NewRecorder()
	workerServer.Handler().ServeHTTP(workerResponse, workerRequest)
	if workerResponse.Code != http.StatusForbidden {
		t.Fatalf("worker profile read = %d %s", workerResponse.Code, workerResponse.Body.String())
	}
}

func TestSafeVariantContentPathKeepsTheCanonicalOwnerContext(t *testing.T) {
	warehouseID, ownerID := uuid.New(), uuid.New()
	for _, testCase := range []struct {
		ownerType string
		context   string
	}{
		{ownerType: persistence.OwnerTypeInventoryFinding, context: persistence.ViewerContextInspection},
		{ownerType: persistence.OwnerTypeCabin, context: persistence.ViewerContextWarehouse},
	} {
		record := persistence.AssetWithVariants{
			Asset: persistence.AssetRecord{ID: uuid.New(), Kind: media.KindImage, Status: media.StatusReady, Generation: 1},
			Variants: []persistence.VariantRecord{{
				Variant: media.VariantSmall, ObjectVersionID: "version-1", ContentType: "image/webp",
			}},
		}
		variants := safeVariants(record, testCase.ownerType, ownerID.String(), warehouseID)
		if len(variants) != 1 {
			t.Fatalf("%s variants = %#v", testCase.ownerType, variants)
		}
		contentPath, ok := variants[0].(map[string]any)["contentPath"].(string)
		if !ok || !strings.Contains(contentPath, "ownerType="+testCase.ownerType) ||
			!strings.Contains(contentPath, "context="+testCase.context) {
			t.Errorf("%s content path = %q", testCase.ownerType, contentPath)
		}
	}
}

func TestLogisticsVariantContentPathUsesStructuredIdentityOnly(t *testing.T) {
	documentID, lineID, warehouseID := uuid.New(), uuid.New(), uuid.New()
	record := persistence.AssetWithVariants{
		Asset: persistence.AssetRecord{ID: uuid.New(), Kind: media.KindImage, Status: media.StatusReady, Generation: 2},
		Variants: []persistence.VariantRecord{{
			Variant: media.VariantMedium, ObjectVersionID: "version-2", ContentType: "image/webp",
		}},
	}
	variants := safeVariants(record, persistence.OwnerTypeLogisticsTransfer,
		persistence.LogisticsOwnerID(documentID, lineID), warehouseID)
	if len(variants) != 1 {
		t.Fatalf("logistics variants = %#v", variants)
	}
	contentPath, _ := variants[0].(map[string]any)["contentPath"].(string)
	for _, required := range []string{
		"ownerType=LOGISTICS_TRANSFER", "documentId=" + documentID.String(),
		"lineId=" + lineID.String(), "context=TRANSFER",
	} {
		if !strings.Contains(contentPath, required) {
			t.Errorf("structured content path %q does not contain %q", contentPath, required)
		}
	}
	if strings.Contains(contentPath, "ownerId=") || strings.Contains(contentPath,
		persistence.LogisticsOwnerID(documentID, lineID)) {
		t.Fatalf("structured content path exposed composite identity: %q", contentPath)
	}
}

func TestReadyVideoExposesOnlyCompressedPlaybackVariant(t *testing.T) {
	warehouseID, ownerID, mediaID := uuid.New(), uuid.New(), uuid.New()
	record := persistence.AssetWithVariants{
		Asset: persistence.AssetRecord{
			ID: mediaID, Kind: media.KindVideo, Status: media.StatusReady, Generation: 4,
		},
		Variants: []persistence.VariantRecord{
			{Variant: media.VariantPlayback, ObjectVersionID: "playback-v4", ContentType: "video/mp4"},
			{Variant: media.VariantSmall, ObjectVersionID: "invalid-image-v4", ContentType: "image/webp"},
		},
	}
	variants := safeVariants(record, persistence.OwnerTypeInventoryFinding, ownerID.String(), warehouseID)
	if len(variants) != 1 {
		t.Fatalf("video variants = %#v, want one PLAYBACK", variants)
	}
	response := variants[0].(map[string]any)
	if response["kind"] != media.VariantPlayback || response["contentType"] != "video/mp4" {
		t.Fatalf("video variant response = %#v", response)
	}
	path, _ := response["contentPath"].(string)
	if !strings.Contains(path, "/assets/"+mediaID.String()+"/variants/PLAYBACK/content?") ||
		!strings.Contains(path, "generation=4") {
		t.Fatalf("video playback path = %q", path)
	}
	if parsed, ok := publicDerivedVariant("PLAYBACK"); !ok || parsed != media.VariantPlayback {
		t.Fatalf("publicDerivedVariant(PLAYBACK) = %q, %v", parsed, ok)
	}
}

func TestVideoPlaybackContentStreamsThroughOwnerScopedVariantPath(t *testing.T) {
	warehouseID, ownerID, mediaID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := []byte("compressed-mp4")
	asset := persistence.AssetRecord{
		ID: mediaID, WarehouseID: warehouseID, FileName: "evidence.webm",
		Kind: media.KindVideo, Status: media.StatusReady, Generation: 2,
	}
	playback := &persistence.VariantRecord{
		Variant: media.VariantPlayback, ObjectKey: "private/playback.mp4", ObjectVersionID: "playback-v2",
		ContentType: "video/mp4", SizeBytes: int64(len(body)),
	}
	repository := &repositoryStub{currentAsset: asset, currentVariant: playback}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.read": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.View}},
	}
	store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
		VersionID: playback.ObjectVersionID, SizeBytes: int64(len(body)), ContentType: playback.ContentType,
	}}
	server := newTestServer(t, repository, validatorStub{principal: principal}, store)
	path := ownerScopedPath("/api/media/v1/assets/"+mediaID.String()+"/variants/PLAYBACK/content", ownerID, warehouseID) + "&generation=2"
	request := httptest.NewRequest(http.MethodGet, path, nil)
	request.Header.Set("Authorization", "Bearer test")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), body) ||
		response.Header().Get("Content-Type") != "video/mp4" ||
		repository.currentReadVariant != media.VariantPlayback {
		t.Fatalf("playback response=%d headers=%#v scope=%#v", response.Code, response.Header(), repository)
	}
}

func TestRotationEndpointIsNotExposed(t *testing.T) {
	server := newTestServer(t, &repositoryStub{}, validatorStub{}, &storeStub{})
	request := httptest.NewRequest(http.MethodPost,
		"/api/media/v1/assets/"+uuid.NewString()+"/rotation", nil)
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusNotFound {
		t.Fatalf("removed rotation endpoint response = %d %s", response.Code, response.Body.String())
	}
}

func TestOwnerScopedDeletionUsesExpectedVersionAndNeverTouchesStorage(t *testing.T) {
	warehouseID, ownerID, mediaID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{deleteAsset: persistence.AssetRecord{
		ID: mediaID, FolderID: mediaID, OwnerType: persistence.OwnerTypeMaintenanceEstimate,
		OwnerID: ownerID.String(), WarehouseID: warehouseID, FileName: "estimate.jpg",
		ContentType: "image/jpeg", Kind: media.KindImage, Status: media.StatusDeleted,
		Version: 6, CreatedAt: time.Now(),
	}}
	principal := auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}
	store := &storeStub{}
	server := newTestServer(t, repository, validatorStub{principal: principal}, store)
	invalidations := &invalidationRecorder{}
	server.invalidations = invalidations
	path := fmt.Sprintf("/api/media/v1/assets/%s/deletion?ownerType=MAINTENANCE_ESTIMATE&ownerId=%s&warehouseId=%s&context=ESTIMATE",
		mediaID, ownerID, warehouseID)
	request := httptest.NewRequest(http.MethodPost, path, strings.NewReader(`{"expectedVersion":5}`))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"status":"DELETED"`) {
		t.Fatalf("deletion response = %d %s", response.Code, response.Body.String())
	}
	if repository.deleteCalls != 1 || repository.deleteCommand.MediaID != mediaID ||
		repository.deleteCommand.OwnerType != persistence.OwnerTypeMaintenanceEstimate ||
		repository.deleteCommand.OwnerID != ownerID.String() ||
		repository.deleteCommand.ExpectedVersion != 5 || repository.scopedCalls != 0 {
		t.Fatalf("delete command = %#v; delete=%d scoped=%d", repository.deleteCommand,
			repository.deleteCalls, repository.scopedCalls)
	}
	if store.putCalls != 0 || store.statCalls != 0 || store.getCalls != 0 {
		t.Fatalf("deletion touched storage: put=%d stat=%d get=%d", store.putCalls, store.statCalls, store.getCalls)
	}
	events := invalidations.snapshot()
	if len(events) != 1 || events[0].WarehouseID != warehouseID || events[0].MediaID != mediaID ||
		events[0].OwnerType != "" || events[0].OwnerID != "" ||
		events[0].Scope != "MEDIA_CHANGED" || events[0].Revision != 6 {
		t.Fatalf("deletion invalidations = %#v", events)
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

func TestServiceOwnerProofEnforcesExactClientAndStructuredLogisticsIdentity(t *testing.T) {
	documentID, lineID, destinationWarehouseID, proofEventID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{ownerProofRecord: persistence.ServiceOwnerProofRecord{
		SourceService: persistence.LogisticsOwnerProofService,
		OwnerType:     persistence.OwnerTypeLogisticsTransfer, DocumentID: documentID, LineID: lineID,
		WarehouseID: destinationWarehouseID, OwnerRevision: 4, AggregateVersion: 7,
		ProofEventID: proofEventID, Active: true,
	}}
	server := newTestServer(t, repository, logisticsValidatorStub(), &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"LOGISTICS_TRANSFER","documentId":"%s","lineId":"%s","warehouseId":"%s","ownerRevision":4,"aggregateVersion":7,"proofEventId":"%s","active":true}`,
		documentID, lineID, destinationWarehouseID, proofEventID)
	request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/owner-proofs", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("owner proof response = %d %s", response.Code, response.Body.String())
	}
	if repository.ownerProofCalls != 1 || repository.ownerProofCommand.OwnerID != uuid.Nil ||
		repository.ownerProofCommand.DocumentID != documentID || repository.ownerProofCommand.LineID != lineID ||
		repository.ownerProofCommand.WarehouseID != destinationWarehouseID ||
		repository.ownerProofCommand.SourceService != persistence.LogisticsOwnerProofService {
		t.Fatalf("owner proof command = %#v, calls=%d", repository.ownerProofCommand, repository.ownerProofCalls)
	}
	for _, forbidden := range []string{"ownerId", "objectKey", "contentPath", "url", "minio"} {
		if strings.Contains(strings.ToLower(response.Body.String()), strings.ToLower(`"`+forbidden+`"`)) {
			t.Fatalf("owner proof response leaked %q: %s", forbidden, response.Body.String())
		}
	}

	wrongClient := validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: "maintenance-service", ClientID: "maintenance-service",
		Scopes: map[string]struct{}{persistence.MaintenanceOwnerProofScope: {}},
	}}
	wrongServer := newTestServer(t, &repositoryStub{}, wrongClient, &storeStub{})
	wrongRequest := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/owner-proofs", strings.NewReader(body))
	wrongRequest.Header.Set("Authorization", "Bearer test")
	wrongResponse := httptest.NewRecorder()
	wrongServer.Handler().ServeHTTP(wrongResponse, wrongRequest)
	if wrongResponse.Code != http.StatusForbidden || !strings.Contains(wrongResponse.Body.String(), `"code":"MEDIA_FORBIDDEN"`) {
		t.Fatalf("wrong owner proof client response = %d %s", wrongResponse.Code, wrongResponse.Body.String())
	}
}

func TestShipmentOwnerProofRequiresAndReturnsAuthorizedSubject(t *testing.T) {
	documentID, lineID, warehouseID, proofEventID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{ownerProofRecord: persistence.ServiceOwnerProofRecord{
		SourceService: persistence.LogisticsOwnerProofService,
		OwnerType:     persistence.OwnerTypeLogisticsShipment, DocumentID: documentID, LineID: lineID,
		WarehouseID: warehouseID, AuthorizedSubjectID: &subjectID,
		OwnerRevision: 2, AggregateVersion: 3, ProofEventID: proofEventID, Active: true,
	}}
	server := newTestServer(t, repository, logisticsValidatorStub(), &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"LOGISTICS_SHIPMENT","documentId":"%s","lineId":"%s","warehouseId":"%s","authorizedSubjectId":"%s","ownerRevision":2,"aggregateVersion":3,"proofEventId":"%s","active":true}`,
		documentID, lineID, warehouseID, subjectID, proofEventID)
	request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/owner-proofs", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusCreated || repository.ownerProofCommand.AuthorizedSubjectID == nil ||
		*repository.ownerProofCommand.AuthorizedSubjectID != subjectID ||
		!strings.Contains(response.Body.String(), `"authorizedSubjectId":"`+subjectID.String()+`"`) {
		t.Fatalf("shipment owner proof response = %d %s; command=%#v", response.Code,
			response.Body.String(), repository.ownerProofCommand)
	}

	missing := strings.Replace(body, `,"authorizedSubjectId":"`+subjectID.String()+`"`, "", 1)
	missingRequest := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/owner-proofs", strings.NewReader(missing))
	missingRequest.Header.Set("Authorization", "Bearer test")
	missingResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(missingResponse, missingRequest)
	if missingResponse.Code != http.StatusBadRequest || repository.ownerProofCalls != 1 {
		t.Fatalf("missing shipment subject response = %d %s; calls=%d", missingResponse.Code,
			missingResponse.Body.String(), repository.ownerProofCalls)
	}
}

func TestCustomerProfileOwnerProofRequiresNonStructuredIdentityAndSubject(t *testing.T) {
	profileID, warehouseID, proofEventID, subjectID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{ownerProofRecord: persistence.ServiceOwnerProofRecord{
		SourceService: persistence.LogisticsOwnerProofService,
		OwnerType:     persistence.OwnerTypeLogisticsCustomerProfile, OwnerID: profileID,
		WarehouseID: warehouseID, AuthorizedSubjectID: &subjectID,
		OwnerRevision: 0, AggregateVersion: 1, ProofEventID: proofEventID, Active: true,
	}}
	server := newTestServer(t, repository, logisticsValidatorStub(), &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"LOGISTICS_CUSTOMER_PROFILE","ownerId":"%s","warehouseId":"%s","authorizedSubjectId":"%s","ownerRevision":0,"aggregateVersion":1,"proofEventId":"%s","active":true}`,
		profileID, warehouseID, subjectID, proofEventID)
	request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/owner-proofs", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer logistics")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusCreated || repository.ownerProofCalls != 1 ||
		repository.ownerProofCommand.OwnerID != profileID || repository.ownerProofCommand.DocumentID != uuid.Nil ||
		repository.ownerProofCommand.AuthorizedSubjectID == nil ||
		*repository.ownerProofCommand.AuthorizedSubjectID != subjectID ||
		!strings.Contains(response.Body.String(), `"ownerId":"`+profileID.String()+`"`) ||
		!strings.Contains(response.Body.String(), `"authorizedSubjectId":"`+subjectID.String()+`"`) {
		t.Fatalf("profile owner proof = %d %s; command=%#v", response.Code,
			response.Body.String(), repository.ownerProofCommand)
	}

	structured := strings.Replace(body, `"ownerId":"`+profileID.String()+`"`,
		`"documentId":"`+profileID.String()+`","lineId":"`+uuid.NewString()+`"`, 1)
	structuredRequest := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/owner-proofs", strings.NewReader(structured))
	structuredRequest.Header.Set("Authorization", "Bearer logistics")
	structuredResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(structuredResponse, structuredRequest)
	if structuredResponse.Code != http.StatusBadRequest || repository.ownerProofCalls != 1 {
		t.Fatalf("structured profile proof = %d %s; calls=%d", structuredResponse.Code,
			structuredResponse.Body.String(), repository.ownerProofCalls)
	}
}

func TestMaintenanceOwnerProofUsesUUIDOwnerAndNeverAcceptsLogisticsShape(t *testing.T) {
	ownerID, warehouseID, proofEventID := uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{ownerProofRecord: persistence.ServiceOwnerProofRecord{
		SourceService: persistence.MaintenanceOwnerProofService,
		OwnerType:     persistence.OwnerTypeMaintenanceRepair, OwnerID: ownerID,
		WarehouseID: warehouseID, OwnerRevision: 0, AggregateVersion: 0,
		ProofEventID: proofEventID, Active: true,
	}}
	validator := validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: persistence.MaintenanceOwnerProofService, ClientID: persistence.MaintenanceOwnerProofService,
		Scopes: map[string]struct{}{persistence.MaintenanceOwnerProofScope: {}},
	}}
	server := newTestServer(t, repository, validator, &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"MAINTENANCE_REPAIR","ownerId":"%s","warehouseId":"%s","ownerRevision":0,"aggregateVersion":0,"proofEventId":"%s","active":true}`,
		ownerID, warehouseID, proofEventID)
	request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/owner-proofs", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusCreated || repository.ownerProofCalls != 1 ||
		repository.ownerProofCommand.OwnerID != ownerID || repository.ownerProofCommand.DocumentID != uuid.Nil {
		t.Fatalf("maintenance proof response = %d %s; command=%#v", response.Code,
			response.Body.String(), repository.ownerProofCommand)
	}

	invalidBody := strings.Replace(body, `"ownerId":"`+ownerID.String()+`"`,
		`"documentId":"`+uuid.NewString()+`","lineId":"`+uuid.NewString()+`"`, 1)
	invalidRequest := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/owner-proofs", strings.NewReader(invalidBody))
	invalidRequest.Header.Set("Authorization", "Bearer test")
	invalidResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(invalidResponse, invalidRequest)
	if invalidResponse.Code != http.StatusBadRequest || repository.ownerProofCalls != 1 {
		t.Fatalf("maintenance structured proof response = %d %s; calls=%d", invalidResponse.Code,
			invalidResponse.Body.String(), repository.ownerProofCalls)
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

func TestLogisticsCabinCoverCommandRequiresExactServiceAndIdempotencyKey(t *testing.T) {
	cabinID, warehouseID, entryID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	changedAt := time.Now().UTC().Truncate(time.Millisecond)
	repository := &repositoryStub{cabinCoverChange: persistence.CabinCoverChangeRecord{
		CabinID: cabinID, WarehouseID: warehouseID, MediaID: mediaID,
		Generation: 2, TaskBoardEntryID: entryID, Version: 7, ChangedAt: changedAt,
	}}
	server := newTestServer(t, repository, logisticsValidatorStub(), &storeStub{})
	invalidations := &invalidationRecorder{}
	server.invalidations = invalidations
	body := fmt.Sprintf(`{"taskBoardEntryId":"%s","evidenceMediaId":"%s"}`, entryID, mediaID)
	idempotencyKey := uuid.New()
	request := httptest.NewRequest(http.MethodPost,
		"/api/internal/media/v1/logistics/cabins/"+cabinID.String()+"/cover-from-task-evidence",
		strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", idempotencyKey.String())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("cover command response = %d %s", response.Code, response.Body.String())
	}
	command := repository.cabinCoverChangeCommand
	if command.CabinID != cabinID || command.TaskBoardEntryID != entryID ||
		command.EvidenceMediaID != mediaID || command.IdempotencyKey != idempotencyKey ||
		command.CorrelationID == uuid.Nil || !checksumPattern.MatchString(command.RequestSHA256) {
		t.Fatalf("cover command = %#v", command)
	}
	var result struct {
		CabinID      uuid.UUID `json:"cabinId"`
		CoverMediaID uuid.UUID `json:"coverMediaId"`
		Version      int64     `json:"version"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil ||
		result.CabinID != cabinID || result.CoverMediaID != mediaID || result.Version != 7 {
		t.Fatalf("cover result = %#v error=%v", result, err)
	}
	events := invalidations.snapshot()
	if len(events) != 1 || events[0].WarehouseID != warehouseID || events[0].MediaID != mediaID ||
		events[0].OwnerType != persistence.OwnerTypeCabin || events[0].OwnerID != cabinID.String() ||
		events[0].Scope != "CABIN_COVER_CHANGED" || events[0].Generation != 2 ||
		events[0].Revision != 7 || !events[0].OccurredAt.Equal(changedAt) {
		t.Fatalf("cover invalidations = %#v", events)
	}

	missingKey := httptest.NewRequest(http.MethodPost,
		"/api/internal/media/v1/logistics/cabins/"+cabinID.String()+"/cover-from-task-evidence",
		strings.NewReader(body))
	missingKey.Header.Set("Authorization", "Bearer test")
	missingResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(missingResponse, missingKey)
	if missingResponse.Code != http.StatusBadRequest {
		t.Fatalf("missing idempotency response = %d %s", missingResponse.Code, missingResponse.Body.String())
	}

	wrongServer := newTestServer(t, &repositoryStub{}, validatorStub{
		servicePrincipal: auth.ServicePrincipal{
			Subject: "maintenance-service", ClientID: "maintenance-service",
			Scopes: map[string]struct{}{"media.maintenance": {}},
		},
	}, &storeStub{})
	wrongRequest := httptest.NewRequest(http.MethodPost,
		"/api/internal/media/v1/logistics/cabins/"+cabinID.String()+"/cover-from-task-evidence",
		strings.NewReader(body))
	wrongRequest.Header.Set("Authorization", "Bearer test")
	wrongRequest.Header.Set("Idempotency-Key", uuid.NewString())
	wrongResponse := httptest.NewRecorder()
	wrongServer.Handler().ServeHTTP(wrongResponse, wrongRequest)
	if wrongResponse.Code != http.StatusForbidden {
		t.Fatalf("wrong service response = %d %s", wrongResponse.Code, wrongResponse.Body.String())
	}
}

func TestInventoryCabinPhotosRequiresExactServiceAndReturnsFrozenShape(t *testing.T) {
	inventoryID, findingID, warehouseID, cabinID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	firstMediaID, coverMediaID, folderID := uuid.New(), uuid.New(), uuid.New()
	completedAt := time.Date(2026, time.August, 19, 12, 5, 24, 123000000, time.UTC)
	changedAt := completedAt.Add(time.Minute)
	repository := &repositoryStub{
		inventoryCabinPhotoResult: persistence.InventoryCabinPhotoResult{
			InventoryID: inventoryID, FindingID: findingID, CabinID: cabinID,
			FolderID: folderID, CoverMediaID: coverMediaID, PhotoCount: 2,
			LibraryVersion: 9, WarehouseID: warehouseID, CoverGeneration: 4,
			ChangedAt: changedAt,
		},
		inventoryCabinPhotoChanged: true,
	}
	server := newTestServer(t, repository, inventoryValidatorStub(), &storeStub{})
	invalidations := &invalidationRecorder{}
	server.invalidations = invalidations
	idempotencyKey := uuid.New()
	body := fmt.Sprintf(`{"warehouseId":"%s","cabinId":"%s","completedAt":"%s","sourceRevision":80,"finalPlanVersion":11,"finalPlanSha256":"%s","coverMediaId":"%s","mediaReferences":[{"mediaId":"%s","generation":2},{"mediaId":"%s","generation":4}]}`,
		warehouseID, cabinID, completedAt.Format(time.RFC3339Nano), strings.Repeat("a", 64),
		coverMediaID, firstMediaID, coverMediaID)
	request := httptest.NewRequest(http.MethodPut,
		"/api/internal/media/v1/inventory/outcomes/"+inventoryID.String()+
			"/findings/"+findingID.String()+"/cabin-photos", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer inventory")
	request.Header.Set("Idempotency-Key", idempotencyKey.String())
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("inventory cabin photos response = %d %s", response.Code, response.Body.String())
	}
	command := repository.inventoryCabinPhotoCommand
	if repository.inventoryCabinPhotoCalls != 1 || command.InventoryID != inventoryID ||
		command.FindingID != findingID || command.WarehouseID != warehouseID ||
		command.CabinID != cabinID || !command.CompletedAt.Equal(completedAt) ||
		command.SourceRevision != 80 || command.FinalPlanVersion != 11 ||
		command.FinalPlanSHA256 != strings.Repeat("a", 64) ||
		command.CoverMediaID != coverMediaID || len(command.MediaReferences) != 2 ||
		command.MediaReferences[0].MediaID != firstMediaID ||
		command.MediaReferences[1].Generation != 4 || command.IdempotencyKey != idempotencyKey ||
		command.CorrelationID == uuid.Nil || !checksumPattern.MatchString(command.RequestSHA256) {
		t.Fatalf("inventory cabin photo command = %#v, calls=%d", command,
			repository.inventoryCabinPhotoCalls)
	}
	var payload map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil || len(payload) != 8 ||
		payload["inventoryId"] != inventoryID.String() || payload["findingId"] != findingID.String() ||
		payload["cabinId"] != cabinID.String() || payload["folderId"] != folderID.String() ||
		payload["coverMediaId"] != coverMediaID.String() || payload["photoCount"] != float64(2) ||
		payload["libraryVersion"] != float64(9) || payload["replay"] != false {
		t.Fatalf("inventory cabin photo payload = %#v error=%v", payload, err)
	}
	events := invalidations.snapshot()
	if len(events) != 1 || events[0].WarehouseID != warehouseID ||
		events[0].OwnerType != persistence.OwnerTypeCabin ||
		events[0].OwnerID != cabinID.String() || events[0].MediaID != coverMediaID ||
		events[0].Generation != 4 || events[0].Revision != 9 ||
		!events[0].OccurredAt.Equal(changedAt) {
		t.Fatalf("inventory cabin photo invalidations = %#v", events)
	}

	repository.inventoryCabinPhotoReplay = true
	repository.inventoryCabinPhotoChanged = false
	replayResponse := httptest.NewRecorder()
	replayRequest := httptest.NewRequest(http.MethodPut, request.URL.Path, strings.NewReader(body))
	replayRequest.Header.Set("Authorization", "Bearer inventory")
	replayRequest.Header.Set("Idempotency-Key", idempotencyKey.String())
	server.Handler().ServeHTTP(replayResponse, replayRequest)
	if replayResponse.Code != http.StatusOK ||
		!strings.Contains(replayResponse.Body.String(), `"replay":true`) ||
		len(invalidations.snapshot()) != 1 {
		t.Fatalf("inventory replay response = %d %s events=%#v", replayResponse.Code,
			replayResponse.Body.String(), invalidations.snapshot())
	}

	wrongServer := newTestServer(t, &repositoryStub{}, logisticsValidatorStub(), &storeStub{})
	wrongResponse := httptest.NewRecorder()
	wrongRequest := httptest.NewRequest(http.MethodPut, request.URL.Path, strings.NewReader(body))
	wrongRequest.Header.Set("Authorization", "Bearer logistics")
	wrongRequest.Header.Set("Idempotency-Key", uuid.NewString())
	wrongServer.Handler().ServeHTTP(wrongResponse, wrongRequest)
	if wrongResponse.Code != http.StatusForbidden ||
		!strings.Contains(wrongResponse.Body.String(), `"code":"MEDIA_FORBIDDEN"`) {
		t.Fatalf("wrong inventory service response = %d %s", wrongResponse.Code,
			wrongResponse.Body.String())
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

func TestCustomerProfileReferenceValidationCarriesExactOwnerSubjectAndContext(t *testing.T) {
	profileID, subjectID, warehouseID, mediaID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{}
	server := newTestServer(t, repository, logisticsValidatorStub(), &storeStub{})
	body := fmt.Sprintf(`{"ownerType":"LOGISTICS_CUSTOMER_PROFILE","ownerId":"%s","authorizedSubjectId":"%s","warehouseId":"%s","context":"PROFILE_AVATAR","references":[{"mediaId":"%s","generation":3}]}`,
		profileID, subjectID, warehouseID, mediaID)
	request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/logistics/references/validate", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer logistics")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	_, command := repository.validationSnapshot()
	if response.Code != http.StatusOK || command.OwnerType != persistence.OwnerTypeLogisticsCustomerProfile ||
		command.OwnerID != profileID.String() || command.WarehouseID != warehouseID ||
		command.AuthorizedSubjectID == nil || *command.AuthorizedSubjectID != subjectID ||
		len(command.References) != 1 || command.References[0].MediaID != mediaID ||
		!strings.Contains(response.Body.String(), `"context":"PROFILE_AVATAR"`) {
		t.Fatalf("profile validation = %d %s; command=%#v", response.Code,
			response.Body.String(), command)
	}

	for name, invalidBody := range map[string]string{
		"wrong context": strings.Replace(body, `"PROFILE_AVATAR"`, `"SHIPMENT"`, 1),
		"structured identity": strings.Replace(body, `"ownerId":"`+profileID.String()+`"`,
			`"documentId":"`+profileID.String()+`","lineId":"`+uuid.NewString()+`"`, 1),
		"multiple references": strings.Replace(body, `]}`,
			`,{"mediaId":"`+uuid.NewString()+`","generation":1}]}`, 1),
	} {
		t.Run(name, func(t *testing.T) {
			invalidRequest := httptest.NewRequest(http.MethodPost,
				"/api/internal/media/v1/logistics/references/validate", strings.NewReader(invalidBody))
			invalidRequest.Header.Set("Authorization", "Bearer logistics")
			invalidResponse := httptest.NewRecorder()
			server.Handler().ServeHTTP(invalidResponse, invalidRequest)
			if invalidResponse.Code != http.StatusBadRequest {
				t.Fatalf("invalid profile validation = %d %s", invalidResponse.Code,
					invalidResponse.Body.String())
			}
		})
	}
	calls, _ := repository.validationSnapshot()
	if calls != 1 {
		t.Fatalf("profile validation calls = %d, want 1", calls)
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

func TestLogisticsCabinPresentationSnapshotReturnsOpaqueCurrentReferences(t *testing.T) {
	warehouseID, firstCabinID, secondCabinID := uuid.New(), uuid.New(), uuid.New()
	firstMediaID, secondMediaID := uuid.New(), uuid.New()
	repository := &repositoryStub{cabinPresentationRecords: []persistence.CabinPresentationSnapshotRecord{
		{
			CabinID:      firstCabinID,
			CoverMediaID: &firstMediaID,
			PhotoCount:   2,
			Photos: []persistence.CabinPresentationPhotoRecord{
				{MediaID: firstMediaID, Generation: 3, SortOrder: 0, HasSmall: true, HasLarge: true},
				{MediaID: secondMediaID, Generation: 2, SortOrder: 4, HasSmall: true},
			},
		},
		{CabinID: secondCabinID, Photos: []persistence.CabinPresentationPhotoRecord{}},
	}}
	server := newTestServer(t, repository, logisticsValidatorStub(), &storeStub{})
	body := fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s","%s"]}`,
		warehouseID, firstCabinID, secondCabinID)
	request := httptest.NewRequest(http.MethodPost,
		"/api/internal/media/v1/logistics/cabin-presentations/snapshots", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer service")
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("snapshot response = %d %s", response.Code, response.Body.String())
	}
	var payload struct {
		Items []struct {
			CabinID      uuid.UUID  `json:"cabinId"`
			CoverMediaID *uuid.UUID `json:"coverMediaId"`
			PhotoCount   int64      `json:"photoCount"`
			Photos       []struct {
				MediaID           uuid.UUID `json:"mediaId"`
				Generation        int       `json:"generation"`
				SortOrder         int64     `json:"sortOrder"`
				AvailableVariants []string  `json:"availableVariants"`
			} `json:"photos"`
		} `json:"items"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode snapshot response: %v", err)
	}
	if len(payload.Items) != 2 || payload.Items[0].CabinID != firstCabinID ||
		payload.Items[0].CoverMediaID == nil || *payload.Items[0].CoverMediaID != firstMediaID ||
		payload.Items[0].PhotoCount != 2 ||
		len(payload.Items[0].Photos) != 2 || payload.Items[0].Photos[0].MediaID != firstMediaID ||
		payload.Items[0].Photos[0].Generation != 3 || payload.Items[0].Photos[0].SortOrder != 0 ||
		!sameStrings(payload.Items[0].Photos[0].AvailableVariants, []string{"SMALL", "LARGE"}) ||
		payload.Items[0].Photos[1].MediaID != secondMediaID ||
		!sameStrings(payload.Items[0].Photos[1].AvailableVariants, []string{"SMALL"}) ||
		payload.Items[1].CabinID != secondCabinID || payload.Items[1].CoverMediaID != nil ||
		payload.Items[1].PhotoCount != 0 ||
		len(payload.Items[1].Photos) != 0 {
		t.Fatalf("snapshot payload = %#v", payload.Items)
	}
	if repository.cabinPresentationCalls != 1 || repository.cabinPresentationWarehouseID != warehouseID ||
		len(repository.cabinPresentationIDs) != 2 || repository.cabinPresentationIDs[0] != firstCabinID ||
		repository.cabinPresentationIDs[1] != secondCabinID {
		t.Fatalf("snapshot repository call = %d %s %#v", repository.cabinPresentationCalls,
			repository.cabinPresentationWarehouseID, repository.cabinPresentationIDs)
	}
	for _, forbidden := range []string{"objectKey", "sourceObjectKey", "bucket", "contentPath", "url", "signed", "fileName", "contentType", "status", "minio"} {
		if strings.Contains(strings.ToLower(response.Body.String()), strings.ToLower(`"`+forbidden+`"`)) {
			t.Fatalf("snapshot response leaked %q: %s", forbidden, response.Body.String())
		}
	}
}

func TestAssetCabinCreationSnapshotReturnsExactCurrentProof(t *testing.T) {
	warehouseID, cabinID := uuid.New(), uuid.New()
	folderID, coverMediaID, secondMediaID := uuid.New(), uuid.New(), uuid.New()
	repository := &repositoryStub{cabinPresentationRecords: []persistence.CabinPresentationSnapshotRecord{{
		CabinID: cabinID, ActiveFolderID: &folderID, CoverMediaID: &coverMediaID, PhotoCount: 2,
		Photos: []persistence.CabinPresentationPhotoRecord{
			{MediaID: coverMediaID, Generation: 3, SortOrder: 0, PhotoIndex: 0,
				SourceChecksum: strings.Repeat("a", 64), SourceContentType: "image/webp",
				SourceContentLength: 101, HasSmall: true},
			{MediaID: secondMediaID, Generation: 2, SortOrder: 1, PhotoIndex: 1,
				SourceChecksum: strings.Repeat("b", 64), SourceContentType: "image/jpeg",
				SourceContentLength: 102, HasLarge: true},
		},
	}}}
	server := newTestServer(t, repository, assetValidatorStub(), &storeStub{})
	body := fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s"]}`, warehouseID, cabinID)
	request := httptest.NewRequest(http.MethodPost,
		"/api/internal/media/v1/assets/cabin-creation-snapshots", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer service")
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("snapshot response = %d %s", response.Code, response.Body.String())
	}
	var payload struct {
		Items []struct {
			CabinID        uuid.UUID  `json:"cabinId"`
			WarehouseID    uuid.UUID  `json:"warehouseId"`
			ActiveFolderID *uuid.UUID `json:"activeFolderId"`
			CoverMediaID   *uuid.UUID `json:"coverMediaId"`
			PhotoCount     int64      `json:"photoCount"`
			ReadyPhotos    []struct {
				MediaID        uuid.UUID `json:"mediaId"`
				Generation     int       `json:"generation"`
				PhotoIndex     int64     `json:"photoIndex"`
				ChecksumSHA256 string    `json:"checksumSha256"`
				ContentType    string    `json:"contentType"`
				ContentLength  int64     `json:"contentLength"`
			} `json:"readyPhotos"`
		} `json:"items"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode snapshot response: %v", err)
	}
	if len(payload.Items) != 1 || payload.Items[0].CabinID != cabinID ||
		payload.Items[0].WarehouseID != warehouseID || payload.Items[0].ActiveFolderID == nil ||
		*payload.Items[0].ActiveFolderID != folderID || payload.Items[0].CoverMediaID == nil ||
		*payload.Items[0].CoverMediaID != coverMediaID || payload.Items[0].PhotoCount != 2 ||
		len(payload.Items[0].ReadyPhotos) != 2 || payload.Items[0].ReadyPhotos[0].MediaID != coverMediaID ||
		payload.Items[0].ReadyPhotos[0].Generation != 3 || payload.Items[0].ReadyPhotos[0].PhotoIndex != 0 ||
		payload.Items[0].ReadyPhotos[0].ChecksumSHA256 != strings.Repeat("a", 64) ||
		payload.Items[0].ReadyPhotos[0].ContentType != "image/webp" ||
		payload.Items[0].ReadyPhotos[0].ContentLength != 101 ||
		payload.Items[0].ReadyPhotos[1].MediaID != secondMediaID ||
		payload.Items[0].ReadyPhotos[1].Generation != 2 || payload.Items[0].ReadyPhotos[1].PhotoIndex != 1 ||
		payload.Items[0].ReadyPhotos[1].ChecksumSHA256 != strings.Repeat("b", 64) ||
		payload.Items[0].ReadyPhotos[1].ContentType != "image/jpeg" ||
		payload.Items[0].ReadyPhotos[1].ContentLength != 102 {
		t.Fatalf("snapshot payload = %#v", payload.Items)
	}
	if repository.cabinPresentationCalls != 1 || repository.cabinPresentationWarehouseID != warehouseID ||
		len(repository.cabinPresentationIDs) != 1 || repository.cabinPresentationIDs[0] != cabinID {
		t.Fatalf("snapshot repository call = %d %s %#v", repository.cabinPresentationCalls,
			repository.cabinPresentationWarehouseID, repository.cabinPresentationIDs)
	}
	for _, forbidden := range []string{"objectKey", "bucket", "url", "fileName", "status", "availableVariants"} {
		if strings.Contains(strings.ToLower(response.Body.String()), strings.ToLower(`"`+forbidden+`"`)) {
			t.Fatalf("snapshot response leaked %q: %s", forbidden, response.Body.String())
		}
	}
}

func TestAssetCabinCreationSnapshotRequiresExactServiceScopeAndValidIdentity(t *testing.T) {
	warehouseID, cabinID := uuid.New(), uuid.New()
	validBody := fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s"]}`, warehouseID, cabinID)
	for _, testCase := range []struct {
		name       string
		validator  validatorStub
		body       string
		wantStatus int
		wantCode   string
	}{
		{name: "unauthorized", validator: validatorStub{serviceErr: auth.ErrUnauthorized}, body: validBody,
			wantStatus: http.StatusUnauthorized, wantCode: "MEDIA_UNAUTHORIZED"},
		{name: "wrong client", validator: validatorStub{servicePrincipal: auth.ServicePrincipal{
			Subject: "logistics-service", ClientID: "logistics-service", Scopes: map[string]struct{}{"media.asset": {}},
		}}, body: validBody, wantStatus: http.StatusForbidden, wantCode: "MEDIA_FORBIDDEN"},
		{name: "extra scope", validator: validatorStub{servicePrincipal: auth.ServicePrincipal{
			Subject: "asset-service", ClientID: "asset-service",
			Scopes: map[string]struct{}{"media.asset": {}, "media.logistics": {}},
		}}, body: validBody, wantStatus: http.StatusForbidden, wantCode: "MEDIA_FORBIDDEN"},
		{name: "duplicate cabin", validator: assetValidatorStub(),
			body:       fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s","%s"]}`, warehouseID, cabinID, cabinID),
			wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_CABIN_CREATION_SNAPSHOT"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			repository := &repositoryStub{}
			server := newTestServer(t, repository, testCase.validator, &storeStub{})
			request := httptest.NewRequest(http.MethodPost,
				"/api/internal/media/v1/assets/cabin-creation-snapshots", strings.NewReader(testCase.body))
			request.Header.Set("Authorization", "Bearer service")
			response := httptest.NewRecorder()

			server.Handler().ServeHTTP(response, request)

			if response.Code != testCase.wantStatus ||
				!strings.Contains(response.Body.String(), `"code":"`+testCase.wantCode+`"`) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if repository.cabinPresentationCalls != 0 {
				t.Fatalf("repository calls = %d, want 0", repository.cabinPresentationCalls)
			}
		})
	}
}

func TestLogisticsCabinPresentationVariantStreamsPinnedRetainedObjectVersion(t *testing.T) {
	warehouseID, cabinID, mediaID := uuid.New(), uuid.New(), uuid.New()
	body := []byte("webp-bytes")
	variant := &persistence.VariantRecord{
		Variant: media.VariantSmall, ObjectKey: "media/private/cabin/small.webp", ObjectVersionID: "small-v3",
		ContentType: "image/webp", SizeBytes: int64(len(body)),
	}
	repository := &repositoryStub{presentationVariant: variant}
	store := &storeStub{objectBody: body, statMetadata: media.ObjectMetadata{
		VersionID: "small-v3", SizeBytes: int64(len(body)), ContentType: "image/webp",
	}}
	server := newTestServer(t, repository, logisticsValidatorStub(), store)
	request := httptest.NewRequest(http.MethodGet,
		"/api/internal/media/v1/logistics/cabin-presentations/assets/"+mediaID.String()+
			"/variants/SMALL/content?warehouseId="+warehouseID.String()+"&cabinId="+cabinID.String()+"&generation=3", nil)
	request.Header.Set("Authorization", "Bearer service")
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "private, no-store" ||
		response.Header().Get("Content-Type") != "image/webp" || response.Header().Get("X-Content-Type-Options") != "nosniff" ||
		response.Body.String() != string(body) {
		t.Fatalf("content response = %d headers=%#v body=%q", response.Code, response.Header(), response.Body.String())
	}
	if strings.Contains(response.Header().Get("Content-Disposition"), "private") ||
		strings.Contains(response.Header().Get("Content-Disposition"), mediaID.String()) {
		t.Fatalf("content disposition leaks media metadata: %q", response.Header().Get("Content-Disposition"))
	}
	if repository.presentationReadCalls != 1 || repository.presentationReadCabinID != cabinID ||
		repository.presentationReadMediaID != mediaID || repository.presentationReadWarehouseID != warehouseID ||
		repository.presentationReadGeneration != 3 || repository.presentationReadVariant != media.VariantSmall {
		t.Fatalf("presentation variant read = calls:%d cabin:%s media:%s warehouse:%s generation:%d variant:%s",
			repository.presentationReadCalls, repository.presentationReadCabinID,
			repository.presentationReadMediaID, repository.presentationReadWarehouseID,
			repository.presentationReadGeneration, repository.presentationReadVariant)
	}
	if store.getCalls != 1 || store.getKey != variant.ObjectKey || store.getVersion != variant.ObjectVersionID {
		t.Fatalf("store read = calls:%d key:%q version:%q", store.getCalls, store.getKey, store.getVersion)
	}
}

func TestLogisticsCabinPresentationEndpointsFailClosed(t *testing.T) {
	warehouseID, cabinID, mediaID := uuid.New(), uuid.New(), uuid.New()
	snapshotBody := fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s","%s"]}`,
		warehouseID, cabinID, cabinID)
	contentPath := "/api/internal/media/v1/logistics/cabin-presentations/assets/" + mediaID.String() +
		"/variants/SMALL/content?warehouseId=" + warehouseID.String() + "&cabinId=" + cabinID.String() + "&generation=1"
	for _, testCase := range []struct {
		name       string
		method     string
		path       string
		body       string
		validator  validatorStub
		repository *repositoryStub
		wantStatus int
		wantCode   string
		wantReads  int
	}{
		{
			name: "missing service token", method: http.MethodPost,
			path: "/api/internal/media/v1/logistics/cabin-presentations/snapshots", body: snapshotBody,
			validator: validatorStub{serviceErr: auth.ErrUnauthorized}, repository: &repositoryStub{},
			wantStatus: http.StatusUnauthorized, wantCode: "MEDIA_UNAUTHORIZED",
		},
		{
			name: "public presentation route does not exist", method: http.MethodGet,
			path:      strings.Replace(contentPath, "/api/internal/media/", "/api/media/", 1),
			validator: logisticsValidatorStub(), repository: &repositoryStub{},
			wantStatus: http.StatusNotFound, wantCode: "MEDIA_NOT_FOUND",
		},
		{
			name: "combined service scopes are rejected", method: http.MethodPost,
			path: "/api/internal/media/v1/logistics/cabin-presentations/snapshots", body: fmt.Sprintf(`{"warehouseId":"%s","cabinIds":["%s"]}`, warehouseID, cabinID),
			validator: validatorStub{servicePrincipal: auth.ServicePrincipal{
				Subject: "logistics-service", ClientID: "logistics-service",
				Scopes: map[string]struct{}{"media.logistics": {}, "asset.logistics": {}},
			}}, repository: &repositoryStub{}, wantStatus: http.StatusForbidden, wantCode: "MEDIA_FORBIDDEN",
		},
		{
			name: "duplicate cabin IDs are rejected", method: http.MethodPost,
			path: "/api/internal/media/v1/logistics/cabin-presentations/snapshots", body: snapshotBody,
			validator: logisticsValidatorStub(), repository: &repositoryStub{},
			wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_CABIN_PRESENTATION",
		},
		{
			name: "variant must be exactly uppercase small or large", method: http.MethodGet,
			path:      strings.Replace(contentPath, "/SMALL/", "/small/", 1),
			validator: logisticsValidatorStub(), repository: &repositoryStub{},
			wantStatus: http.StatusBadRequest, wantCode: "MEDIA_INVALID_CABIN_PRESENTATION",
		},
		{
			name: "owner warehouse or generation mismatch stays opaque", method: http.MethodGet,
			path: contentPath, validator: logisticsValidatorStub(),
			repository: &repositoryStub{presentationReadErr: persistence.ErrNotFound},
			wantStatus: http.StatusNotFound, wantCode: "MEDIA_NOT_FOUND", wantReads: 1,
		},
		{
			name: "unavailable variant stays opaque", method: http.MethodGet,
			path: contentPath, validator: logisticsValidatorStub(), repository: &repositoryStub{
				presentationVariant: &persistence.VariantRecord{Variant: media.VariantSmall},
			}, wantStatus: http.StatusNotFound, wantCode: "MEDIA_NOT_FOUND", wantReads: 1,
		},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			store := &storeStub{}
			server := newTestServer(t, testCase.repository, testCase.validator, store)
			request := httptest.NewRequest(testCase.method, testCase.path, strings.NewReader(testCase.body))
			request.Header.Set("Authorization", "Bearer service")
			response := httptest.NewRecorder()

			server.Handler().ServeHTTP(response, request)

			if response.Code != testCase.wantStatus || !strings.Contains(response.Body.String(), `"code":"`+testCase.wantCode+`"`) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if testCase.repository.cabinPresentationCalls != 0 || testCase.repository.presentationReadCalls != testCase.wantReads ||
				store.getCalls != 0 {
				t.Fatalf("repository/storage calls = snapshots:%d variants:%d storage:%d",
					testCase.repository.cabinPresentationCalls, testCase.repository.presentationReadCalls, store.getCalls)
			}
		})
	}
}

func logisticsValidatorStub() validatorStub {
	return validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: "logistics-service", ClientID: "logistics-service",
		Scopes: map[string]struct{}{"media.logistics": {}},
	}}
}

func assetValidatorStub() validatorStub {
	return validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: "asset-service", ClientID: "asset-service",
		Scopes: map[string]struct{}{"media.asset": {}},
	}}
}

func inventoryValidatorStub() validatorStub {
	return validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: "inventory-service", ClientID: "inventory-service",
		Scopes: map[string]struct{}{"media.inventory": {}},
	}}
}

func sameStrings(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
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
		MaxUploadBytes: 1 << 20, AllowedMIMETypes: map[string]struct{}{"image/jpeg": {}, "image/webp": {}},
		UploadExpiry: time.Minute,
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("NewServer() error = %v", err)
	}
	return server
}

type invalidationRecorder struct {
	mutex  sync.Mutex
	events []realtime.Event
}

func (recorder *invalidationRecorder) Publish(event realtime.Event) {
	recorder.mutex.Lock()
	defer recorder.mutex.Unlock()
	recorder.events = append(recorder.events, event)
}

func (*invalidationRecorder) ServeHTTP(http.ResponseWriter, *http.Request, uuid.UUID) {}

func (recorder *invalidationRecorder) snapshot() []realtime.Event {
	recorder.mutex.Lock()
	defer recorder.mutex.Unlock()
	return append([]realtime.Event(nil), recorder.events...)
}

type readyStub struct{ err error }

func (stub readyStub) Ready(context.Context) error { return stub.err }

type validatorStub struct {
	principal        auth.Principal
	err              error
	workerPrincipal  auth.WorkerPrincipal
	workerErr        error
	servicePrincipal auth.ServicePrincipal
	serviceErr       error
}

func (stub validatorStub) Validate(context.Context, string) (auth.Principal, error) {
	return stub.principal, stub.err
}

func (stub validatorStub) ValidateWorker(context.Context, string) (auth.WorkerPrincipal, error) {
	if stub.workerErr != nil {
		return auth.WorkerPrincipal{}, stub.workerErr
	}
	if stub.workerPrincipal.SubjectID != uuid.Nil {
		return stub.workerPrincipal, nil
	}
	if stub.err != nil {
		return auth.WorkerPrincipal{}, stub.err
	}
	return auth.WorkerPrincipal{}, auth.ErrForbidden
}

func (stub validatorStub) ValidateService(context.Context, string) (auth.ServicePrincipal, error) {
	return stub.servicePrincipal, stub.serviceErr
}

type repositoryStub struct {
	mutex                        sync.Mutex
	contentMutex                 sync.Mutex
	contentLockErr               error
	variantAsset                 persistence.AssetRecord
	variantPart                  persistence.UploadImageVariantPart
	variantErr                   error
	completeVariantPart          persistence.UploadImageVariantPart
	completeVariantReplay        bool
	completeVariantErr           error
	completeVariantCommand       persistence.CompleteUploadImageVariantCommand
	createAsset                  persistence.AssetRecord
	createReplay                 bool
	createErr                    error
	createCalls                  int
	createCommand                persistence.CreateUploadCommand
	sessionAsset                 persistence.AssetRecord
	sessionErr                   error
	finalizeAsset                persistence.AssetRecord
	finalizeReplay               bool
	finalizeErr                  error
	finalizeFunc                 func(persistence.FinalizeCommand) (persistence.AssetRecord, bool, error)
	finalizeCalls                int
	finalizeCommands             []persistence.FinalizeCommand
	ownerRecords                 []persistence.AssetWithVariants
	ownerReadErr                 error
	workerListRecords            []persistence.AssetWithVariants
	workerListErr                error
	workerListCalls              int
	workerListEntryID            uuid.UUID
	workerListWarehouseID        uuid.UUID
	workerListWorkerID           uuid.UUID
	driverListRecords            []persistence.AssetWithVariants
	driverListErr                error
	driverListCalls              int
	driverListShiftID            uuid.UUID
	driverListWarehouseID        uuid.UUID
	driverListWorkerID           uuid.UUID
	cabinCoverRecords            []persistence.CabinCoverRecord
	cabinCoverErr                error
	cabinCoverCalls              int
	cabinCoverWarehouseID        uuid.UUID
	cabinCoverIDs                []uuid.UUID
	cabinPresentationRecords     []persistence.CabinPresentationSnapshotRecord
	cabinPresentationErr         error
	cabinPresentationCalls       int
	cabinPresentationWarehouseID uuid.UUID
	cabinPresentationIDs         []uuid.UUID
	presentationVariant          *persistence.VariantRecord
	presentationReadErr          error
	presentationReadCalls        int
	presentationReadCabinID      uuid.UUID
	presentationReadMediaID      uuid.UUID
	presentationReadWarehouseID  uuid.UUID
	presentationReadGeneration   int
	presentationReadVariant      media.Variant
	cabinCoverChange             persistence.CabinCoverChangeRecord
	cabinCoverChangeReplay       bool
	cabinCoverChangeErr          error
	cabinCoverChangeCommand      persistence.SetCabinCoverFromTaskEvidenceCommand
	inventoryCabinPhotoResult    persistence.InventoryCabinPhotoResult
	inventoryCabinPhotoReplay    bool
	inventoryCabinPhotoChanged   bool
	inventoryCabinPhotoErr       error
	inventoryCabinPhotoCalls     int
	inventoryCabinPhotoCommand   persistence.ApplyInventoryCabinPhotosCommand
	originalAsset                persistence.AssetRecord
	originalVariant              *persistence.VariantRecord
	originalReadErr              error
	originalReadCalls            int
	originalReadMediaID          uuid.UUID
	originalReadOwnerType        string
	originalReadOwnerID          string
	originalReadWarehouseID      uuid.UUID
	originalReadGeneration       *int
	currentAsset                 persistence.AssetRecord
	currentVariant               *persistence.VariantRecord
	currentReadErr               error
	currentReadCalls             int
	currentReadMediaID           uuid.UUID
	currentReadOwnerType         string
	currentReadOwnerID           string
	currentReadWarehouseID       uuid.UUID
	currentReadGeneration        int
	currentReadVariant           media.Variant
	workerOriginalAsset          persistence.AssetRecord
	workerOriginalVariant        *persistence.VariantRecord
	workerOriginalErr            error
	workerOriginalCalls          int
	workerCurrentAsset           persistence.AssetRecord
	workerCurrentVariant         *persistence.VariantRecord
	workerCurrentErr             error
	workerCurrentCalls           int
	workerAuthorizeErr           error
	workerAuthorizeCalls         int
	workerEntryID                uuid.UUID
	workerWarehouseID            uuid.UUID
	workerID                     uuid.UUID
	workerReadEntryID            uuid.UUID
	workerReadWarehouseID        uuid.UUID
	workerReadWorkerID           uuid.UUID
	workerReadMediaID            uuid.UUID
	workerReadGeneration         *int
	workerReadVariant            media.Variant
	driverOriginalAsset          persistence.AssetRecord
	driverOriginalVariant        *persistence.VariantRecord
	driverOriginalErr            error
	driverOriginalCalls          int
	driverCurrentAsset           persistence.AssetRecord
	driverCurrentVariant         *persistence.VariantRecord
	driverCurrentErr             error
	driverCurrentCalls           int
	driverAuthorizeErr           error
	driverAuthorizeCalls         int
	driverShiftID                uuid.UUID
	driverWarehouseID            uuid.UUID
	driverWorkerID               uuid.UUID
	driverReadShiftID            uuid.UUID
	driverReadWarehouseID        uuid.UUID
	driverReadWorkerID           uuid.UUID
	driverReadMediaID            uuid.UUID
	driverReadGeneration         *int
	driverReadVariant            media.Variant
	scopedAsset                  persistence.AssetRecord
	scopedErr                    error
	scopedCalls                  int
	scopedOwnerType              string
	scopedOwnerID                string
	scopedWarehouseID            uuid.UUID
	validationCalls              int
	validationCommand            persistence.ValidateLogisticsReferencesCommand
	validationErr                error
	ownerProofRecord             persistence.ServiceOwnerProofRecord
	ownerProofReplay             bool
	ownerProofErr                error
	ownerProofCalls              int
	ownerProofCommand            persistence.ServiceOwnerProofCommand
	deleteAsset                  persistence.AssetRecord
	deleteReplay                 bool
	deleteErr                    error
	deleteCalls                  int
	deleteCommand                persistence.DeleteCommand
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

func (stub *repositoryStub) AcquireUploadImageVariantContentLock(
	context.Context,
	uuid.UUID,
	media.Variant,
) (func() error, error) {
	if stub.contentLockErr != nil {
		return nil, stub.contentLockErr
	}
	stub.contentMutex.Lock()
	return func() error {
		stub.contentMutex.Unlock()
		return nil
	}, nil
}

func (stub *repositoryStub) UploadSessionForPrincipal(context.Context, uuid.UUID, uuid.UUID, string) (persistence.AssetRecord, error) {
	return stub.sessionAsset, stub.sessionErr
}

func (stub *repositoryStub) UploadSessionForCustomer(context.Context, uuid.UUID, uuid.UUID) (persistence.AssetRecord, error) {
	return stub.sessionAsset, stub.sessionErr
}

func (stub *repositoryStub) UploadImageVariantForPrincipal(
	context.Context,
	uuid.UUID,
	uuid.UUID,
	string,
	media.Variant,
) (persistence.AssetRecord, persistence.UploadImageVariantPart, error) {
	return stub.variantAsset, stub.variantPart, stub.variantErr
}

func (stub *repositoryStub) UploadImageVariantForCustomer(
	context.Context,
	uuid.UUID,
	uuid.UUID,
	media.Variant,
) (persistence.AssetRecord, persistence.UploadImageVariantPart, error) {
	return stub.variantAsset, stub.variantPart, stub.variantErr
}

func (stub *repositoryStub) CompleteUploadImageVariant(
	_ context.Context,
	command persistence.CompleteUploadImageVariantCommand,
) (persistence.UploadImageVariantPart, bool, error) {
	stub.completeVariantCommand = command
	if stub.completeVariantErr != nil {
		return persistence.UploadImageVariantPart{}, false, stub.completeVariantErr
	}
	if stub.completeVariantPart.Variant == "" {
		return stub.variantPart, stub.completeVariantReplay, nil
	}
	return stub.completeVariantPart, stub.completeVariantReplay, nil
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

func (stub *repositoryStub) ReadOwnerAssetsForCustomer(_ context.Context, _, _ string, _, _ uuid.UUID, _ int,
	_ *uuid.UUID, consume func([]persistence.AssetWithVariants) error,
) error {
	if stub.ownerReadErr != nil {
		return stub.ownerReadErr
	}
	if stub.ownerRecords == nil {
		return errors.New("unexpected ReadOwnerAssetsForCustomer")
	}
	return consume(stub.ownerRecords)
}

func (stub *repositoryStub) ReadTaskBoardEntryAssetsForWorker(_ context.Context, entryID, warehouseID, workerID uuid.UUID,
	_ int, _ *uuid.UUID, consume func([]persistence.AssetWithVariants) error,
) error {
	stub.workerListCalls++
	stub.workerListEntryID, stub.workerListWarehouseID, stub.workerListWorkerID = entryID, warehouseID, workerID
	if stub.workerListErr != nil {
		return stub.workerListErr
	}
	if stub.workerListRecords == nil {
		return errors.New("unexpected ReadTaskBoardEntryAssetsForWorker")
	}
	return consume(stub.workerListRecords)
}

func (stub *repositoryStub) ReadDriverShiftAssetsForWorker(_ context.Context, shiftID, warehouseID, workerID uuid.UUID,
	_ int, _ *uuid.UUID, consume func([]persistence.AssetWithVariants) error,
) error {
	stub.driverListCalls++
	stub.driverListShiftID, stub.driverListWarehouseID, stub.driverListWorkerID = shiftID, warehouseID, workerID
	if stub.driverListErr != nil {
		return stub.driverListErr
	}
	if stub.driverListRecords == nil {
		return errors.New("unexpected ReadDriverShiftAssetsForWorker")
	}
	return consume(stub.driverListRecords)
}

func (stub *repositoryStub) ReadCabinCovers(_ context.Context, warehouseID uuid.UUID, cabinIDs []uuid.UUID,
	consume func([]persistence.CabinCoverRecord) error,
) error {
	stub.cabinCoverCalls++
	stub.cabinCoverWarehouseID = warehouseID
	stub.cabinCoverIDs = append([]uuid.UUID(nil), cabinIDs...)
	if stub.cabinCoverErr != nil {
		return stub.cabinCoverErr
	}
	if stub.cabinCoverRecords == nil {
		return errors.New("unexpected ReadCabinCovers")
	}
	return consume(stub.cabinCoverRecords)
}

func (stub *repositoryStub) ReadCabinPresentationSnapshots(_ context.Context, warehouseID uuid.UUID, cabinIDs []uuid.UUID,
	consume func([]persistence.CabinPresentationSnapshotRecord) error,
) error {
	stub.cabinPresentationCalls++
	stub.cabinPresentationWarehouseID = warehouseID
	stub.cabinPresentationIDs = append([]uuid.UUID(nil), cabinIDs...)
	if stub.cabinPresentationErr != nil {
		return stub.cabinPresentationErr
	}
	if stub.cabinPresentationRecords == nil {
		return errors.New("unexpected ReadCabinPresentationSnapshots")
	}
	return consume(stub.cabinPresentationRecords)
}

func (stub *repositoryStub) ReadCabinPresentationVariant(_ context.Context, cabinID, warehouseID, mediaID uuid.UUID,
	generation int, variant media.Variant, consume func(persistence.VariantRecord) error,
) error {
	stub.presentationReadCalls++
	stub.presentationReadCabinID, stub.presentationReadWarehouseID = cabinID, warehouseID
	stub.presentationReadMediaID, stub.presentationReadGeneration = mediaID, generation
	stub.presentationReadVariant = variant
	if stub.presentationReadErr != nil {
		return stub.presentationReadErr
	}
	if stub.presentationVariant == nil {
		return errors.New("unexpected ReadCabinPresentationVariant")
	}
	return consume(*stub.presentationVariant)
}

func (stub *repositoryStub) SetCabinCoverFromTaskEvidence(
	_ context.Context,
	command persistence.SetCabinCoverFromTaskEvidenceCommand,
) (persistence.CabinCoverChangeRecord, bool, error) {
	stub.cabinCoverChangeCommand = command
	return stub.cabinCoverChange, stub.cabinCoverChangeReplay, stub.cabinCoverChangeErr
}

func (stub *repositoryStub) ApplyInventoryCabinPhotos(
	_ context.Context,
	command persistence.ApplyInventoryCabinPhotosCommand,
) (persistence.InventoryCabinPhotoResult, bool, bool, error) {
	stub.inventoryCabinPhotoCalls++
	stub.inventoryCabinPhotoCommand = command
	return stub.inventoryCabinPhotoResult, stub.inventoryCabinPhotoReplay,
		stub.inventoryCabinPhotoChanged, stub.inventoryCabinPhotoErr
}

func (stub *repositoryStub) ReadOriginal(_ context.Context, mediaID uuid.UUID, ownerType, ownerID string, warehouseID uuid.UUID, generation *int,
	consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	stub.originalReadCalls++
	stub.originalReadMediaID, stub.originalReadOwnerType, stub.originalReadOwnerID = mediaID, ownerType, ownerID
	stub.originalReadWarehouseID = warehouseID
	if generation != nil {
		value := *generation
		stub.originalReadGeneration = &value
	}
	if stub.originalReadErr != nil {
		return stub.originalReadErr
	}
	if stub.originalAsset.ID == uuid.Nil {
		return errors.New("unexpected ReadOriginal")
	}
	return consume(stub.originalAsset, stub.originalVariant)
}

func (stub *repositoryStub) ReadOriginalForCustomer(_ context.Context, mediaID uuid.UUID, ownerType, ownerID string,
	warehouseID, _ uuid.UUID, generation *int,
	consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	return stub.ReadOriginal(context.Background(), mediaID, ownerType, ownerID, warehouseID, generation, consume)
}

func (stub *repositoryStub) ReadCurrentVariant(_ context.Context, mediaID uuid.UUID, ownerType, ownerID string,
	warehouseID uuid.UUID, generation int, variant media.Variant, consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	stub.currentReadCalls++
	stub.currentReadMediaID, stub.currentReadOwnerType, stub.currentReadOwnerID = mediaID, ownerType, ownerID
	stub.currentReadWarehouseID, stub.currentReadGeneration, stub.currentReadVariant = warehouseID, generation, variant
	if stub.currentReadErr != nil {
		return stub.currentReadErr
	}
	if stub.currentAsset.ID == uuid.Nil {
		return errors.New("unexpected ReadCurrentVariant")
	}
	return consume(stub.currentAsset, stub.currentVariant)
}

func (stub *repositoryStub) ReadCurrentVariantForCustomer(_ context.Context, mediaID uuid.UUID, ownerType, ownerID string,
	warehouseID, _ uuid.UUID, generation int, variant media.Variant,
	consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	return stub.ReadCurrentVariant(context.Background(), mediaID, ownerType, ownerID, warehouseID,
		generation, variant, consume)
}

func (stub *repositoryStub) ReadTaskBoardEntryOriginalForWorker(_ context.Context, entryID, warehouseID, workerID, mediaID uuid.UUID, generation *int,
	consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	stub.workerOriginalCalls++
	stub.workerReadEntryID, stub.workerReadWarehouseID, stub.workerReadWorkerID = entryID, warehouseID, workerID
	stub.workerReadMediaID = mediaID
	if generation != nil {
		value := *generation
		stub.workerReadGeneration = &value
	}
	if stub.workerOriginalErr != nil {
		return stub.workerOriginalErr
	}
	if stub.workerOriginalAsset.ID == uuid.Nil {
		return errors.New("unexpected ReadTaskBoardEntryOriginalForWorker")
	}
	return consume(stub.workerOriginalAsset, stub.workerOriginalVariant)
}

func (stub *repositoryStub) ReadDriverShiftOriginalForWorker(_ context.Context, shiftID, warehouseID, workerID, mediaID uuid.UUID, generation *int,
	consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	stub.driverOriginalCalls++
	stub.driverReadShiftID, stub.driverReadWarehouseID, stub.driverReadWorkerID = shiftID, warehouseID, workerID
	stub.driverReadMediaID = mediaID
	if generation != nil {
		value := *generation
		stub.driverReadGeneration = &value
	}
	if stub.driverOriginalErr != nil {
		return stub.driverOriginalErr
	}
	if stub.driverOriginalAsset.ID == uuid.Nil {
		return errors.New("unexpected ReadDriverShiftOriginalForWorker")
	}
	return consume(stub.driverOriginalAsset, stub.driverOriginalVariant)
}

func (stub *repositoryStub) ReadTaskBoardEntryVariantForWorker(_ context.Context, entryID, warehouseID, workerID, mediaID uuid.UUID, generation int,
	variant media.Variant, consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	stub.workerCurrentCalls++
	stub.workerReadEntryID, stub.workerReadWarehouseID, stub.workerReadWorkerID = entryID, warehouseID, workerID
	stub.workerReadMediaID, stub.workerReadVariant = mediaID, variant
	stub.workerReadGeneration = &generation
	if stub.workerCurrentErr != nil {
		return stub.workerCurrentErr
	}
	if stub.workerCurrentAsset.ID == uuid.Nil {
		return errors.New("unexpected ReadTaskBoardEntryVariantForWorker")
	}
	return consume(stub.workerCurrentAsset, stub.workerCurrentVariant)
}

func (stub *repositoryStub) ReadDriverShiftVariantForWorker(_ context.Context, shiftID, warehouseID, workerID, mediaID uuid.UUID, generation int,
	variant media.Variant, consume func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	stub.driverCurrentCalls++
	stub.driverReadShiftID, stub.driverReadWarehouseID, stub.driverReadWorkerID = shiftID, warehouseID, workerID
	stub.driverReadMediaID, stub.driverReadVariant = mediaID, variant
	stub.driverReadGeneration = &generation
	if stub.driverCurrentErr != nil {
		return stub.driverCurrentErr
	}
	if stub.driverCurrentAsset.ID == uuid.Nil {
		return errors.New("unexpected ReadDriverShiftVariantForWorker")
	}
	return consume(stub.driverCurrentAsset, stub.driverCurrentVariant)
}

func (stub *repositoryStub) AuthorizeTaskBoardEntryWorker(_ context.Context, entryID, warehouseID, workerID uuid.UUID) error {
	stub.workerAuthorizeCalls++
	stub.workerEntryID, stub.workerWarehouseID, stub.workerID = entryID, warehouseID, workerID
	return stub.workerAuthorizeErr
}

func (stub *repositoryStub) AuthorizeDriverShiftWorker(_ context.Context, shiftID, warehouseID, workerID uuid.UUID) error {
	stub.driverAuthorizeCalls++
	stub.driverShiftID, stub.driverWarehouseID, stub.driverWorkerID = shiftID, warehouseID, workerID
	return stub.driverAuthorizeErr
}

func (stub *repositoryStub) GetAssetScoped(_ context.Context, _ uuid.UUID, ownerType, ownerID string, warehouseID uuid.UUID) (persistence.AssetRecord, error) {
	stub.scopedCalls++
	stub.scopedOwnerType, stub.scopedOwnerID, stub.scopedWarehouseID = ownerType, ownerID, warehouseID
	if stub.scopedErr != nil {
		return persistence.AssetRecord{}, stub.scopedErr
	}
	if stub.scopedAsset.ID == uuid.Nil {
		return persistence.AssetRecord{}, errors.New("unexpected GetAssetScoped")
	}
	return stub.scopedAsset, nil
}

func (stub *repositoryStub) ValidateLogisticsReferences(_ context.Context, command persistence.ValidateLogisticsReferencesCommand) error {
	stub.mutex.Lock()
	defer stub.mutex.Unlock()
	stub.validationCalls++
	stub.validationCommand = command
	return stub.validationErr
}

func (stub *repositoryStub) UpsertServiceOwnerProof(_ context.Context, command persistence.ServiceOwnerProofCommand) (persistence.ServiceOwnerProofRecord, bool, error) {
	stub.ownerProofCalls++
	stub.ownerProofCommand = command
	if stub.ownerProofErr != nil {
		return persistence.ServiceOwnerProofRecord{}, false, stub.ownerProofErr
	}
	if stub.ownerProofRecord.ProofEventID == uuid.Nil {
		return persistence.ServiceOwnerProofRecord{}, false, errors.New("unexpected UpsertServiceOwnerProof")
	}
	return stub.ownerProofRecord, stub.ownerProofReplay, nil
}

func (stub *repositoryStub) validationSnapshot() (int, persistence.ValidateLogisticsReferencesCommand) {
	stub.mutex.Lock()
	defer stub.mutex.Unlock()
	return stub.validationCalls, stub.validationCommand
}

func (stub *repositoryStub) Delete(_ context.Context, command persistence.DeleteCommand) (persistence.AssetRecord, bool, error) {
	stub.deleteCalls++
	stub.deleteCommand = command
	if stub.deleteErr != nil {
		return persistence.AssetRecord{}, false, stub.deleteErr
	}
	if stub.deleteAsset.ID == uuid.Nil {
		return persistence.AssetRecord{}, false, errors.New("unexpected Delete")
	}
	return stub.deleteAsset, stub.deleteReplay, nil
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
