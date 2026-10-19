package assetimport

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
)

func TestWorkerPreflightUsesFakeYandexWithoutDownloading(t *testing.T) {
	jobID, sourceRowID := uuid.New(), uuid.New()
	var downloadRequested bool
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "" {
			t.Fatal("preflight sent OAuth authorization")
		}
		switch request.URL.Path {
		case "/v1/disk/public/resources":
			_, _ = io.WriteString(response, `{"type":"file","name":"photo.webp","path":"disk:/photo.webp","mime_type":"image/webp","size":4}`)
		case "/v1/disk/public/resources/download":
			downloadRequested = true
			response.WriteHeader(http.StatusInternalServerError)
		default:
			response.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	repository := &assetImportRepositoryStub{work: Work{Job: Job{
		ID: jobID, Status: StatusPreflightRunning, Sources: []Source{{SourceRowID: sourceRowID, PublicKey: "AbCdEfGhIjKlMn"}},
	}, LeaseToken: uuid.New()}}
	worker := newTestWorker(t, repository, newTestYandexClient(t, server), &assetImportStoreStub{})

	if err := worker.runOnce(context.Background()); err != nil {
		t.Fatalf("runOnce() error = %v", err)
	}
	if downloadRequested || len(repository.preflightEntries) != 1 {
		t.Fatalf("preflight download=%v entries=%#v", downloadRequested, repository.preflightEntries)
	}
	entry := repository.preflightEntries[0]
	if entry.SourceRowID != sourceRowID || entry.ID == uuid.Nil || entry.Status != EntryPrepared || entry.ContentType != "image/webp" {
		t.Fatalf("preflight entry = %#v", entry)
	}
}

func TestWorkerPreflightEnforcesPerSourceAndGlobalEntryBounds(t *testing.T) {
	entriesAtSourceLimit := preflightFixtureEntries(MaxEntriesPerSource)

	t.Run("accepts the global entry limit", func(t *testing.T) {
		if MaxEntriesPerJob%MaxEntriesPerSource != 0 {
			t.Fatalf("global entry limit %d is not divisible by source limit %d", MaxEntriesPerJob, MaxEntriesPerSource)
		}
		repository := preflightRepository(MaxEntriesPerJob / MaxEntriesPerSource)
		worker := newTestWorker(t, repository, &preflightYandexStub{entries: entriesAtSourceLimit}, &assetImportStoreStub{})

		entries, err := worker.preflight(context.Background(), repository.work)
		if err != nil {
			t.Fatalf("preflight(global limit) error = %v", err)
		}
		if len(entries) != MaxEntriesPerJob {
			t.Fatalf("preflight entries = %d, want %d", len(entries), MaxEntriesPerJob)
		}
	})

	t.Run("rejects one entry over a source limit", func(t *testing.T) {
		repository := preflightRepository(1)
		worker := newTestWorker(t, repository,
			&preflightYandexStub{entries: preflightFixtureEntries(MaxEntriesPerSource + 1)}, &assetImportStoreStub{})

		if _, err := worker.preflight(context.Background(), repository.work); !errors.Is(err, ErrExternalRejected) {
			t.Fatalf("preflight(source limit) error = %v, want ErrExternalRejected", err)
		}
		if len(repository.preflightEntries) != 0 {
			t.Fatalf("source-limit preflight persisted %d entries", len(repository.preflightEntries))
		}
	})

	t.Run("rejects one source worth of entries over the global limit", func(t *testing.T) {
		repository := preflightRepository(MaxEntriesPerJob/MaxEntriesPerSource + 1)
		worker := newTestWorker(t, repository, &preflightYandexStub{entries: entriesAtSourceLimit}, &assetImportStoreStub{})

		if _, err := worker.preflight(context.Background(), repository.work); !errors.Is(err, ErrExternalRejected) {
			t.Fatalf("preflight(global limit) error = %v, want ErrExternalRejected", err)
		}
		if len(repository.preflightEntries) != 0 {
			t.Fatalf("global-limit preflight persisted %d entries", len(repository.preflightEntries))
		}
	})
}

func TestWorkerRenewsLeaseDuringBlockedPreflight(t *testing.T) {
	repository := preflightRepository(1)
	repository.renewed = make(chan struct{})
	yandex := &blockingPreflightYandex{started: make(chan struct{}), release: make(chan struct{}), canceled: make(chan struct{})}
	worker := newTestWorker(t, repository, yandex, &assetImportStoreStub{})
	ticks := make(chan time.Time)
	worker.leaseTicker = func(time.Duration) (<-chan time.Time, func()) { return ticks, func() {} }
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	done := make(chan error, 1)
	go func() { done <- worker.runOnce(ctx) }()
	select {
	case <-yandex.started:
	case <-ctx.Done():
		t.Fatal("preflight never reached enumeration")
	}
	select {
	case ticks <- time.Now():
	case <-ctx.Done():
		t.Fatal("preflight renewal did not start")
	}
	select {
	case <-repository.renewed:
	case <-ctx.Done():
		t.Fatal("preflight renewal did not complete")
	}
	close(yandex.release)
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("runOnce() error = %v", err)
		}
	case <-ctx.Done():
		t.Fatal("worker did not complete preflight")
	}
	if repository.renewCalls != 2 || len(repository.preflightEntries) != 1 || repository.requeueCalls != 0 {
		t.Fatalf("renewals=%d entries=%d requeues=%d", repository.renewCalls, len(repository.preflightEntries), repository.requeueCalls)
	}
}

