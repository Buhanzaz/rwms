package assetimport

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
)

const (
	defaultPollInterval = time.Second
	defaultLease        = 90 * time.Second
	// A source archive can contain many individually admissible originals. The
	// cap guards the temporary ZIP from turning one public link into unbounded
	// disk use while remaining comfortably above the observed legacy folders.
	maxArchiveBytesPerSource int64 = 256 << 20
)

// Worker leases durable import jobs, preflights public Yandex.Disk resources,
// and ingests approved image files through the normal media pipeline.
type Worker struct {
	repository   Repository
	yandex       YandexPublicResources
	store        ObjectStore
	instanceID   string
	maxBytes     int64
	pollInterval time.Duration
	lease        time.Duration
	temporaryDir string
	logger       *slog.Logger
}

// NewWorker validates and creates the durable asset-import worker.
func NewWorker(repository Repository, yandex YandexPublicResources, store ObjectStore, instanceID string, maxBytes int64, logger *slog.Logger) (*Worker, error) {
	if repository == nil || yandex == nil || store == nil || strings.TrimSpace(instanceID) == "" || maxBytes <= 0 || logger == nil {
		return nil, fmt.Errorf("asset import worker dependencies are required")
	}
	return &Worker{
		repository: repository, yandex: yandex, store: store, instanceID: instanceID,
		maxBytes: maxBytes, pollInterval: defaultPollInterval, lease: defaultLease,
		temporaryDir: os.TempDir(), logger: logger,
	}, nil
}

