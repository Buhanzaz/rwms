package assetimport

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/url"
	"sort"
	"strings"

	"github.com/google/uuid"
)

// Service provides the command-side boundary consumed by the private HTTP
// adapter. The durable worker is intentionally separate so a completed HTTP
// request never depends on a Yandex or object-store round trip.
type Service struct {
	repository Repository
}

// NewService creates the command-side import boundary over one durable
// repository.
func NewService(repository Repository) (*Service, error) {
	if repository == nil {
		return nil, fmt.Errorf("asset import repository is required")
	}
	return &Service{repository: repository}, nil
}

// Preflight validates, canonicalizes, and idempotently stores a remote-source
// enumeration request without fetching the remote bytes.
func (service *Service) Preflight(ctx context.Context, command CreateCommand) (Job, bool, error) {
	if err := validateCreateCommand(command); err != nil {
		return Job{}, false, err
	}
	command.Sources = canonicalSources(command.Sources)
	return service.repository.CreateAssetImport(ctx, command)
}

// Get returns the private durable state of one asset-import job.
func (service *Service) Get(ctx context.Context, jobID uuid.UUID) (Job, error) {
	if jobID == uuid.Nil {
		return Job{}, ErrNotFound
	}
	return service.repository.GetAssetImport(ctx, jobID)
}

// Activate validates CABIN bindings and idempotently queues their files for
// durable worker ingestion.
func (service *Service) Activate(ctx context.Context, command ActivateCommand) (Job, bool, error) {
	if err := validateActivateCommand(command); err != nil {
		return Job{}, false, err
	}
	command.Bindings = canonicalBindings(command.Bindings)
	return service.repository.ActivateAssetImport(ctx, command)
}

// Retry idempotently requeues a permitted terminal import phase.
func (service *Service) Retry(ctx context.Context, command RetryCommand) (Job, bool, error) {
	if command.JobID == uuid.Nil || command.IdempotencyKey == uuid.Nil || !validSHA256(command.RequestSHA256) {
		return Job{}, false, ErrConflict
	}
	return service.repository.RetryAssetImport(ctx, command)
}

// ReplacePreflightSources safely corrects selected source keys only while a
// preflight has failed. It intentionally cannot alter an active or completed
// ingestion job, where doing so could duplicate already accepted media.
func (service *Service) ReplacePreflightSources(ctx context.Context, command ReplaceSourcesCommand) (Job, bool, error) {
	if err := validateReplaceSourcesCommand(command); err != nil {
		return Job{}, false, err
	}
	command.Replacements = canonicalReplacements(command.Replacements)
	return service.repository.ReplaceAssetImportSources(ctx, command)
}

// ParsePublicURL accepts only the exact legacy share-link shape. The key is
// returned for private persistence; callers must never put the URL or key in
// a response or log record.
func ParsePublicURL(raw string) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed == nil || parsed.Scheme != "https" || parsed.Host != "disk.yandex.ru" ||
		parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" || parsed.Port() != "" {
		return "", ErrConflict
	}
	parts := strings.Split(parsed.EscapedPath(), "/")
	if len(parts) != 3 || parts[0] != "" || parts[1] != "d" || parts[2] == "" ||
		strings.Contains(parts[2], "%") {
		return "", ErrConflict
	}
	key := parts[2]
	if len(key) != 14 {
		return "", ErrConflict
	}
	for _, character := range key {
		if !((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
			(character >= '0' && character <= '9') || character == '-' || character == '_') {
			return "", ErrConflict
		}
	}
	return key, nil
}

// CanonicalPreflightSHA returns the stable request fingerprint for a source
// preflight command, independent of source ordering.
func CanonicalPreflightSHA(assetImportID, warehouseID uuid.UUID, sources []Source) string {
	canonical := canonicalSources(sources)
	value := make([]string, 0, len(canonical)+2)
	value = append(value, assetImportID.String(), warehouseID.String())
	for _, source := range canonical {
		value = append(value, source.SourceRowID.String()+":"+source.PublicKey)
	}
	return fingerprint(strings.Join(value, "\n"))
}

// CanonicalActivationSHA returns the stable request fingerprint for CABIN
// bindings, independent of their input ordering.
func CanonicalActivationSHA(jobID uuid.UUID, bindings []ActivationBinding) string {
	canonical := canonicalBindings(bindings)
	value := make([]string, 0, len(canonical)+1)
	value = append(value, jobID.String())
	for _, binding := range canonical {
		value = append(value, binding.SourceRowID.String()+":"+binding.CabinID.String())
	}
	return fingerprint(strings.Join(value, "\n"))
}