func TestWorkerLeaseLossCancelsPreflightWithoutCompletionOrRequeue(t *testing.T) {
	repository := preflightRepository(1)
	repository.renewErrAt = 2
	yandex := &blockingPreflightYandex{started: make(chan struct{}), release: make(chan struct{}), canceled: make(chan struct{})}
	worker := newTestWorker(t, repository, yandex, &assetImportStoreStub{})
	ticks := make(chan time.Time)
	worker.leaseTicker = func(time.Duration) (<-chan time.Time, func()) { return ticks, func() {} }
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	done := make(chan error, 1)
	go func() { done <- worker.runOnce(ctx) }()
	select {
	case <-yandex.started:
	case <-ctx.Done():
		t.Fatal("preflight never reached enumeration")
	}
	select {
	case ticks <- time.Now():
	case <-ctx.Done():
		t.Fatal("preflight renewal did not start")
	}
	select {
	case <-yandex.canceled:
	case <-ctx.Done():
		t.Fatal("lease loss did not cancel enumeration")
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("runOnce() error = %v", err)
		}
	case <-ctx.Done():
		t.Fatal("worker did not stop after preflight lease loss")
	}
	if len(repository.preflightEntries) != 0 || repository.requeueCalls != 0 || repository.renewCalls != 2 {
		t.Fatalf("entries=%d requeues=%d renewals=%d", len(repository.preflightEntries), repository.requeueCalls, repository.renewCalls)
	}
}

