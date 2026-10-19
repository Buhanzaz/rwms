package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/assetimport"
	"dev.buhanzaz.rwms/media-service/internal/auth"
	"github.com/google/uuid"
)

func TestAssetImportPreflightRequiresExactServiceScopeAndNeverEchoesSource(t *testing.T) {
	assetImportID, warehouseID, sourceRowID, jobID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	publicURL := "https://disk.yandex.ru/d/AbCdEfGhIjKlMn"
	service := &assetImportServiceStub{preflightJob: assetimport.Job{
		ID: jobID, AssetImportID: assetImportID, WarehouseID: warehouseID, Status: assetimport.StatusPreflightPending,
		Sources: []assetimport.Source{{SourceRowID: sourceRowID, PublicKey: "AbCdEfGhIjKlMn"}},
	}}
	body := `{"assetImportId":"` + assetImportID.String() + `","warehouseId":"` + warehouseID.String() + `","sources":[{"sourceRowId":"` + sourceRowID.String() + `","publicUrl":"` + publicURL + `"}]}`

	t.Run("exact asset service scope queues job without source echo", func(t *testing.T) {
		server := newAssetImportTestServer(t, validatorStub{servicePrincipal: auth.ServicePrincipal{
			Subject: assetimport.AssetImportService, ClientID: assetimport.AssetImportService,
			Scopes: map[string]struct{}{assetimport.AssetImportScope: {}},
		}}, service)
		request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/asset-imports/preflight", strings.NewReader(body))
		request.Header.Set("Authorization", "Bearer service")
		request.Header.Set("Idempotency-Key", uuid.NewString())
		response := httptest.NewRecorder()

		server.Handler().ServeHTTP(response, request)

		if response.Code != http.StatusAccepted || service.preflightCalls != 1 ||
			strings.Contains(response.Body.String(), publicURL) || strings.Contains(response.Body.String(), "AbCdEfGhIjKlMn") ||
			!strings.Contains(response.Body.String(), `"jobId":"`+jobID.String()+`"`) {
			t.Fatalf("response=%d calls=%d body=%s", response.Code, service.preflightCalls, response.Body.String())
		}
		if service.preflightCommand.RequestSHA256 != assetimport.CanonicalPreflightSHA(assetImportID, warehouseID, service.preflightCommand.Sources) {
			t.Fatalf("preflight command = %#v", service.preflightCommand)
		}
	})

	t.Run("combined service scopes fail closed", func(t *testing.T) {
		service.preflightCalls = 0
		server := newAssetImportTestServer(t, validatorStub{servicePrincipal: auth.ServicePrincipal{
			Subject: assetimport.AssetImportService, ClientID: assetimport.AssetImportService,
			Scopes: map[string]struct{}{assetimport.AssetImportScope: {}, "asset.read": {}},
		}}, service)
		request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/asset-imports/preflight", strings.NewReader(body))
		request.Header.Set("Authorization", "Bearer service")
		request.Header.Set("Idempotency-Key", uuid.NewString())
		response := httptest.NewRecorder()

		server.Handler().ServeHTTP(response, request)

		if response.Code != http.StatusForbidden || service.preflightCalls != 0 || !strings.Contains(response.Body.String(), `"code":"MEDIA_FORBIDDEN"`) {
			t.Fatalf("response=%d calls=%d body=%s", response.Code, service.preflightCalls, response.Body.String())
		}
	})

}

func TestAssetImportReplacementRequiresExactServiceScopeAndNeverEchoesSource(t *testing.T) {
	assetImportID, warehouseID, sourceRowID, jobID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	publicURL := "https://disk.yandex.ru/d/QrStUvWxYz0123"
	service := &assetImportServiceStub{preflightJob: assetimport.Job{
		ID: jobID, AssetImportID: assetImportID, WarehouseID: warehouseID, Status: assetimport.StatusPreflightPending,
		Sources: []assetimport.Source{{SourceRowID: sourceRowID, PublicKey: "QrStUvWxYz0123"}},
	}}
	server := newAssetImportTestServer(t, validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: assetimport.AssetImportService, ClientID: assetimport.AssetImportService,
		Scopes: map[string]struct{}{assetimport.AssetImportScope: {}},
	}}, service)
	body := `{"sources":[{"sourceRowId":"` + sourceRowID.String() + `","publicUrl":"` + publicURL + `"}]}`
	request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/asset-imports/"+jobID.String()+"/replace-sources", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer service")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusAccepted || service.replaceCalls != 1 ||
		strings.Contains(response.Body.String(), publicURL) || strings.Contains(response.Body.String(), "QrStUvWxYz0123") ||
		!strings.Contains(response.Body.String(), `"jobId":"`+jobID.String()+`"`) {
		t.Fatalf("response=%d calls=%d body=%s", response.Code, service.replaceCalls, response.Body.String())
	}
	if service.replaceCommand.RequestSHA256 != assetimport.CanonicalReplaceSourcesSHA(jobID, service.replaceCommand.Replacements) ||
		len(service.replaceCommand.Replacements) != 1 || service.replaceCommand.Replacements[0] != (assetimport.SourceReplacement{SourceRowID: sourceRowID, PublicKey: "QrStUvWxYz0123"}) {
		t.Fatalf("replace command = %#v", service.replaceCommand)
	}
}

