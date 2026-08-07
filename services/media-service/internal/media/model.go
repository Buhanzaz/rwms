package media

import (
	"fmt"
	"sort"
	"strings"
)

type Kind string

const (
	KindImage Kind = "IMAGE"
	KindVideo Kind = "VIDEO"
)

type Status string

const (
	StatusUploading  Status = "UPLOADING"
	StatusProcessing Status = "PROCESSING"
	StatusReady      Status = "READY"
	StatusFailed     Status = "FAILED"
	StatusDeleted    Status = "DELETED"
)

type ProcessingKind string

const (
	ProcessingInitial ProcessingKind = "INITIAL"
)

type Variant string

const (
	VariantSmall    Variant = "SMALL"
	VariantMedium   Variant = "MEDIUM"
	VariantLarge    Variant = "LARGE"
	VariantOriginal Variant = "ORIGINAL"
)

// Rotation is retained only to read legacy media metadata. New processing is
// always physically oriented by the client and uses Rotation0.
type Rotation int16

const Rotation0 Rotation = 0

func KindForContentType(contentType string) (Kind, bool) {
	normalized := strings.ToLower(strings.TrimSpace(strings.Split(contentType, ";")[0]))
	switch {
	case strings.HasPrefix(normalized, "image/"):
		return KindImage, true
	case strings.HasPrefix(normalized, "video/"):
		return KindVideo, true
	default:
		return "", false
	}
}

type Asset struct {
	ID                string
	OwnerType         string
	OwnerID           string
	WarehouseID       string
	Kind              Kind
	Status            Status
	SortOrder         int64
	Version           int64
	Rotation          Rotation
	CurrentGeneration int
	// NextGeneration is monotonically allocated before processing begins. It is
	// never decremented after a failed attempt, preventing a later retry from
	// reusing object keys written by the failed worker.
	NextGeneration    int
	PendingGeneration *int
	PendingRotation   *Rotation
	ProcessingError   string
	CreatedAtUnix     int64
}

type ProcessingJob struct {
	MediaID    string
	Generation int
	Rotation   Rotation
	Kind       ProcessingKind
}

func (asset *Asset) QueueInitialProcessing() (ProcessingJob, error) {
	if asset.Status != StatusUploading {
		return ProcessingJob{}, fmt.Errorf("media %s cannot begin processing from %s", asset.ID, asset.Status)
	}
	if asset.CurrentGeneration != 0 || asset.PendingGeneration != nil {
		return ProcessingJob{}, fmt.Errorf("media %s already has a processing generation", asset.ID)
	}
	return asset.queueProcessing(asset.allocateGeneration())
}

func (asset *Asset) CompleteProcessing(job ProcessingJob) error {
	if err := asset.validatePendingJob(job); err != nil {
		return err
	}
	asset.CurrentGeneration = job.Generation
	asset.Rotation = job.Rotation
	asset.PendingGeneration = nil
	asset.PendingRotation = nil
	asset.ProcessingError = ""
	asset.Status = StatusReady
	asset.Version++
	return nil
}

func (asset *Asset) FailProcessing(job ProcessingJob, reason string) error {
	if err := asset.validatePendingJob(job); err != nil {
		return err
	}
	asset.PendingGeneration = nil
	asset.PendingRotation = nil
	asset.ProcessingError = strings.TrimSpace(reason)
	if asset.CurrentGeneration == 0 {
		asset.Status = StatusFailed
	} else {
		// A failed reprocessing attempt keeps the previously verified generation current.
		asset.Status = StatusReady
	}
	asset.Version++
	return nil
}

func (asset *Asset) queueProcessing(generation int) (ProcessingJob, error) {
	rotation := Rotation0
	asset.Status = StatusProcessing
	asset.PendingGeneration = &generation
	asset.PendingRotation = &rotation
	asset.ProcessingError = ""
	asset.Version++
	return ProcessingJob{
		MediaID:    asset.ID,
		Generation: generation,
		Rotation:   rotation,
		Kind:       ProcessingInitial,
	}, nil
}

func (asset *Asset) allocateGeneration() int {
	next := asset.NextGeneration
	if next <= asset.CurrentGeneration {
		next = asset.CurrentGeneration + 1
	}
	if next <= 0 {
		next = 1
	}
	asset.NextGeneration = next + 1
	return next
}

func (asset *Asset) validatePendingJob(job ProcessingJob) error {
	if job.Kind != ProcessingInitial || job.Rotation != Rotation0 {
		return fmt.Errorf("media %s has an unsupported processing job", asset.ID)
	}
	if asset.Status != StatusProcessing || asset.PendingGeneration == nil || asset.PendingRotation == nil {
		return fmt.Errorf("media %s has no pending processing job", asset.ID)
	}
	if job.MediaID != asset.ID || job.Generation != *asset.PendingGeneration || job.Rotation != *asset.PendingRotation {
		return fmt.Errorf("processing job does not match pending generation for media %s", asset.ID)
	}
	return nil
}

// SortForPresentation guarantees the product order: all photographs first,
// then all videos. Stable ordering preserves each owner's explicit sort order.
func SortForPresentation(assets []Asset) {
	sort.SliceStable(assets, func(left, right int) bool {
		leftVideo := assets[left].Kind == KindVideo
		rightVideo := assets[right].Kind == KindVideo
		if leftVideo != rightVideo {
			return !leftVideo
		}
		if assets[left].SortOrder != assets[right].SortOrder {
			return assets[left].SortOrder < assets[right].SortOrder
		}
		return assets[left].CreatedAtUnix < assets[right].CreatedAtUnix
	})
}

type VariantSpec struct {
	Variant  Variant
	LongEdge int
	Quality  int
}

type VariantConfiguration struct {
	SmallLongEdge  int
	MediumLongEdge int
	LargeLongEdge  int
}

func (configuration VariantConfiguration) ImageVariants() []VariantSpec {
	return []VariantSpec{
		{Variant: VariantSmall, LongEdge: positiveOr(configuration.SmallLongEdge, 480), Quality: 78},
		{Variant: VariantMedium, LongEdge: positiveOr(configuration.MediumLongEdge, 960), Quality: 80},
		{Variant: VariantLarge, LongEdge: positiveOr(configuration.LargeLongEdge, 1920), Quality: 82},
	}
}

func positiveOr(value, fallback int) int {
	if value > 0 {
		return value
	}
	return fallback
}