// Run polls and processes asset-import jobs until ctx is canceled. Individual
// failures are recorded in durable state and do not stop the worker loop.
func (worker *Worker) Run(ctx context.Context) error {
	if worker == nil || worker.repository == nil || worker.yandex == nil || worker.store == nil || worker.pollInterval <= 0 || worker.lease <= 0 {
		return errors.New("asset import worker is not configured")
	}
	ticker := time.NewTicker(worker.pollInterval)
	defer ticker.Stop()
	for {
		if err := worker.runOnce(ctx); err != nil && !errors.Is(err, context.Canceled) {
			// Errors can contain a signed Yandex URL at lower layers. Keep the
			// operational record intentionally code-only.
			worker.logger.Warn("asset import worker iteration failed", "failureCode", failureCode(err))
		}
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
}

func (worker *Worker) runOnce(ctx context.Context) error {
	work, claimed, err := worker.repository.ClaimAssetImport(ctx, worker.instanceID, worker.lease)
	if err != nil || !claimed {
		return err
	}
	phase := phaseFor(work.Job.Status)
	if phase == "" {
		return nil
	}
	var runErr error
	switch phase {
	case PhasePreflight:
		runErr = worker.preflight(ctx, work)
	case PhaseActivation:
		runErr = worker.activate(ctx, work)
	}
	if runErr == nil {
		return nil
	}
	return worker.handleFailure(ctx, work, phase, runErr)
}

func (worker *Worker) preflight(ctx context.Context, work Work) error {
	if len(work.Job.Sources) < 1 || len(work.Job.Sources) > MaxSourcesPerJob {
		return ErrExternalRejected
	}
	entries := make([]DiscoveredEntry, 0)
	seen := make(map[string]struct{})
	for _, source := range work.Job.Sources {
		if err := worker.renew(ctx, work); err != nil {
			return err
		}
		discovered, err := worker.yandex.Enumerate(ctx, source.PublicKey)
		if err != nil {
			return err
		}
		if len(discovered) > MaxEntriesPerSource {
			return ErrExternalRejected
		}
		sourceEntries := 0
		for _, entry := range discovered {
			if entry.ResourcePath == "" || len(entry.ResourcePath) > 4096 || sourceEntries >= MaxEntriesPerSource ||
				len(entries) >= MaxEntriesPerJob {
				return ErrExternalRejected
			}
			key := source.SourceRowID.String() + "\x00" + entry.ResourcePath
			if _, duplicate := seen[key]; duplicate {
				continue
			}
			seen[key] = struct{}{}
			entry.ID = entryID(work.Job.ID, source.SourceRowID, entry.ResourcePath)
			entry.SourceRowID = source.SourceRowID
			if entry.SizeBytes != nil && *entry.SizeBytes <= 0 {
				entry.Status = EntrySkipped
				entry.WarningCode = "INVALID_FILE_SIZE"
			}
			entries = append(entries, entry)
			sourceEntries++
		}
	}
	return worker.repository.CompleteAssetImportPreflight(ctx, work.Job.ID, work.LeaseToken, entries)
}

func (worker *Worker) activate(ctx context.Context, work Work) error {
	sources := make(map[uuid.UUID]Source, len(work.Job.Sources))
	for _, source := range work.Job.Sources {
		sources[source.SourceRowID] = source
	}
	entriesBySource := make(map[uuid.UUID][]Entry, len(work.Job.Sources))
	for _, entry := range work.Job.Entries {
		if entry.Status != EntryPrepared && entry.Status != EntryDownloading {
			continue
		}
		source, found := sources[entry.SourceRowID]
		if !found || source.CabinID == nil || *source.CabinID == uuid.Nil {
			return ErrConflict
		}
		entriesBySource[entry.SourceRowID] = append(entriesBySource[entry.SourceRowID], entry)
	}
	for _, source := range work.Job.Sources {
		entries := entriesBySource[source.SourceRowID]
		if len(entries) == 0 {
			continue
		}
		ready := make([]Entry, 0, len(entries))
		for _, entry := range entries {
			if err := worker.renew(ctx, work); err != nil {
				return err
			}
			if entry.SizeBytes != nil && (*entry.SizeBytes <= 0 || *entry.SizeBytes > worker.maxBytes) {
				if err := worker.skip(ctx, work, entry.ID, "FILE_TOO_LARGE"); err != nil {
					return err
				}
				continue
			}
			if err := worker.repository.SetAssetImportEntryStatus(ctx, work.Job.ID, work.LeaseToken, entry.ID, EntryDownloading, ""); err != nil {
				return err
			}
			ready = append(ready, entry)
		}
		if len(ready) == 0 {
			continue
		}
		if err := worker.downloadArchiveAndIngest(ctx, work, source, ready); err != nil {
			return err
		}
	}
	return worker.repository.CompleteAssetImportActivation(ctx, work.Job.ID, work.LeaseToken)
}

// downloadArchiveAndIngest is deliberately source-scoped: one public Yandex
// folder belongs to one imported cabin. It downloads the "download all" ZIP,
// unpacks each expected original into a private temporary directory, then
// invokes the ordinary media ingress/repository path for that cabin. The
// directory is removed on every exit path, including a failed upload.
func (worker *Worker) downloadArchiveAndIngest(ctx context.Context, work Work, source Source, entries []Entry) error {
	temporaryDir, err := os.MkdirTemp(worker.temporaryDir, "rwms-media-import-*")
	if err != nil {
		return err
	}
	defer func() {
		if removeErr := os.RemoveAll(temporaryDir); removeErr != nil {
			worker.logger.Warn("asset import temporary files could not be removed", "failureCode", "TEMPORARY_CLEANUP_FAILED")
		}
	}()

	body, metadata, err := worker.yandex.DownloadArchive(ctx, source.PublicKey)
	if err != nil {
		return err
	}
	defer body.Close()
	archiveLimit := worker.archiveByteLimit(len(entries))
	if archiveLimit <= 0 || metadata.ContentLength > archiveLimit {
		return errArchiveTooLarge
	}
	archivePath := filepath.Join(temporaryDir, "source.zip")
	file, err := os.Create(archivePath)
	if err != nil {
		return err
	}
	written, copyErr := io.Copy(file, io.LimitReader(body, archiveLimit+1))
	closeErr := file.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if written <= 0 {
		return errInvalidArchive
	}
	if written > archiveLimit {
		return errArchiveTooLarge
	}

	archive, openErr := zip.OpenReader(archivePath)
	if openErr != nil {
		// A public Yandex link can legally point to exactly one original rather
		// than a folder. Keep that compatible without weakening the folder ZIP
		// path that the HTML import uses.
		if errors.Is(openErr, zip.ErrFormat) && len(entries) == 1 {
			if err := worker.ingestTemporaryOriginal(ctx, work, source, entries[0], archivePath); err != nil {
				return worker.handleEntryIngestError(ctx, work, entries[0], err)
			}
			return nil
		}
		return errInvalidArchive
	}
	defer archive.Close()
	files, err := archiveFilesForEntries(archive.File, entries)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		member, found := files[entry.ID]
		if !found {
			if err := worker.skip(ctx, work, entry.ID, failureCode(errArchiveEntryMissing)); err != nil {
				return err
			}
			continue
		}
		if err := worker.extractAndIngestArchiveEntry(ctx, work, source, entry, member, temporaryDir); err != nil {
			if err := worker.handleEntryIngestError(ctx, work, entry, err); err != nil {
				return err
			}
		}
	}
	return nil
}