func TestWorkerLeaseLossCancelsStalledActivationStoreWithoutCompletionOrRequeue(t *testing.T) {
	jobID, sourceRowID, entryID, cabinID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repository := &assetImportRepositoryStub{work: Work{Job: Job{
		ID: jobID, WarehouseID: uuid.New(), Status: StatusActivationRunning,
		Sources: []Source{{SourceRowID: sourceRowID, PublicKey: "AbCdEfGhIjKlMn", CabinID: &cabinID}},
		Entries: []Entry{{ID: entryID, SourceRowID: sourceRowID, ResourcePath: "disk:/photo.jpg", FileName: "photo.jpg", ContentType: "image/jpeg", Status: EntryPrepared}},
	}, LeaseToken: uuid.New()}, renewErrAt: 2}
	store := &blockingAssetImportStore{started: make(chan struct{}), canceled: make(chan struct{})}
	worker := newTestWorker(t, repository, &archiveYandexStub{archive: testZip(t, []testZipFile{{name: "folder/photo.jpg", body: []byte{0xff, 0xd8, 0xff, 0xd9}}})}, store)
	ticks := make(chan time.Time)
	worker.leaseTicker = func(time.Duration) (<-chan time.Time, func()) { return ticks, func() {} }
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	done := make(chan error, 1)
	go func() { done <- worker.runOnce(ctx) }()
	select {
	case <-store.started:
	case <-ctx.Done():
		t.Fatal("activation never reached object storage")
	}
	select {
	case ticks <- time.Now():
	case <-ctx.Done():
		t.Fatal("activation renewal did not start")
	}
	select {
	case <-store.canceled:
	case <-ctx.Done():
		t.Fatal("lease loss did not cancel object storage")
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("runOnce() error = %v", err)
		}
	case <-ctx.Done():
		t.Fatal("worker did not stop after activation lease loss")
	}
	if repository.activationCompleted || repository.requeueCalls != 0 || repository.renewCalls != 2 {
		t.Fatalf("completed=%v requeues=%d renewals=%d", repository.activationCompleted, repository.requeueCalls, repository.renewCalls)
	}
}

