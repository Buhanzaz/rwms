package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"log/slog"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/auth"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/storage"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

func TestHTTPSameOriginUploadFinalizeAndRestartIntegration(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	minioEndpoint := os.Getenv("MEDIA_TEST_MINIO_ENDPOINT")
	if databaseURL == "" || minioEndpoint == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL and MEDIA_TEST_MINIO_ENDPOINT are required")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	database, err := persistence.Open(ctx, databaseURL)
	if err != nil {
		t.Fatalf("persistence.Open() error = %v", err)
	}
	defer database.Close()
	repository := persistence.NewRepository(database.Pool)
	minioAccessKey := os.Getenv("MEDIA_TEST_MINIO_ACCESS_KEY")
	minioSecretKey := os.Getenv("MEDIA_TEST_MINIO_SECRET_KEY")
	minioBucket := testsupport.NewVersionedMinIOBucket(t, minioEndpoint,
		minioAccessKey, minioSecretKey, false)
	store, err := storage.NewMinIOObjectStore(storage.MinIOOptions{
		Endpoint:  minioEndpoint,
		AccessKey: minioAccessKey,
		SecretKey: minioSecretKey,
		Bucket:    minioBucket,
	})
	if err != nil {
		t.Fatalf("storage.NewMinIOObjectStore() error = %v", err)
	}
	if err := store.EnsureVersioning(ctx); err != nil {
		t.Fatalf("EnsureVersioning() error = %v", err)
	}

	warehouseID := uuid.New()
	ownerID := uuid.New()
	proof := persistence.ValidatedOwnerProof{
		ConsumerName:     persistence.InventoryOwnerConsumerGroup,
		EventID:          uuid.New(),
		BodySHA256:       strings.Repeat("1", 64),
		AggregateType:    persistence.InventoryFindingAggregate,
		AggregateID:      ownerID,
		AggregateVersion: 1,
		OwnerType:        persistence.OwnerTypeInventoryFinding,
		OwnerID:          ownerID.String(),
		WarehouseID:      warehouseID,
		OwnerRevision:    1,
		Active:           true,
		RecordedAt:       time.Now().UTC(),
	}
	if replayed, err := repository.ApplyValidatedOwnerProof(ctx, proof); err != nil || replayed {
		t.Fatalf("ApplyValidatedOwnerProof() = %v, %v; want a new proof", replayed, err)
	}

	jwtFixture := newIntegrationJWTFixture(t)
	validator, err := auth.NewValidator(jwtFixture.issuer, jwtFixture.audience, jwtFixture.server.URL)
	if err != nil {
		t.Fatalf("auth.NewValidator() error = %v", err)
	}
	server, err := NewServer(repository, database, validator, store, Configuration{
		MaxUploadBytes: 1 << 20,
		AllowedMIMETypes: map[string]struct{}{
			"image/jpeg": {},
		},
		UploadExpiry: 5 * time.Minute,
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("NewServer() error = %v", err)
	}

	runtime := startIntegrationHTTPRuntime(t, server.Handler())
	t.Cleanup(func() { runtime.stop(t) })
	client := &http.Client{Timeout: 10 * time.Second}
	subjectID := uuid.New()
	validToken := jwtFixture.token(t, subjectID, warehouseID)
	foreignSubjectToken := jwtFixture.token(t, uuid.New(), warehouseID)
	foreignWarehouseToken := jwtFixture.token(t, uuid.New(), uuid.New())

	file := integrationJPEG(t)
	checksumBytes := sha256.Sum256(file)
	checksum := hex.EncodeToString(checksumBytes[:])
	createBody := map[string]any{
		"ownerType":      persistence.OwnerTypeInventoryFinding,
		"ownerId":        ownerID,
		"warehouseId":    warehouseID,
		"context":        persistence.ViewerContextInspection,
		"fileName":       "http-integration.jpg",
		"contentType":    "image/jpeg",
		"contentLength":  len(file),
		"checksumSha256": checksum,
		"sortOrder":      0,
	}

	unauthorized := doIntegrationJSON(t, client, http.MethodPost, runtime.baseURL+"/api/media/v1/upload-sessions", "", uuid.New(), createBody)
	assertIntegrationProblem(t, unauthorized, http.StatusUnauthorized, "MEDIA_UNAUTHORIZED")

	forbidden := doIntegrationJSON(t, client, http.MethodPost, runtime.baseURL+"/api/media/v1/upload-sessions", foreignWarehouseToken, uuid.New(), createBody)
	assertIntegrationProblem(t, forbidden, http.StatusForbidden, "MEDIA_FORBIDDEN")

	createdResponse := doIntegrationJSON(t, client, http.MethodPost, runtime.baseURL+"/api/media/v1/upload-sessions", validToken, uuid.New(), createBody)
	if createdResponse.status != http.StatusCreated {
		t.Fatalf("create status = %d, want %d; body=%s", createdResponse.status, http.StatusCreated, createdResponse.body)
	}
	var created struct {
		UploadSessionID uuid.UUID `json:"uploadSessionId"`
		MediaID         uuid.UUID `json:"mediaId"`
		ContentPath     string    `json:"contentUploadUrl"`
	}
	if err := json.Unmarshal(createdResponse.body, &created); err != nil {
		t.Fatalf("decode create response: %v; body=%s", err, createdResponse.body)
	}
	if created.UploadSessionID == uuid.Nil || created.MediaID == uuid.Nil ||
		created.ContentPath != "/api/media/v1/upload-sessions/"+created.UploadSessionID.String()+"/content" {
		t.Fatalf("incomplete create response = %#v", created)
	}
	for _, forbidden := range []string{`"uploadUrl":`, `"formFields":`, minioEndpoint, "http://", "https://"} {
		if bytes.Contains(createdResponse.body, []byte(forbidden)) {
			t.Fatalf("create response leaked %q: %s", forbidden, createdResponse.body)
		}
	}

	finalizeKey := uuid.New()
	contentURL := runtime.baseURL + created.ContentPath
	foreignContent := doIntegrationContent(t, client, contentURL, foreignSubjectToken, uuid.New(), "image/jpeg", file)
	assertIntegrationProblem(t, foreignContent, http.StatusNotFound, "MEDIA_NOT_FOUND")
	uploadedResponse := doIntegrationContent(t, client, contentURL, validToken, finalizeKey, "image/jpeg", file)
	if uploadedResponse.status != http.StatusCreated {
		t.Fatalf("content status = %d, want %d; body=%s", uploadedResponse.status, http.StatusCreated, uploadedResponse.body)
	}
	var uploaded uploadedObjectResponse
	if err := json.Unmarshal(uploadedResponse.body, &uploaded); err != nil {
		t.Fatalf("decode uploaded object: %v; body=%s", err, uploadedResponse.body)
	}
	if uploaded.ObjectVersionID == "" || uploaded.ETag == "" || uploaded.ChecksumSHA256 != checksum {
		t.Fatalf("uploaded object = %#v", uploaded)
	}
	replayedContent := doIntegrationContent(t, client, contentURL, validToken, finalizeKey, "image/jpeg", file)
	if replayedContent.status != http.StatusOK || !bytes.Equal(replayedContent.body, uploadedResponse.body) {
		t.Fatalf("content replay = %d %s; want exact %s", replayedContent.status, replayedContent.body, uploadedResponse.body)
	}
	conflictingContent := doIntegrationContent(t, client, contentURL, validToken, uuid.New(), "image/jpeg", file)
	assertIntegrationProblem(t, conflictingContent, http.StatusConflict, "MEDIA_CONFLICT")

	finalizeBody := map[string]any{
		"objectVersionId": uploaded.ObjectVersionID,
		"etag":            uploaded.ETag,
		"checksumSha256":  checksum,
	}
	finalizeURL := runtime.baseURL + "/api/media/v1/upload-sessions/" + created.UploadSessionID.String() + "/complete"
	foreignFinalize := doIntegrationJSON(t, client, http.MethodPost, finalizeURL, foreignSubjectToken, uuid.New(), finalizeBody)
	assertIntegrationProblem(t, foreignFinalize, http.StatusNotFound, "MEDIA_NOT_FOUND")

	finalizedResponse := doIntegrationJSON(t, client, http.MethodPost, finalizeURL, validToken, finalizeKey, finalizeBody)
	if finalizedResponse.status != http.StatusOK {
		t.Fatalf("finalize status = %d, want %d; body=%s", finalizedResponse.status, http.StatusOK, finalizedResponse.body)
	}
	var finalized integrationAssetResponse
	if err := json.Unmarshal(finalizedResponse.body, &finalized); err != nil {
		t.Fatalf("decode finalize response: %v; body=%s", err, finalizedResponse.body)
	}
	if finalized.ID != created.MediaID || finalized.Status != media.StatusProcessing || finalized.Version != 2 {
		t.Fatalf("finalized response = %#v; want media=%s status=%s version=2", finalized, created.MediaID, media.StatusProcessing)
	}

	stored, err := repository.GetAssetScoped(ctx, created.MediaID, persistence.OwnerTypeInventoryFinding, ownerID.String(), warehouseID)
	if err != nil {
		t.Fatalf("GetAssetScoped() error = %v", err)
	}
	if stored.SourceVersionID != uploaded.ObjectVersionID {
		t.Fatalf("stored source version = %q, want exact ingress version %q", stored.SourceVersionID, uploaded.ObjectVersionID)
	}
	if stored.SourceETag != uploaded.ETag || stored.SourceChecksum != checksum || stored.Status != media.StatusProcessing || stored.Version != 2 {
		t.Fatalf("stored immutable source metadata = %#v", stored)
	}

	runtime.stop(t)
	restarted := startIntegrationHTTPRuntime(t, server.Handler())
	t.Cleanup(func() { restarted.stop(t) })
	readyResponse, err := client.Get(restarted.baseURL + "/health/ready")
	if err != nil {
		t.Fatalf("GET readiness after restart: %v", err)
	}
	readyBody, readErr := io.ReadAll(readyResponse.Body)
	_ = readyResponse.Body.Close()
	if readErr != nil || readyResponse.StatusCode != http.StatusOK {
		t.Fatalf("readiness after restart = %d %s, read error=%v", readyResponse.StatusCode, readyBody, readErr)
	}
	restarted.stop(t)
}

type integrationAssetResponse struct {
	ID      uuid.UUID    `json:"id"`
	Status  media.Status `json:"status"`
	Version int64        `json:"version"`
}

type integrationHTTPResponse struct {
	status int
	header http.Header
	body   []byte
}

func doIntegrationJSON(t *testing.T, client *http.Client, method, target, token string, idempotencyKey uuid.UUID, body any) integrationHTTPResponse {
	t.Helper()
	wire, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("encode request JSON: %v", err)
	}
	request, err := http.NewRequest(method, target, bytes.NewReader(wire))
	if err != nil {
		t.Fatalf("http.NewRequest() error = %v", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", idempotencyKey.String())
	request.Header.Set(correlationHeader, uuid.NewString())
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("%s %s: %v", method, target, err)
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read %s %s response: %v", method, target, err)
	}
	return integrationHTTPResponse{status: response.StatusCode, header: response.Header.Clone(), body: responseBody}
}

func assertIntegrationProblem(t *testing.T, response integrationHTTPResponse, status int, code string) {
	t.Helper()
	if response.status != status || response.header.Get("Content-Type") != "application/problem+json" ||
		!bytes.Contains(response.body, []byte(`"code":"`+code+`"`)) {
		t.Fatalf("problem response = %d %q %s; want %d %s", response.status, response.header.Get("Content-Type"), response.body, status, code)
	}
}

func doIntegrationContent(
	t *testing.T,
	client *http.Client,
	target, token string,
	idempotencyKey uuid.UUID,
	contentType string,
	body []byte,
) integrationHTTPResponse {
	t.Helper()
	request, err := http.NewRequest(http.MethodPut, target, bytes.NewReader(body))
	if err != nil {
		t.Fatalf("create content request: %v", err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Idempotency-Key", idempotencyKey.String())
	request.Header.Set("Content-Type", contentType)
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("PUT same-origin content: %v", err)
	}
	defer response.Body.Close()
	responseBody, readErr := io.ReadAll(response.Body)
	if readErr != nil {
		t.Fatalf("read content upload response: %v", readErr)
	}
	return integrationHTTPResponse{status: response.StatusCode, header: response.Header.Clone(), body: responseBody}
}

func integrationJPEG(t *testing.T) []byte {
	t.Helper()
	imageValue := image.NewRGBA(image.Rect(0, 0, 3, 2))
	imageValue.Set(0, 0, color.RGBA{R: 230, G: 40, B: 20, A: 255})
	imageValue.Set(1, 0, color.RGBA{R: 20, G: 180, B: 60, A: 255})
	imageValue.Set(2, 0, color.RGBA{R: 40, G: 80, B: 220, A: 255})
	var encoded bytes.Buffer
	if err := jpeg.Encode(&encoded, imageValue, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatalf("encode JPEG fixture: %v", err)
	}
	return encoded.Bytes()
}

type integrationJWTFixture struct {
	privateKey *rsa.PrivateKey
	issuer     string
	audience   string
	server     *httptest.Server
}

func newIntegrationJWTFixture(t *testing.T) *integrationJWTFixture {
	t.Helper()
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("rsa.GenerateKey() error = %v", err)
	}
	fixture := &integrationJWTFixture{
		privateKey: privateKey,
		issuer:     "https://auth.media-integration.test",
		audience:   "rwms-services",
	}
	fixture.server = httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(response).Encode(map[string]any{"keys": []any{map[string]any{
			"kty": "RSA",
			"use": "sig",
			"alg": "RS256",
			"kid": "media-http-integration",
			"n":   base64.RawURLEncoding.EncodeToString(privateKey.PublicKey.N.Bytes()),
			"e":   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(privateKey.PublicKey.E)).Bytes()),
		}}})
	}))
	t.Cleanup(fixture.server.Close)
	return fixture
}

