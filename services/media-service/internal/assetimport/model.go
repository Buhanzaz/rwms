// Package assetimport implements the private, durable import of cabin photos
// from a public Yandex.Disk resource. It deliberately keeps public keys,
// resource paths, and download URLs inside the service boundary.
package assetimport

import (
	"context"
	"errors"
	"io"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
)

const (
	// AssetImportService is the only service identity authorized to call the
	// private Yandex.Disk import API.
	AssetImportService = "asset-service"
	// AssetImportScope is the sole scope accepted with AssetImportService's
	// service principal.
	AssetImportScope = "media.asset-import"

	// MaxSourcesPerJob bounds one durable import while accommodating the known
	// 415-row legacy Yandex source set.
	MaxSourcesPerJob = 500
	// MaxEntriesPerSource permits the observed multi-image public folders while
	// still rejecting a single unexpectedly broad source before activation.
	MaxEntriesPerSource = 100
	// MaxEntriesPerJob independently limits the whole durable job. It is lower
	// than MaxSourcesPerJob*MaxEntriesPerSource, so a large batch of full folders
	// cannot turn into an unbounded database transaction or activation run.
	MaxEntriesPerJob = 25_000
	// MaxAttempts bounds transient work retries before a job becomes terminal.
	MaxAttempts = 3
)

// Status identifies the durable lifecycle state of an asset-import job.
type Status string

const (
	// StatusPreflightPending waits for the worker to enumerate its sources.
	StatusPreflightPending Status = "PREFLIGHT_PENDING"
	// StatusPreflightRunning marks a job leased for source enumeration.
	StatusPreflightRunning Status = "PREFLIGHT_RUNNING"
	// StatusPreflightReady permits a caller to bind discovered sources to cabins.
	StatusPreflightReady Status = "PREFLIGHT_READY"
	// StatusActivationPending waits for the worker to download and ingest files.
	StatusActivationPending Status = "ACTIVATION_PENDING"
	// StatusActivationRunning marks a job leased for durable ingestion.
	StatusActivationRunning Status = "ACTIVATION_RUNNING"
	// StatusCompleted means every accepted source has reached a terminal outcome.
	StatusCompleted Status = "COMPLETED"
	// StatusFailed identifies a terminal preflight or activation failure.
	StatusFailed Status = "FAILED"
)

// Phase distinguishes durable preflight from activation work and failures.
type Phase string

const (
	// PhasePreflight enumerates remote public resources without downloading them.
	PhasePreflight Phase = "PREFLIGHT"
	// PhaseActivation downloads the approved entries into normal media ingress.
	PhaseActivation Phase = "ACTIVATION"
)

// EntryStatus is the per-file import outcome within one durable job.
type EntryStatus string

const (
	// EntryPrepared awaits the activation worker.
	EntryPrepared EntryStatus = "PREPARED"
	// EntryDownloading is leased by the worker while it reads an external file.
	EntryDownloading EntryStatus = "DOWNLOADING"
	// EntryImported has been durably accepted by normal media ingress.
	EntryImported EntryStatus = "IMPORTED"
	// EntrySkipped records a supported terminal warning without importing bytes.
	EntrySkipped EntryStatus = "SKIPPED"
	// EntryFailed records a terminal import failure for one discovered file.
	EntryFailed EntryStatus = "FAILED"
)

var (
	// ErrNotFound indicates that no durable asset-import job has the requested ID.
	ErrNotFound = errors.New("asset import job not found")
	// ErrConflict indicates invalid lifecycle state, request shape, or bounds.
	ErrConflict = errors.New("asset import conflict")
	// ErrIdempotencyMismatch rejects reuse of an idempotency key with new input.
	ErrIdempotencyMismatch = errors.New("asset import idempotency mismatch")
	// ErrOwnerProofMissing prevents import without a current CABIN owner proof.
	ErrOwnerProofMissing = errors.New("asset import owner proof missing")
	// ErrOwnerMediaLimit prevents a source from exceeding its owner's asset cap.
	ErrOwnerMediaLimit = errors.New("asset import owner media limit reached")
	// ErrLeaseLost indicates that another worker now owns the durable job lease.
	ErrLeaseLost = errors.New("asset import lease lost")
)

// Source retains only the parsed public key. PublicUrl is intentionally not a
// persisted model field, so it cannot be echoed from a job or accidentally
// emitted by a log formatter.
type Source struct {
	SourceRowID uuid.UUID
	PublicKey   string
	CabinID     *uuid.UUID
}

// Entry is private worker state. API adapters must expose only SafeEntry.
type Entry struct {
	ID           uuid.UUID
	SourceRowID  uuid.UUID
	ResourcePath string
	FileName     string
	ContentType  string
	SizeBytes    *int64
	Status       EntryStatus
	WarningCode  string
	MediaID      *uuid.UUID
}

// Job is the private durable state and source/entry inventory for one asset
// import. HTTP adapters must expose only its deliberately safe projection.
type Job struct {
	ID                 uuid.UUID
	AssetImportID      uuid.UUID
	WarehouseID        uuid.UUID
	Status             Status
	FailurePhase       Phase
	FailureCode        string
	PreflightAttempts  int
	ActivationAttempts int
	CreatedAt          time.Time
	UpdatedAt          time.Time
	Sources            []Source
	Entries            []Entry
}