func TestWorkerActivationDownloadsArchiveUnpacksOriginalsUsesNormalIngressAndCleansTemporaryFiles(t *testing.T) {
	jobID, sourceRowID, firstEntryID, secondEntryID, cabinID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	var downloadRequested bool
	archivePayload := testZip(t, []testZipFile{
		{name: "200962/photo-a.jpg", body: []byte{0xff, 0xd8, 0xff, 0xd9}},
		{name: "200962/nested/photo-b.jpg", body: []byte{0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0xff, 0xd9}},
	})
	var server *httptest.Server
	server = httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "" {
			t.Fatal("activation sent OAuth authorization")
		}
		switch request.URL.Path {
		case "/v1/disk/public/resources/download":
			if request.URL.Query().Get("path") != "" {
				t.Fatalf("archive download unexpectedly requested one path: %q", request.URL.Query().Get("path"))
			}
			_, _ = io.WriteString(response, `{"href":"`+server.URL+`/private/fresh"}`)
		case "/private/fresh":
			downloadRequested = true
			response.Header().Set("Content-Type", "application/zip")
			_, _ = response.Write(archivePayload)
		default:
			response.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	warehouseID := uuid.New()
	repository := &assetImportRepositoryStub{work: Work{Job: Job{
		ID: jobID, WarehouseID: warehouseID, Status: StatusActivationRunning,
		Sources: []Source{{SourceRowID: sourceRowID, PublicKey: "AbCdEfGhIjKlMn", CabinID: &cabinID}},
		Entries: []Entry{
			{ID: firstEntryID, SourceRowID: sourceRowID, ResourcePath: "disk:/photo-a.jpg", FileName: "photo-a.jpg", ContentType: "image/jpeg", Status: EntryPrepared},
			{ID: secondEntryID, SourceRowID: sourceRowID, ResourcePath: "disk:/nested/photo-b.jpg", FileName: "photo-b.jpg", ContentType: "image/jpeg", Status: EntryPrepared},
		},
	}, LeaseToken: uuid.New()}}
	store := &assetImportStoreStub{}
	worker := newTestWorker(t, repository, newTestYandexClient(t, server), store)
	temporaryRoot := t.TempDir()
	worker.temporaryDir = temporaryRoot

	if err := worker.runOnce(context.Background()); err != nil {
		t.Fatalf("runOnce() error = %v", err)
	}
	if !downloadRequested || !repository.activationCompleted || len(repository.importCommands) != 2 || store.puts != 2 || len(store.bodies) != 2 {
		t.Fatalf("activation download=%v complete=%v commands=%#v store=%#v", downloadRequested, repository.activationCompleted, repository.importCommands, store)
	}
	for _, command := range repository.importCommands {
		if command.CabinID != cabinID || command.WarehouseID != warehouseID || command.ContentType != "image/jpeg" || command.MediaID == uuid.Nil {
			t.Fatalf("archive ingress command = %#v", command)
		}
	}
	if !bytes.Equal(store.bodies[0], []byte{0xff, 0xd8, 0xff, 0xd9}) ||
		!bytes.Equal(store.bodies[1], []byte{0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0xff, 0xd9}) {
		t.Fatalf("stored original bytes = %#v", store.bodies)
	}
	if len(repository.statuses) != 2 || repository.statuses[0].status != EntryDownloading || repository.statuses[1].status != EntryDownloading {
		t.Fatalf("entry state transitions = %#v", repository.statuses)
	}
	remaining, err := os.ReadDir(temporaryRoot)
	if err != nil || len(remaining) != 0 {
		t.Fatalf("temporary ZIP/original files remain: %#v, err=%v", remaining, err)
	}
}

func TestWorkerRequeuesTransientObjectStoreFailure(t *testing.T) {
	jobID, sourceRowID, entryID, cabinID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	archivePayload := testZip(t, []testZipFile{{name: "200962/photo.jpg", body: []byte{0xff, 0xd8, 0xff, 0xd9}}})
	var server *httptest.Server
	server = httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/v1/disk/public/resources/download":
			_, _ = io.WriteString(response, `{"href":"`+server.URL+`/private/fresh"}`)
		case "/private/fresh":
			response.Header().Set("Content-Type", "application/zip")
			_, _ = response.Write(archivePayload)
		default:
			response.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	warehouseID := uuid.New()
	repository := &assetImportRepositoryStub{work: Work{Job: Job{
		ID: jobID, WarehouseID: warehouseID, Status: StatusActivationRunning, ActivationAttempts: 1,
		Sources: []Source{{SourceRowID: sourceRowID, PublicKey: "AbCdEfGhIjKlMn", CabinID: &cabinID}},
		Entries: []Entry{{ID: entryID, SourceRowID: sourceRowID, ResourcePath: "disk:/photo.jpg", FileName: "photo.jpg", Status: EntryPrepared}},
	}, LeaseToken: uuid.New()}}
	worker := newTestWorker(t, repository, newTestYandexClient(t, server), &assetImportStoreStub{err: errors.New("MinIO unavailable")})
	temporaryRoot := t.TempDir()
	worker.temporaryDir = temporaryRoot

	if err := worker.runOnce(context.Background()); err != nil {
		t.Fatalf("runOnce() error = %v", err)
	}
	if repository.requeueCalls != 1 || repository.requeuePhase != PhaseActivation ||
		repository.requeueCode != "DEPENDENCY_UNAVAILABLE" {
		t.Fatalf("transient object-store requeue = calls:%d phase:%s code:%s", repository.requeueCalls, repository.requeuePhase, repository.requeueCode)
	}
	remaining, err := os.ReadDir(temporaryRoot)
	if err != nil || len(remaining) != 0 {
		t.Fatalf("temporary files remain after failed ingress: %#v, err=%v", remaining, err)
	}
}

// This is an opt-in smoke test for a real public Yandex.Disk folder. It is
// intentionally inert in normal CI; the caller supplies a short-lived/public
// key through RWMS_TEST_YANDEX_PUBLIC_KEY. The object store and repository are
// test doubles, so it proves the real external ZIP download and extraction
// without creating media records outside the explicit runtime test below.
func TestWorkerActivationDownloadsRealYandexArchiveWhenConfigured(t *testing.T) {
	publicKey := strings.TrimSpace(os.Getenv("RWMS_TEST_YANDEX_PUBLIC_KEY"))
	if publicKey == "" {
		t.Skip("RWMS_TEST_YANDEX_PUBLIC_KEY is not configured")
	}
	if !ValidYandexPublicKey(publicKey) {
		t.Fatal("RWMS_TEST_YANDEX_PUBLIC_KEY is not a valid public key")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	client, err := NewYandexClient()
	if err != nil {
		t.Fatalf("NewYandexClient() error = %v", err)
	}
	discovered, err := client.Enumerate(ctx, publicKey)
	if err != nil {
		t.Fatalf("Enumerate(real Yandex folder) error = %v", err)
	}
	jobID, sourceRowID, warehouseID, cabinID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	entries := make([]Entry, 0, len(discovered))
	for _, item := range discovered {
		if item.Status != EntryPrepared {
			continue
		}
		entries = append(entries, Entry{
			ID: uuid.New(), SourceRowID: sourceRowID, ResourcePath: item.ResourcePath, FileName: item.FileName,
			ContentType: item.ContentType, SizeBytes: item.SizeBytes, Status: EntryPrepared,
		})
	}
	if len(entries) == 0 {
		t.Fatal("real Yandex folder has no supported image originals")
	}
	repository := &assetImportRepositoryStub{work: Work{Job: Job{
		ID: jobID, WarehouseID: warehouseID, Status: StatusActivationRunning,
		Sources: []Source{{SourceRowID: sourceRowID, PublicKey: publicKey, CabinID: &cabinID}}, Entries: entries,
	}, LeaseToken: uuid.New()}}
	store := &assetImportStoreStub{}
	worker, err := NewWorker(repository, client, store, "real-yandex-worker-test", 20<<20, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("NewWorker() error = %v", err)
	}
	temporaryRoot := t.TempDir()
	worker.temporaryDir = temporaryRoot

	if err := worker.runOnce(ctx); err != nil {
		t.Fatalf("runOnce(real Yandex ZIP) error = %v", err)
	}
	if !repository.activationCompleted || len(repository.importCommands) != len(entries) || store.puts != len(entries) {
		t.Fatalf("real Yandex import completion=%v commands=%d entries=%d puts=%d", repository.activationCompleted, len(repository.importCommands), len(entries), store.puts)
	}
	remaining, err := os.ReadDir(temporaryRoot)
	if err != nil || len(remaining) != 0 {
		t.Fatalf("real Yandex temporary ZIP/original files remain: %#v, err=%v", remaining, err)
	}
}

func newTestWorker(t *testing.T, repository *assetImportRepositoryStub, yandex YandexPublicResources, store ObjectStore) *Worker {
	t.Helper()
	worker, err := NewWorker(repository, yandex, store, "test-import-worker", 1024, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("NewWorker() error = %v", err)
	}
	return worker
}

func preflightRepository(sourceCount int) *assetImportRepositoryStub {
	sources := make([]Source, 0, sourceCount)
	for range sourceCount {
		sources = append(sources, Source{SourceRowID: uuid.New(), PublicKey: "AbCdEfGhIjKlMn"})
	}
	return &assetImportRepositoryStub{work: Work{Job: Job{
		ID: uuid.New(), Status: StatusPreflightRunning, Sources: sources,
	}, LeaseToken: uuid.New()}}
}

func preflightFixtureEntries(count int) []DiscoveredEntry {
	entries := make([]DiscoveredEntry, 0, count)
	for index := range count {
		entries = append(entries, DiscoveredEntry{
			ResourcePath: fmt.Sprintf("disk:/fixture/photo-%03d.jpg", index),
			FileName:     fmt.Sprintf("photo-%03d.jpg", index),
			ContentType:  "image/jpeg",
			Status:       EntryPrepared,
		})
	}
	return entries
}

type preflightYandexStub struct {
	entries []DiscoveredEntry
}

type blockingPreflightYandex struct {
	started  chan struct{}
	release  chan struct{}
	canceled chan struct{}
	start    sync.Once
	cancel   sync.Once
}

type archiveYandexStub struct {
	archive []byte
}

func (*archiveYandexStub) Enumerate(context.Context, string) ([]DiscoveredEntry, error) {
	return nil, ErrExternalRejected
}

func (*archiveYandexStub) Download(context.Context, string, string) (io.ReadCloser, DownloadMetadata, error) {
	return nil, DownloadMetadata{}, ErrExternalRejected
}

func (stub *archiveYandexStub) DownloadArchive(context.Context, string) (io.ReadCloser, DownloadMetadata, error) {
	return io.NopCloser(bytes.NewReader(stub.archive)), DownloadMetadata{ContentType: "application/zip", ContentLength: int64(len(stub.archive))}, nil
}

func (stub *blockingPreflightYandex) Enumerate(ctx context.Context, _ string) ([]DiscoveredEntry, error) {
	stub.start.Do(func() { close(stub.started) })
	select {
	case <-stub.release:
		return []DiscoveredEntry{{ResourcePath: "disk:/fixture/photo.jpg", FileName: "photo.jpg", ContentType: "image/jpeg", Status: EntryPrepared}}, nil
	case <-ctx.Done():
		stub.cancel.Do(func() { close(stub.canceled) })
		return nil, ctx.Err()
	}
}

func (*blockingPreflightYandex) Download(context.Context, string, string) (io.ReadCloser, DownloadMetadata, error) {
	return nil, DownloadMetadata{}, ErrExternalRejected
}

func (*blockingPreflightYandex) DownloadArchive(context.Context, string) (io.ReadCloser, DownloadMetadata, error) {
	return nil, DownloadMetadata{}, ErrExternalRejected
}

func (stub *preflightYandexStub) Enumerate(context.Context, string) ([]DiscoveredEntry, error) {
	return append([]DiscoveredEntry(nil), stub.entries...), nil
}

func (*preflightYandexStub) Download(context.Context, string, string) (io.ReadCloser, DownloadMetadata, error) {
	return nil, DownloadMetadata{}, ErrExternalRejected
}

func (*preflightYandexStub) DownloadArchive(context.Context, string) (io.ReadCloser, DownloadMetadata, error) {
	return nil, DownloadMetadata{}, ErrExternalRejected
}

type assetImportRepositoryStub struct {
	work                Work
	claimed             bool
	preflightEntries    []DiscoveredEntry
	statuses            []assetImportEntryTransition
	importCommand       ImportAssetCommand
	importCommands      []ImportAssetCommand
	activationCompleted bool
	requeueCalls        int
	requeuePhase        Phase
	requeueCode         string
	renewCalls          int
	renewErrAt          int
	renewed             chan struct{}
	renewOnce           sync.Once
}

type assetImportEntryTransition struct {
	status EntryStatus
	code   string
}

func (stub *assetImportRepositoryStub) CreateAssetImport(context.Context, CreateCommand) (Job, bool, error) {
	return Job{}, false, ErrConflict
}

func (stub *assetImportRepositoryStub) GetAssetImport(context.Context, uuid.UUID) (Job, error) {
	return Job{}, ErrNotFound
}

func (stub *assetImportRepositoryStub) ActivateAssetImport(context.Context, ActivateCommand) (Job, bool, error) {
	return Job{}, false, ErrConflict
}

func (stub *assetImportRepositoryStub) RetryAssetImport(context.Context, RetryCommand) (Job, bool, error) {
	return Job{}, false, ErrConflict
}

func (stub *assetImportRepositoryStub) ReplaceAssetImportSources(context.Context, ReplaceSourcesCommand) (Job, bool, error) {
	return Job{}, false, ErrConflict
}

func (stub *assetImportRepositoryStub) ClaimAssetImport(context.Context, string, time.Duration) (Work, bool, error) {
	if stub.claimed {
		return Work{}, false, nil
	}
	stub.claimed = true
	return stub.work, true, nil
}

func (stub *assetImportRepositoryStub) RenewAssetImportLease(context.Context, uuid.UUID, uuid.UUID, string, time.Duration) error {
	stub.renewCalls++
	if stub.renewed != nil && stub.renewCalls == 2 {
		stub.renewOnce.Do(func() { close(stub.renewed) })
	}
	if stub.renewErrAt > 0 && stub.renewCalls >= stub.renewErrAt {
		return ErrLeaseLost
	}
	return nil
}

func (stub *assetImportRepositoryStub) CompleteAssetImportPreflight(_ context.Context, _ uuid.UUID, _ uuid.UUID, entries []DiscoveredEntry) error {
	stub.preflightEntries = append([]DiscoveredEntry(nil), entries...)
	return nil
}

func (stub *assetImportRepositoryStub) RequeueAssetImport(_ context.Context, _ uuid.UUID, _ uuid.UUID, phase Phase, code string, _ time.Time) error {
	stub.requeueCalls++
	stub.requeuePhase = phase
	stub.requeueCode = code
	return nil
}

func (stub *assetImportRepositoryStub) FailAssetImport(context.Context, uuid.UUID, uuid.UUID, Phase, string) error {
	return nil
}

func (stub *assetImportRepositoryStub) SetAssetImportEntryStatus(_ context.Context, _ uuid.UUID, _ uuid.UUID, _ uuid.UUID, status EntryStatus, code string) error {
	stub.statuses = append(stub.statuses, assetImportEntryTransition{status: status, code: code})
	return nil
}

func (stub *assetImportRepositoryStub) ImportAsset(_ context.Context, command ImportAssetCommand) (ImportAssetResult, error) {
	stub.importCommand = command
	stub.importCommands = append(stub.importCommands, command)
	return ImportAssetResult{MediaID: command.MediaID}, nil
}

func (stub *assetImportRepositoryStub) CompleteAssetImportActivation(context.Context, uuid.UUID, uuid.UUID) error {
	stub.activationCompleted = true
	return nil
}

type assetImportStoreStub struct {
	puts   int
	bytes  []byte
	bodies [][]byte
	err    error
}

type blockingAssetImportStore struct {
	started  chan struct{}
	canceled chan struct{}
	start    sync.Once
	cancel   sync.Once
}

func (stub *blockingAssetImportStore) PutIngressVersion(ctx context.Context, _ string, source io.Reader, _ int64, _ string, _ string) (media.ObjectMetadata, error) {
	if _, err := io.Copy(io.Discard, source); err != nil {
		return media.ObjectMetadata{}, err
	}
	stub.start.Do(func() { close(stub.started) })
	<-ctx.Done()
	stub.cancel.Do(func() { close(stub.canceled) })
	return media.ObjectMetadata{}, ctx.Err()
}

func (stub *assetImportStoreStub) PutIngressVersion(_ context.Context, _ string, source io.Reader, size int64, contentType, checksum string) (media.ObjectMetadata, error) {
	bytes, err := io.ReadAll(source)
	if err != nil {
		return media.ObjectMetadata{}, err
	}
	stub.puts++
	stub.bytes = bytes
	stub.bodies = append(stub.bodies, bytes)
	if int64(len(bytes)) != size || len(checksum) != 64 || !strings.HasPrefix(contentType, "image/") {
		return media.ObjectMetadata{}, ErrConflict
	}
	if stub.err != nil {
		return media.ObjectMetadata{}, stub.err
	}
	return media.ObjectMetadata{SizeBytes: size, ContentType: contentType, VersionID: "immutable-v1", ETag: "etag"}, nil
}

type testZipFile struct {
	name string
	body []byte
}

func testZip(t *testing.T, files []testZipFile) []byte {
	t.Helper()
	var archive bytes.Buffer
	writer := zip.NewWriter(&archive)
	for _, file := range files {
		entry, err := writer.Create(file.name)
		if err != nil {
			t.Fatalf("create ZIP member %q: %v", file.name, err)
		}
		if _, err := entry.Write(file.body); err != nil {
			t.Fatalf("write ZIP member %q: %v", file.name, err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close ZIP: %v", err)
	}
	return archive.Bytes()
}