func (fixture *integrationJWTFixture) token(t *testing.T, subjectID, warehouseID uuid.UUID) string {
	t.Helper()
	now := time.Now().UTC()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"iss":            fixture.issuer,
		"aud":            fixture.audience,
		"sub":            subjectID.String(),
		"iat":            now.Add(-time.Minute).Unix(),
		"nbf":            now.Add(-time.Minute).Unix(),
		"exp":            now.Add(5 * time.Minute).Unix(),
		"principal_type": "USER",
		"scope":          "rwms.read rwms.write",
		"warehouse_access": []any{map[string]any{
			"warehouseId": warehouseID.String(),
			"level":       "EDIT",
		}},
	})
	token.Header["kid"] = "media-http-integration"
	signed, err := token.SignedString(fixture.privateKey)
	if err != nil {
		t.Fatalf("sign integration token: %v", err)
	}
	return signed
}

type integrationHTTPRuntime struct {
	baseURL string
	server  *http.Server
	done    chan error
	once    sync.Once
}

func startIntegrationHTTPRuntime(t *testing.T, handler http.Handler) *integrationHTTPRuntime {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen for integration HTTP server: %v", err)
	}
	runtime := &integrationHTTPRuntime{
		baseURL: "http://" + listener.Addr().String(),
		server: &http.Server{
			Handler:           handler,
			ReadHeaderTimeout: 5 * time.Second,
		},
		done: make(chan error, 1),
	}
	go func() {
		runtime.done <- runtime.server.Serve(listener)
	}()
	return runtime
}

func (runtime *integrationHTTPRuntime) stop(t *testing.T) {
	t.Helper()
	runtime.once.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := runtime.server.Shutdown(ctx); err != nil {
			t.Errorf("HTTP server Shutdown() error = %v", err)
		}
		if err := <-runtime.done; !errors.Is(err, http.ErrServerClosed) {
			t.Errorf("HTTP server Serve() error = %v, want http.ErrServerClosed", err)
		}
	})
}