// CreateCommand requests idempotent source preflight for one asset-service
// import request.
type CreateCommand struct {
	JobID          uuid.UUID
	AssetImportID  uuid.UUID
	WarehouseID    uuid.UUID
	IdempotencyKey uuid.UUID
	RequestSHA256  string
	Sources        []Source
}

// ActivationBinding assigns one preflight source row to its proven CABIN owner.
type ActivationBinding struct {
	SourceRowID uuid.UUID
	CabinID     uuid.UUID
}

// ActivateCommand starts idempotent import of a preflight job's cabin bindings.
type ActivateCommand struct {
	JobID          uuid.UUID
	IdempotencyKey uuid.UUID
	RequestSHA256  string
	Bindings       []ActivationBinding
}

// RetryCommand requests an idempotent retry of a terminal asset-import phase.
type RetryCommand struct {
	JobID          uuid.UUID
	IdempotencyKey uuid.UUID
	RequestSHA256  string
}

// SourceReplacement contains only the parsed public key. The public URL is
// accepted at the private HTTP boundary and must never be persisted or
// returned by this package.
type SourceReplacement struct {
	SourceRowID uuid.UUID
	PublicKey   string
}

// ReplaceSourcesCommand corrects one or more public keys after a terminal
// preflight rejection, then queues a fresh preflight for the same durable job.
type ReplaceSourcesCommand struct {
	JobID          uuid.UUID
	IdempotencyKey uuid.UUID
	RequestSHA256  string
	Replacements   []SourceReplacement
}

// DiscoveredEntry is sanitized remote-file metadata returned by preflight.
type DiscoveredEntry struct {
	ID           uuid.UUID
	SourceRowID  uuid.UUID
	ResourcePath string
	FileName     string
	ContentType  string
	SizeBytes    *int64
	Status       EntryStatus
	WarningCode  string
}

// Work pairs an owned durable job with the lease token required for mutations.
type Work struct {
	Job        Job
	LeaseToken uuid.UUID
}

// ImportAssetCommand records one externally downloaded file through the normal
// versioned media ingress and its owner-proof invariants.
type ImportAssetCommand struct {
	JobID           uuid.UUID
	LeaseToken      uuid.UUID
	EntryID         uuid.UUID
	MediaID         uuid.UUID
	WarehouseID     uuid.UUID
	CabinID         uuid.UUID
	FileName        string
	ContentType     string
	SizeBytes       int64
	ChecksumSHA256  string
	ObjectKey       string
	ObjectVersionID string
	ETag            string
	CorrelationID   uuid.UUID
}

// ImportAssetResult identifies the logical media asset created for one entry.
type ImportAssetResult struct {
	MediaID uuid.UUID
}

// Repository is intentionally a narrow private boundary. The worker never
// runs arbitrary SQL or knows the media aggregate schema.
type Repository interface {
	CreateAssetImport(context.Context, CreateCommand) (Job, bool, error)
	GetAssetImport(context.Context, uuid.UUID) (Job, error)
	ActivateAssetImport(context.Context, ActivateCommand) (Job, bool, error)
	RetryAssetImport(context.Context, RetryCommand) (Job, bool, error)
	ReplaceAssetImportSources(context.Context, ReplaceSourcesCommand) (Job, bool, error)
	ClaimAssetImport(context.Context, string, time.Duration) (Work, bool, error)
	RenewAssetImportLease(context.Context, uuid.UUID, uuid.UUID, string, time.Duration) error
	CompleteAssetImportPreflight(context.Context, uuid.UUID, uuid.UUID, []DiscoveredEntry) error
	RequeueAssetImport(context.Context, uuid.UUID, uuid.UUID, Phase, string, time.Time) error
	FailAssetImport(context.Context, uuid.UUID, uuid.UUID, Phase, string) error
	SetAssetImportEntryStatus(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, EntryStatus, string) error
	ImportAsset(context.Context, ImportAssetCommand) (ImportAssetResult, error)
	CompleteAssetImportActivation(context.Context, uuid.UUID, uuid.UUID) error
}

// YandexPublicResources is the only external protocol admitted by this
// feature. It is deliberately separate from the public import API.
type YandexPublicResources interface {
	Enumerate(context.Context, string) ([]DiscoveredEntry, error)
	Download(context.Context, string, string) (io.ReadCloser, DownloadMetadata, error)
	DownloadArchive(context.Context, string) (io.ReadCloser, DownloadMetadata, error)
}

// DownloadMetadata describes a streamed external payload without retaining its
// temporary URL.
type DownloadMetadata struct {
	ContentType   string
	ContentLength int64
}

// ObjectStore is the narrow versioned-ingress dependency needed by the import
// worker.
type ObjectStore interface {
	PutIngressVersion(context.Context, string, io.Reader, int64, string, string) (media.ObjectMetadata, error)
}
