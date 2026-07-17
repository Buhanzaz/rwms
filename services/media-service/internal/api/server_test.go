package api

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/auth"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/storage"
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
	if store.ensureCalls != 0 || store.statCalls != 0 || store.getCalls != 0 {
		t.Fatalf("unauthenticated storage calls = ensure:%d stat:%d get:%d", store.ensureCalls, store.statCalls, store.getCalls)
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

func newTestServer(t *testing.T, repository repository, validator tokenValidator, store objectStore) *Server {
	t.Helper()
	server, err := NewServer(repository, readyStub{}, validator, store, Configuration{
		MaxUploadBytes: 1 << 20, AllowedMIMETypes: map[string]struct{}{"image/jpeg": {}},
		UploadExpiry: time.Minute, DownloadExpiry: time.Minute,
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("NewServer() error = %v", err)
	}
	return server
}

type readyStub struct{ err error }

func (stub readyStub) Ready(context.Context) error { return stub.err }

type validatorStub struct {
	principal auth.Principal
	err       error
}

func (stub validatorStub) Validate(context.Context, string) (auth.Principal, error) {
	return stub.principal, stub.err
}

type repositoryStub struct {
	sessionAsset  persistence.AssetRecord
	finalizeCalls int
}

func (stub *repositoryStub) CreateUpload(context.Context, persistence.CreateUploadCommand) (persistence.AssetRecord, bool, error) {
	return persistence.AssetRecord{}, false, errors.New("unexpected CreateUpload")
}

func (stub *repositoryStub) UploadSessionForSubject(context.Context, uuid.UUID, uuid.UUID) (persistence.AssetRecord, error) {
	return stub.sessionAsset, nil
}

func (stub *repositoryStub) FinalizeUpload(context.Context, persistence.FinalizeCommand) (persistence.AssetRecord, bool, error) {
	stub.finalizeCalls++
	return persistence.AssetRecord{}, false, errors.New("unexpected FinalizeUpload")
}

func (stub *repositoryStub) ReadOwnerAssets(context.Context, string, string, uuid.UUID, int,
	*uuid.UUID, func([]persistence.AssetWithVariants) error,
) error {
	return errors.New("unexpected ReadOwnerAssets")
}

func (stub *repositoryStub) ReadOriginal(context.Context, uuid.UUID, string, string, uuid.UUID,
	func(persistence.AssetRecord, *persistence.VariantRecord) error,
) error {
	return errors.New("unexpected ReadOriginal")
}

func (stub *repositoryStub) GetAssetScoped(context.Context, uuid.UUID, string, string, uuid.UUID) (persistence.AssetRecord, error) {
	return persistence.AssetRecord{}, errors.New("unexpected GetAssetScoped")
}

func (stub *repositoryStub) Rotate(context.Context, persistence.RotateCommand) (persistence.AssetRecord, bool, error) {
	return persistence.AssetRecord{}, false, errors.New("unexpected Rotate")
}

type storeStub struct {
	ensureCalls  int
	statCalls    int
	getCalls     int
	statMetadata media.ObjectMetadata
	statErr      error
	objectBody   []byte
}

func (stub *storeStub) EnsureVersioning(context.Context) error {
	stub.ensureCalls++
	return nil
}

func (stub *storeStub) SignedUploadPolicy(context.Context, string, string, string, int64, time.Duration) (storage.UploadPolicy, error) {
	return storage.UploadPolicy{}, errors.New("unexpected SignedUploadPolicy")
}

func (stub *storeStub) StatVersion(context.Context, string, string) (media.ObjectMetadata, error) {
	stub.statCalls++
	return stub.statMetadata, stub.statErr
}

func (stub *storeStub) GetVersion(context.Context, string, string) (io.ReadCloser, media.ObjectMetadata, error) {
	stub.getCalls++
	return io.NopCloser(bytes.NewReader(stub.objectBody)), stub.statMetadata, nil
}

func (stub *storeStub) SignedVersionDownloadURL(context.Context, string, string, time.Duration) (*url.URL, error) {
	return nil, errors.New("unexpected SignedVersionDownloadURL")
}