// CanonicalRetrySHA returns the stable request fingerprint for one retry key.
func CanonicalRetrySHA(jobID, key uuid.UUID) string {
	return fingerprint(jobID.String() + "\n" + key.String())
}

// CanonicalReplaceSourcesSHA returns the stable fingerprint for selected
// source-key replacements, independent of input ordering.
func CanonicalReplaceSourcesSHA(jobID uuid.UUID, replacements []SourceReplacement) string {
	canonical := canonicalReplacements(replacements)
	value := make([]string, 0, len(canonical)+1)
	value = append(value, jobID.String())
	for _, replacement := range canonical {
		value = append(value, replacement.SourceRowID.String()+":"+replacement.PublicKey)
	}
	return fingerprint(strings.Join(value, "\n"))
}

func fingerprint(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func validateCreateCommand(command CreateCommand) error {
	if command.JobID == uuid.Nil || command.AssetImportID == uuid.Nil || command.WarehouseID == uuid.Nil ||
		command.IdempotencyKey == uuid.Nil || !validSHA256(command.RequestSHA256) ||
		len(command.Sources) < 1 || len(command.Sources) > MaxSourcesPerJob {
		return ErrConflict
	}
	seen := make(map[uuid.UUID]struct{}, len(command.Sources))
	for _, source := range command.Sources {
		if source.SourceRowID == uuid.Nil || source.PublicKey == "" {
			return ErrConflict
		}
		if _, duplicate := seen[source.SourceRowID]; duplicate {
			return ErrConflict
		}
		seen[source.SourceRowID] = struct{}{}
		if len(source.PublicKey) != 14 {
			return ErrConflict
		}
	}
	return nil
}

func validateActivateCommand(command ActivateCommand) error {
	if command.JobID == uuid.Nil || command.IdempotencyKey == uuid.Nil || !validSHA256(command.RequestSHA256) ||
		len(command.Bindings) < 1 || len(command.Bindings) > MaxSourcesPerJob {
		return ErrConflict
	}
	seen := make(map[uuid.UUID]struct{}, len(command.Bindings))
	for _, binding := range command.Bindings {
		if binding.SourceRowID == uuid.Nil || binding.CabinID == uuid.Nil {
			return ErrConflict
		}
		if _, duplicate := seen[binding.SourceRowID]; duplicate {
			return ErrConflict
		}
		seen[binding.SourceRowID] = struct{}{}
	}
	return nil
}

func validateReplaceSourcesCommand(command ReplaceSourcesCommand) error {
	if command.JobID == uuid.Nil || command.IdempotencyKey == uuid.Nil || !validSHA256(command.RequestSHA256) ||
		len(command.Replacements) < 1 || len(command.Replacements) > MaxSourcesPerJob {
		return ErrConflict
	}
	seen := make(map[uuid.UUID]struct{}, len(command.Replacements))
	for _, replacement := range command.Replacements {
		if replacement.SourceRowID == uuid.Nil || !ValidYandexPublicKey(replacement.PublicKey) {
			return ErrConflict
		}
		if _, duplicate := seen[replacement.SourceRowID]; duplicate {
			return ErrConflict
		}
		seen[replacement.SourceRowID] = struct{}{}
	}
	return nil
}

func canonicalSources(sources []Source) []Source {
	result := append([]Source(nil), sources...)
	sort.Slice(result, func(left, right int) bool {
		return result[left].SourceRowID.String() < result[right].SourceRowID.String()
	})
	return result
}

func canonicalBindings(bindings []ActivationBinding) []ActivationBinding {
	result := append([]ActivationBinding(nil), bindings...)
	sort.Slice(result, func(left, right int) bool {
		return result[left].SourceRowID.String() < result[right].SourceRowID.String()
	})
	return result
}

func canonicalReplacements(replacements []SourceReplacement) []SourceReplacement {
	result := append([]SourceReplacement(nil), replacements...)
	sort.Slice(result, func(left, right int) bool {
		return result[left].SourceRowID.String() < result[right].SourceRowID.String()
	})
	return result
}

// ValidYandexPublicKey reports whether value is a safe 14-character public
// Yandex.Disk key accepted for private persistence.
func ValidYandexPublicKey(value string) bool {
	if len(value) != 14 {
		return false
	}
	for _, character := range value {
		if !((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
			(character >= '0' && character <= '9') || character == '-' || character == '_') {
			return false
		}
	}
	return true
}

func validSHA256(value string) bool {
	if len(value) != sha256.Size*2 {
		return false
	}
	decoded, err := hex.DecodeString(value)
	if err != nil || len(decoded) != sha256.Size {
		return false
	}
	return value == strings.ToLower(value)
}