func (worker *Worker) archiveByteLimit(entryCount int) int64 {
	if entryCount <= 0 || worker.maxBytes <= 0 {
		return 0
	}
	count := int64(entryCount)
	if count > math.MaxInt64/worker.maxBytes {
		return maxArchiveBytesPerSource
	}
	limit := count * worker.maxBytes
	// JPEG/PNG ZIPs are usually only marginally smaller than their originals;
	// reserve a little room for the central directory and folder entries.
	const archiveOverhead int64 = 1 << 20
	if limit <= maxArchiveBytesPerSource-archiveOverhead {
		limit += archiveOverhead
	}
	if limit > maxArchiveBytesPerSource {
		return maxArchiveBytesPerSource
	}
	return limit
}

func (worker *Worker) extractAndIngestArchiveEntry(ctx context.Context, work Work, source Source, entry Entry, member *zip.File, temporaryDir string) error {
	if member == nil || member.FileInfo().IsDir() || member.UncompressedSize64 == 0 || member.UncompressedSize64 > uint64(worker.maxBytes) {
		return errDownloadedTooLarge
	}
	input, err := member.Open()
	if err != nil {
		return err
	}
	defer input.Close()
	temporaryPath, err := worker.unpackOriginal(temporaryDir, input)
	if err != nil {
		return err
	}
	// Do not keep one unpacked original until the rest of the folder completes.
	// The parent directory defer remains a final safety net for interrupted work.
	defer func() {
		if removeErr := os.Remove(temporaryPath); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
			worker.logger.Warn("asset import temporary original could not be removed", "failureCode", "TEMPORARY_CLEANUP_FAILED")
		}
	}()
	return worker.ingestTemporaryOriginal(ctx, work, source, entry, temporaryPath)
}

func (worker *Worker) unpackOriginal(temporaryDir string, source io.Reader) (string, error) {
	file, err := os.CreateTemp(temporaryDir, "original-*")
	if err != nil {
		return "", err
	}
	temporaryPath := file.Name()
	written, copyErr := io.Copy(file, io.LimitReader(source, worker.maxBytes+1))
	closeErr := file.Close()
	if copyErr != nil {
		return "", copyErr
	}
	if closeErr != nil {
		return "", closeErr
	}
	if written <= 0 || written > worker.maxBytes {
		return "", errDownloadedTooLarge
	}
	return temporaryPath, nil
}

func (worker *Worker) ingestTemporaryOriginal(ctx context.Context, work Work, source Source, entry Entry, temporaryPath string) error {
	fileInfo, err := os.Stat(temporaryPath)
	if err != nil {
		return err
	}
	if fileInfo.Size() <= 0 || fileInfo.Size() > worker.maxBytes {
		return errDownloadedTooLarge
	}
	hash := sha256.New()
	prefixFile, err := os.Open(temporaryPath)
	if err != nil {
		return err
	}
	prefix := make([]byte, 512)
	read, readErr := io.ReadFull(prefixFile, prefix)
	if readErr != nil && !errors.Is(readErr, io.EOF) && !errors.Is(readErr, io.ErrUnexpectedEOF) {
		_ = prefixFile.Close()
		return readErr
	}
	prefix = prefix[:read]
	if _, err := prefixFile.Seek(0, io.SeekStart); err != nil {
		_ = prefixFile.Close()
		return err
	}
	if _, err := io.Copy(hash, prefixFile); err != nil {
		_ = prefixFile.Close()
		return err
	}
	if err := prefixFile.Close(); err != nil {
		return err
	}
	contentType, supported := supportedDownloadedImage(prefix)
	if !supported {
		return errUnsupportedImage
	}
	input, err := os.Open(temporaryPath)
	if err != nil {
		return err
	}
	defer input.Close()
	mediaID := importedMediaID(work.Job.ID, entry.ID)
	checksum := hex.EncodeToString(hash.Sum(nil))
	object, err := worker.store.PutIngressVersion(ctx, media.IngressObjectKey(mediaID.String(), extensionForContentType(contentType)), input,
		fileInfo.Size(), contentType, checksum)
	if err != nil {
		return err
	}
	if object.SizeBytes != fileInfo.Size() || object.VersionID == "" || strings.TrimSpace(object.ETag) == "" ||
		strings.ToLower(strings.TrimSpace(strings.Split(object.ContentType, ";")[0])) != contentType {
		return ErrConflict
	}
	if err := worker.renew(ctx, work); err != nil {
		return err
	}
	_, err = worker.repository.ImportAsset(ctx, ImportAssetCommand{
		JobID: work.Job.ID, LeaseToken: work.LeaseToken, EntryID: entry.ID, MediaID: mediaID,
		WarehouseID: work.Job.WarehouseID, CabinID: *source.CabinID, FileName: safeFileName(entry.FileName, contentType),
		ContentType: contentType, SizeBytes: fileInfo.Size(), ChecksumSHA256: checksum,
		ObjectKey:       media.IngressObjectKey(mediaID.String(), extensionForContentType(contentType)),
		ObjectVersionID: object.VersionID, ETag: strings.Trim(object.ETag, "\""), CorrelationID: uuid.New(),
	})
	return err
}

