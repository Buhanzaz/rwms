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
	ProcessingInitial  ProcessingKind = "INITIAL"
	ProcessingRotation ProcessingKind = "ROTATION"
)

type Variant string

const (
	VariantSmall    Variant = "SMALL"
	VariantMedium   Variant = "MEDIUM"
	VariantLarge    Variant = "LARGE"
	VariantOriginal Variant = "ORIGINAL"
)

type Rotation int16

const (
	Rotation0   Rotation = 0
	Rotation90  Rotation = 90
	Rotation180 Rotation = 180
	Rotation270 Rotation = 270
)

func ParseRotation(value int16) (Rotation, error) {
	rotation := Rotation(value)
	switch rotation {
	case Rotation0, Rotation90, Rotation180, Rotation270:
		return rotation, nil
	default:
		return 0, fmt.Errorf("unsupported media rotation %d", value)
	}
}

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
	return asset.queueProcessing(ProcessingInitial, 1, Rotation0)
}

func (asset *Asset) QueueRotation(expectedVersion int64, rotation Rotation) (ProcessingJob, error) {
	if expectedVersion != asset.Version {
		return ProcessingJob{}, fmt.Errorf("media %s version conflict: expected %d, actual %d", asset.ID, expectedVersion, asset.Version)
	}
	if asset.Status != StatusReady {
		return ProcessingJob{}, fmt.Errorf("media %s cannot rotate from %s", asset.ID, asset.Status)
	}
	if asset.CurrentGeneration <= 0 {
		return ProcessingJob{}, fmt.Errorf("media %s has no current generation", asset.ID)
	}
	if _, err := ParseRotation(int16(rotation)); err != nil {
		return ProcessingJob{}, err
	}
	if rotation == asset.Rotation {
		return ProcessingJob{}, fmt.Errorf("media %s is already rotated to %d degrees", asset.ID, rotation)
	}
	return asset.queueProcessing(ProcessingRotation, asset.CurrentGeneration+1, rotation)
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
		// A failed rotation must keep the previously verified generation current.
		asset.Status = StatusReady
	}
	asset.Version++
	return nil
}

func (asset *Asset) queueProcessing(kind ProcessingKind, generation int, rotation Rotation) (ProcessingJob, error) {
	if _, err := ParseRotation(int16(rotation)); err != nil {
		return ProcessingJob{}, err
	}
	asset.Status = StatusProcessing
	asset.PendingGeneration = &generation
	asset.PendingRotation = &rotation
	asset.ProcessingError = ""
	asset.Version++
	return ProcessingJob{
		MediaID:    asset.ID,
		Generation: generation,
		Rotation:   rotation,
		Kind:       kind,
	}, nil
}

func (asset *Asset) validatePendingJob(job ProcessingJob) error {
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
		{Variant: VariantSmall, LongEdge: positiveOr(configuration.SmallLongEdge, 96), Quality: 78},
		{Variant: VariantMedium, LongEdge: positiveOr(configuration.MediumLongEdge, 320), Quality: 80},
		{Variant: VariantLarge, LongEdge: positiveOr(configuration.LargeLongEdge, 1280), Quality: 82},
	}
}

func positiveOr(value, fallback int) int {
	if value > 0 {
		return value
	}
	return fallback
}
