package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
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
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

// TestHTTPAcceptsSixConcurrentImageVariantStreamsIntegration exercises the
// real PostgreSQL locks and HTTP handlers at the Manager's shared stream limit.
// Storage holds every PUT until all six have entered, so sequential reception
// cannot satisfy the test merely by eventually completing all requests.
func TestHTTPAcceptsSixConcurrentImageVariantStreamsIntegration(t *testing.T) {
	baseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if baseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL := testsupport.NewMigratedMediaDatabase(t, baseURL)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := persistence.Open(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	repository := persistence.NewRepository(database.Pool)
	warehouseID, ownerID, subjectID := uuid.New(), uuid.New(), uuid.New()
	_, err = repository.ApplyValidatedOwnerProof(ctx, persistence.ValidatedOwnerProof{
		ConsumerName: persistence.InventoryOwnerConsumerGroup, EventID: uuid.New(),
		BodySHA256: strings.Repeat("a", 64), AggregateType: persistence.InventoryFindingAggregate,
		AggregateID: ownerID, AggregateVersion: 1, OwnerType: persistence.OwnerTypeInventoryFinding,
		OwnerID: ownerID.String(), WarehouseID: warehouseID, OwnerRevision: 0,
		Active: true, RecordedAt: time.Now().UTC(),
	})
	if err != nil {
		t.Fatal(err)
	}
	store := &parallelImageStore{
		entered: make(chan string, 6), release: make(chan struct{}),
		objects: make(map[string]media.ObjectMetadata),
	}
	server := newTestServer(t, repository, validatorStub{principal: auth.Principal{
		SubjectID: subjectID, Scopes: map[string]struct{}{"rwms.write": {}},
		Grants: []auth.WarehouseGrant{{WarehouseID: warehouseID, Level: auth.Edit}},
	}}, store)
	runtime := httptest.NewServer(server.Handler())
	defer runtime.Close()
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(func() { close(store.release) }) }
	defer release()
	client := runtime.Client()
	client.Timeout = 15 * time.Second

	type imageSession struct {
		UploadSessionID uuid.UUID `json:"uploadSessionId"`
		MediaID         uuid.UUID `json:"mediaId"`
		VariantURLs     []struct {
			Kind media.Variant `json:"kind"`
			Path string        `json:"contentUploadUrl"`
		} `json:"variantUploadUrls"`
	}
	type pendingPart struct {
		imageIndex int
		kind       media.Variant
		path       string
		body       []byte
		checksum   string
	}
	var sessions [2]imageSession
	var pending []pendingPart
	for imageIndex := range sessions {
		create := createUploadRequest{
			OwnerType: persistence.OwnerTypeInventoryFinding, OwnerID: ownerID.String(),
			WarehouseID: warehouseID.String(), Context: persistence.ViewerContextInspection,
			FileName: fmt.Sprintf("parallel-%d.jpg", imageIndex),
		}
		bodies := make(map[media.Variant][]byte)
		for variantIndex, kind := range []media.Variant{media.VariantSmall, media.VariantMedium, media.VariantLarge} {
			body := []byte(fmt.Sprintf("synthetic-client-webp-%d-%s", imageIndex, kind))
			bodies[kind] = body
			digest := sha256.Sum256(body)
			create.ImageVariants = append(create.ImageVariants, createImageVariantRequest{
				Kind: kind, ContentLength: int64(len(body)), ChecksumSHA256: hex.EncodeToString(digest[:]),
				Width: 320 * (variantIndex + 1), Height: 180 * (variantIndex + 1),
			})
		}
		body, err := json.Marshal(create)
		if err != nil {
			t.Fatal(err)
		}
		status, response, err := parallelImageRequest(ctx, client, http.MethodPost,
			runtime.URL+"/api/media/v1/upload-sessions", "application/json", body)
		if err != nil || status != http.StatusCreated {
			t.Fatalf("create image %d: status=%d body=%s error=%v", imageIndex, status, response, err)
		}
		if err := json.Unmarshal(response, &sessions[imageIndex]); err != nil {
			t.Fatal(err)
		}
		if len(sessions[imageIndex].VariantURLs) != 3 {
			t.Fatalf("image %d has %d variant paths", imageIndex, len(sessions[imageIndex].VariantURLs))
		}
		for _, variant := range sessions[imageIndex].VariantURLs {
			body := bodies[variant.Kind]
			digest := sha256.Sum256(body)
			pending = append(pending, pendingPart{imageIndex, variant.Kind, variant.Path,
				body, hex.EncodeToString(digest[:])})
		}
	}
	type uploadResult struct {
		part     pendingPart
		status   int
		response []byte
		err      error
	}
	results := make(chan uploadResult, len(pending))
	for _, part := range pending {
		go func() {
			status, response, err := parallelImageRequest(ctx, client, http.MethodPut,
				runtime.URL+part.path, "image/webp", part.body)
			results <- uploadResult{part, status, response, err}
		}()
	}
	barrier := time.NewTimer(10 * time.Second)
	defer barrier.Stop()
	entered := make(map[string]struct{}, len(pending))
	for len(entered) < len(pending) {
		select {
		case key := <-store.entered:
			if _, duplicate := entered[key]; duplicate {
				t.Fatalf("duplicate variant stream at barrier: %s", key)
			}
			entered[key] = struct{}{}
		case result := <-results:
			t.Fatalf("request completed before six streams entered storage: status=%d body=%s error=%v",
				result.status, result.response, result.err)
		case <-barrier.C:
			t.Fatalf("only %d of six variant streams entered storage concurrently", len(entered))
		}
	}
	// Metadata work must remain possible while all six content locks are held.
	if err := database.Pool.Ping(ctx); err != nil {
		t.Fatalf("database unavailable during six concurrent PUTs: %v", err)
	}
	release()
	var completions [2][]finalizeImageVariantRequest
	for range pending {
		result := <-results
		if result.err != nil || result.status != http.StatusCreated {
			t.Fatalf("upload %s: status=%d body=%s error=%v", result.part.kind,
				result.status, result.response, result.err)
		}
		var uploaded uploadedObjectResponse
		if err := json.Unmarshal(result.response, &uploaded); err != nil {
			t.Fatal(err)
		}
		session := sessions[result.part.imageIndex]
		_, persisted, err := repository.UploadImageVariantForPrincipal(ctx,
			session.UploadSessionID, subjectID, persistence.PrincipalTypeUser, result.part.kind)
		if err != nil || persisted.UploadedAt == nil || persisted.ObjectVersionID != uploaded.ObjectVersionID ||
			persisted.ETag != uploaded.ETag || persisted.ChecksumSHA256 != result.part.checksum ||
			uploaded.ChecksumSHA256 != result.part.checksum || persisted.ContentLength != int64(len(result.part.body)) {
			t.Fatalf("persisted variant mismatch: part=%#v response=%#v error=%v", persisted, uploaded, err)
		}
		completions[result.part.imageIndex] = append(completions[result.part.imageIndex], finalizeImageVariantRequest{
			Kind: result.part.kind, ObjectVersionID: uploaded.ObjectVersionID,
			ETag: uploaded.ETag, ChecksumSHA256: uploaded.ChecksumSHA256,
		})
	}
	for imageIndex, session := range sessions {
		body, err := json.Marshal(finalizeUploadRequest{Variants: completions[imageIndex]})
		if err != nil {
			t.Fatal(err)
		}
		status, response, err := parallelImageRequest(ctx, client, http.MethodPost,
			runtime.URL+"/api/media/v1/upload-sessions/"+session.UploadSessionID.String()+"/complete",
			"application/json", body)
		if err != nil || status != http.StatusAccepted {
			t.Fatalf("finalize image %d: status=%d body=%s error=%v", imageIndex, status, response, err)
		}
		asset, err := repository.UploadSessionForPrincipal(ctx, session.UploadSessionID, subjectID, persistence.PrincipalTypeUser)
		if err != nil || asset.ID != session.MediaID || asset.UploadCompletedAt == nil || asset.Status != media.StatusProcessing {
			t.Fatalf("image %d was not durably finalized: %#v error=%v", imageIndex, asset, err)
		}
	}
}