func TestAssetImportUnsupportedMethodReturnsMethodNotAllowed(t *testing.T) {
	server := newAssetImportTestServer(t, validatorStub{servicePrincipal: auth.ServicePrincipal{
		Subject: assetimport.AssetImportService, ClientID: assetimport.AssetImportService,
		Scopes: map[string]struct{}{assetimport.AssetImportScope: {}},
	}}, &assetImportServiceStub{})
	request := httptest.NewRequest(http.MethodDelete, "/api/internal/media/v1/asset-imports/"+uuid.NewString()+"/activate", nil)
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusMethodNotAllowed || !strings.Contains(response.Body.String(), `"code":"MEDIA_METHOD_NOT_ALLOWED"`) {
		t.Fatalf("response=%d body=%s", response.Code, response.Body.String())
	}
}

func TestAssetImportPreflightSourceBoundAcceptsFiveHundredAndRejectsFiveHundredOne(t *testing.T) {
	for _, scenario := range []struct {
		name       string
		sources    int
		wantStatus int
		wantCalls  int
	}{
		{name: "maximum accepted", sources: 500, wantStatus: http.StatusAccepted, wantCalls: 1},
		{name: "one over maximum rejected", sources: 501, wantStatus: http.StatusConflict, wantCalls: 0},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			body := assetImportPreflightJSON(t, scenario.sources)
			if len(body) > 64<<10 {
				t.Fatalf("%d source request is %d bytes, exceeding handler limit", scenario.sources, len(body))
			}
			service := &assetImportServiceStub{preflightJob: assetimport.Job{
				ID: uuid.New(), AssetImportID: uuid.New(), WarehouseID: uuid.New(), Status: assetimport.StatusPreflightPending,
			}}
			server := newAssetImportTestServer(t, validatorStub{servicePrincipal: auth.ServicePrincipal{
				Subject: assetimport.AssetImportService, ClientID: assetimport.AssetImportService,
				Scopes: map[string]struct{}{assetimport.AssetImportScope: {}},
			}}, service)
			request := httptest.NewRequest(http.MethodPost, "/api/internal/media/v1/asset-imports/preflight", bytes.NewReader(body))
			request.Header.Set("Authorization", "Bearer service")
			request.Header.Set("Idempotency-Key", uuid.NewString())
			response := httptest.NewRecorder()

			server.Handler().ServeHTTP(response, request)

			if response.Code != scenario.wantStatus || service.preflightCalls != scenario.wantCalls {
				t.Fatalf("sources=%d response=%d calls=%d body=%s", scenario.sources, response.Code, service.preflightCalls, response.Body.String())
			}
			if scenario.wantCalls == 1 && len(service.preflightCommand.Sources) != scenario.sources {
				t.Fatalf("accepted source count = %d, want %d", len(service.preflightCommand.Sources), scenario.sources)
			}
		})
	}
}

func assetImportPreflightJSON(t *testing.T, sources int) []byte {
	t.Helper()
	body := assetImportPreflightRequest{AssetImportID: uuid.NewString(), WarehouseID: uuid.NewString(), Sources: make([]assetImportSourceRequest, 0, sources)}
	for range sources {
		body.Sources = append(body.Sources, assetImportSourceRequest{
			SourceRowID: uuid.NewString(), PublicURL: "https://disk.yandex.ru/d/AbCdEfGhIjKlMn",
		})
	}
	encoded, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal preflight body: %v", err)
	}
	return encoded
}

func newAssetImportTestServer(t *testing.T, validator tokenValidator, imports assetImportService) *Server {
	t.Helper()
	server, err := NewServer(&repositoryStub{}, readyStub{}, validator, &storeStub{}, Configuration{
		MaxUploadBytes: 1 << 20, AllowedMIMETypes: map[string]struct{}{"image/jpeg": {}},
		UploadExpiry: time.Minute, AssetImports: imports,
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("NewServer() error = %v", err)
	}
	return server
}

type assetImportServiceStub struct {
	preflightJob     assetimport.Job
	preflightCommand assetimport.CreateCommand
	preflightCalls   int
	replaceCommand   assetimport.ReplaceSourcesCommand
	replaceCalls     int
}

func (stub *assetImportServiceStub) Preflight(_ context.Context, command assetimport.CreateCommand) (assetimport.Job, bool, error) {
	stub.preflightCalls++
	stub.preflightCommand = command
	return stub.preflightJob, false, nil
}

func (stub *assetImportServiceStub) Get(context.Context, uuid.UUID) (assetimport.Job, error) {
	return stub.preflightJob, nil
}

func (stub *assetImportServiceStub) Activate(context.Context, assetimport.ActivateCommand) (assetimport.Job, bool, error) {
	return stub.preflightJob, false, nil
}

func (stub *assetImportServiceStub) ReplacePreflightSources(_ context.Context, command assetimport.ReplaceSourcesCommand) (assetimport.Job, bool, error) {
	stub.replaceCalls++
	stub.replaceCommand = command
	return stub.preflightJob, false, nil
}

func (stub *assetImportServiceStub) Retry(context.Context, assetimport.RetryCommand) (assetimport.Job, bool, error) {
	return stub.preflightJob, false, nil
}