func (worker *Worker) handleEntryIngestError(ctx context.Context, work Work, entry Entry, err error) error {
	switch {
	case errors.Is(err, errUnsupportedImage), errors.Is(err, errDownloadedTooLarge), errors.Is(err, errArchiveEntryMissing):
		return worker.skip(ctx, work, entry.ID, failureCode(err))
	case errors.Is(err, ErrOwnerProofMissing), errors.Is(err, ErrOwnerMediaLimit), errors.Is(err, ErrConflict):
		return worker.repository.SetAssetImportEntryStatus(ctx, work.Job.ID, work.LeaseToken, entry.ID, EntryFailed, failureCode(err))
	default:
		return err
	}
}

// archiveFilesForEntries maps only preflight-approved resource paths to ZIP
// members. It never writes a ZIP member name to disk, which makes Zip Slip
// paths inert. Yandex's download-all ZIP adds one common top-level directory,
// so the exact path is tried first and then once with that directory removed.
func archiveFilesForEntries(members []*zip.File, entries []Entry) (map[uuid.UUID]*zip.File, error) {
	if len(members) == 0 || len(members) > MaxEntriesPerSource*4+1 {
		return nil, errInvalidArchive
	}
	byPath := make(map[string]*zip.File, len(members))
	for _, member := range members {
		if member == nil || member.FileInfo().IsDir() {
			continue
		}
		normalized, valid := normalizedArchivePath(member.Name)
		if !valid {
			continue
		}
		if _, duplicate := byPath[normalized]; duplicate {
			return nil, errInvalidArchive
		}
		byPath[normalized] = member
	}
	if len(byPath) == 0 {
		return nil, errInvalidArchive
	}
	commonRoot := commonArchiveRoot(byPath)
	result := make(map[uuid.UUID]*zip.File, len(entries))
	for _, entry := range entries {
		expected, valid := normalizedResourcePath(entry.ResourcePath)
		if !valid {
			return nil, errInvalidArchive
		}
		if member, found := byPath[expected]; found {
			result[entry.ID] = member
			continue
		}
		if commonRoot != "" {
			if member, found := byPath[commonRoot+"/"+expected]; found {
				result[entry.ID] = member
			}
		}
	}
	return result, nil
}

func normalizedResourcePath(resourcePath string) (string, bool) {
	resourcePath = strings.TrimSpace(resourcePath)
	resourcePath = strings.TrimPrefix(resourcePath, "disk:")
	resourcePath = strings.TrimPrefix(resourcePath, "/")
	return normalizedArchivePath(resourcePath)
}

func normalizedArchivePath(value string) (string, bool) {
	value = strings.TrimSpace(value)
	if value == "" || strings.Contains(value, "\\") || strings.ContainsRune(value, 0) || strings.HasPrefix(value, "/") {
		return "", false
	}
	cleaned := path.Clean(value)
	if cleaned == "." || cleaned == ".." || strings.HasPrefix(cleaned, "../") || strings.HasPrefix(cleaned, "/") {
		return "", false
	}
	return cleaned, true
}

func commonArchiveRoot(members map[string]*zip.File) string {
	var root string
	for memberPath := range members {
		candidate, remainder, found := strings.Cut(memberPath, "/")
		if !found || candidate == "" || remainder == "" {
			return ""
		}
		if root == "" {
			root = candidate
			continue
		}
		if root != candidate {
			return ""
		}
	}
	return root
}

func (worker *Worker) renew(ctx context.Context, work Work) error {
	return worker.repository.RenewAssetImportLease(ctx, work.Job.ID, work.LeaseToken, worker.instanceID, worker.lease)
}