func parallelImageRequest(ctx context.Context, client *http.Client, method, url, contentType string, body []byte) (int, []byte, error) {
	request, err := http.NewRequestWithContext(ctx, method, url, bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	request.Header.Set("Authorization", "Bearer test")
	request.Header.Set("Idempotency-Key", uuid.NewString())
	request.Header.Set("Content-Type", contentType)
	response, err := client.Do(request)
	if err != nil {
		return 0, nil, err
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(response.Body)
	return response.StatusCode, responseBody, err
}

// parallelImageStore replaces only MinIO, retaining real HTTP streaming,
// checksum validation, database locking and durable variant completion.
type parallelImageStore struct {
	entered chan string
	release chan struct{}
	mutex   sync.Mutex
	objects map[string]media.ObjectMetadata
}

func (store *parallelImageStore) EnsureVersioning(context.Context) error { return nil }

func (store *parallelImageStore) PutIngressVersion(ctx context.Context, key string, source io.Reader,
	size int64, contentType, checksum string,
) (media.ObjectMetadata, error) {
	store.entered <- key
	select {
	case <-store.release:
	case <-ctx.Done():
		return media.ObjectMetadata{}, ctx.Err()
	}
	body, err := io.ReadAll(source)
	if err != nil {
		return media.ObjectMetadata{}, err
	}
	digest := sha256.Sum256(body)
	if int64(len(body)) != size || hex.EncodeToString(digest[:]) != checksum {
		return media.ObjectMetadata{}, fmt.Errorf("unexpected streamed bytes")
	}
	metadata := media.ObjectMetadata{
		VersionID: uuid.NewString(), ETag: checksum, SizeBytes: size, ContentType: contentType,
	}
	store.mutex.Lock()
	defer store.mutex.Unlock()
	if _, duplicate := store.objects[key]; duplicate {
		return media.ObjectMetadata{}, fmt.Errorf("duplicate object write")
	}
	store.objects[key] = metadata
	return metadata, nil
}

func (store *parallelImageStore) StatVersion(_ context.Context, key, version string) (media.ObjectMetadata, error) {
	store.mutex.Lock()
	defer store.mutex.Unlock()
	metadata, found := store.objects[key]
	if !found || metadata.VersionID != version {
		return media.ObjectMetadata{}, fmt.Errorf("unknown object version")
	}
	return metadata, nil
}

func (store *parallelImageStore) GetVersion(context.Context, string, string) (io.ReadCloser, media.ObjectMetadata, error) {
	return nil, media.ObjectMetadata{}, fmt.Errorf("unexpected image readback")
}
