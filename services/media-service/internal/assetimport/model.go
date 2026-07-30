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
	AssetImportService = "asset-service"
	AssetImportScope   = "media.asset-import"

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
	MaxAttempts      = 3
)

type Status string

const (
	StatusPreflightPending  Status = "PREFLIGHT_PENDING"
	StatusPreflightRunning  Status = "PREFLIGHT_RUNNING"
	StatusPreflightReady    Status = "PREFLIGHT_READY"
	StatusActivationPending Status = "ACTIVATION_PENDING"
	StatusActivationRunning Status = "ACTIVATION_RUNNING"
	StatusCompleted         Status = "COMPLETED"
	StatusFailed            Status = "FAILED"
)

type Phase string

const (
	PhasePreflight  Phase = "PREFLIGHT"
	PhaseActivation Phase = "ACTIVATION"
)

type EntryStatus string

const (
	EntryPrepared    EntryStatus = "PREPARED"
	EntryDownloading EntryStatus = "DOWNLOADING"
	EntryImported    EntryStatus = "IMPORTED"
	EntrySkipped     EntryStatus = "SKIPPED"
	EntryFailed      EntryStatus = "FAILED"
)

var (
	ErrNotFound            = errors.New("asset import job not found")
	ErrConflict            = errors.New("asset import conflict")
	ErrIdempotencyMismatch = errors.New("asset import idempotency mismatch")
	ErrOwnerProofMissing   = errors.New("asset import owner proof missing")
	ErrOwnerMediaLimit     = errors.New("asset import owner media limit reached")
	ErrLeaseLost           = errors.New("asset import lease lost")
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

type CreateCommand struct {
	JobID          uuid.UUID
	AssetImportID  uuid.UUID
	WarehouseID    uuid.UUID
	IdempotencyKey uuid.UUID
	RequestSHA256  string
	Sources        []Source
}

type ActivationBinding struct {
	SourceRowID uuid.UUID
	CabinID     uuid.UUID
}

type ActivateCommand struct {
	JobID          uuid.UUID
	IdempotencyKey uuid.UUID
	RequestSHA256  string
	Bindings       []ActivationBinding
}

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

type Work struct {
	Job        Job
	LeaseToken uuid.UUID
}

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

type DownloadMetadata struct {
	ContentType   string
	ContentLength int64
}

type ObjectStore interface {
	PutIngressVersion(context.Context, string, io.Reader, int64, string, string) (media.ObjectMetadata, error)
}