func (worker *Worker) skip(ctx context.Context, work Work, entryID uuid.UUID, code string) error {
	return worker.repository.SetAssetImportEntryStatus(ctx, work.Job.ID, work.LeaseToken, entryID, EntrySkipped, code)
}

func (worker *Worker) handleFailure(ctx context.Context, work Work, phase Phase, err error) error {
	if errors.Is(err, ErrLeaseLost) {
		return nil
	}
	// Do not turn a graceful process shutdown into a durable terminal failure.
	// The lease will expire and a healthy worker can reclaim the job.
	if errors.Is(err, context.Canceled) {
		return err
	}
	attempts := work.Job.PreflightAttempts
	if phase == PhaseActivation {
		attempts = work.Job.ActivationAttempts
	}
	code := failureCode(err)
	if retryable(err) && attempts < MaxAttempts {
		delay := time.Duration(1<<max(attempts-1, 0)) * time.Second
		if retryErr := worker.repository.RequeueAssetImport(ctx, work.Job.ID, work.LeaseToken, phase, code, time.Now().UTC().Add(delay)); retryErr != nil {
			return retryErr
		}
		return nil
	}
	if failErr := worker.repository.FailAssetImport(ctx, work.Job.ID, work.LeaseToken, phase, code); failErr != nil {
		return failErr
	}
	return nil
}

func phaseFor(status Status) Phase {
	switch status {
	case StatusPreflightRunning:
		return PhasePreflight
	case StatusActivationRunning:
		return PhaseActivation
	default:
		return ""
	}
}

func retryable(err error) bool {
	// The dependency boundary intentionally returns only stable sentinel errors.
	// Anything else can be a transient PostgreSQL, MinIO, or network failure;
	// retry it under the durable job budget instead of terminally abandoning a
	// successfully downloaded object after an ambiguous response.
	switch {
	case errors.Is(err, context.Canceled), errors.Is(err, ErrLeaseLost),
		errors.Is(err, ErrExternalRejected), errors.Is(err, ErrUnsafeDownload),
		errors.Is(err, errUnsupportedImage), errors.Is(err, errDownloadedTooLarge),
		errors.Is(err, errArchiveTooLarge), errors.Is(err, errInvalidArchive),
		errors.Is(err, errArchiveEntryMissing),
		errors.Is(err, ErrOwnerProofMissing), errors.Is(err, ErrOwnerMediaLimit),
		errors.Is(err, ErrConflict):
		return false
	default:
		return true
	}
}

var (
	errUnsupportedImage    = errors.New("unsupported imported image")
	errDownloadedTooLarge  = errors.New("imported file exceeds size limit")
	errArchiveTooLarge     = errors.New("import archive exceeds size limit")
	errInvalidArchive      = errors.New("invalid import archive")
	errArchiveEntryMissing = errors.New("import archive entry missing")
)

func supportedDownloadedImage(prefix []byte) (string, bool) {
	sniffed := strings.ToLower(strings.TrimSpace(strings.Split(http.DetectContentType(prefix), ";")[0]))
	switch sniffed {
	case "image/jpeg", "image/png", "image/webp":
		return sniffed, true
	default:
		return "", false
	}
}

func failureCode(err error) string {
	switch {
	case errors.Is(err, ErrExternalUnavailable):
		return "YANDEX_UNAVAILABLE"
	case errors.Is(err, ErrExternalRejected):
		return "YANDEX_RESOURCE_REJECTED"
	case errors.Is(err, ErrUnsafeDownload):
		return "UNSAFE_DOWNLOAD_TARGET"
	case errors.Is(err, errUnsupportedImage):
		return "UNSUPPORTED_MEDIA"
	case errors.Is(err, errDownloadedTooLarge):
		return "FILE_TOO_LARGE"
	case errors.Is(err, errArchiveTooLarge):
		return "ARCHIVE_TOO_LARGE"
	case errors.Is(err, errInvalidArchive):
		return "INVALID_ARCHIVE"
	case errors.Is(err, errArchiveEntryMissing):
		return "ARCHIVE_ENTRY_MISSING"
	case errors.Is(err, ErrOwnerProofMissing):
		return "OWNER_PROOF_UNAVAILABLE"
	case errors.Is(err, ErrOwnerMediaLimit):
		return "OWNER_MEDIA_LIMIT"
	case errors.Is(err, ErrLeaseLost):
		return "LEASE_LOST"
	case errors.Is(err, ErrConflict):
		return "INGESTION_CONFLICT"
	default:
		return "DEPENDENCY_UNAVAILABLE"
	}
}
