// Package media contains the immutable media lifecycle, transformation
// primitives, and object naming rules used by media-service.
package media

import (
	"fmt"
	"sort"
	"strings"
)

// Kind classifies uploaded content as an image or video.
type Kind string

const (
	// KindImage is still-image media with canonical and WebP derivatives.
	KindImage Kind = "IMAGE"
	// KindVideo is video media with an exact original and compressed playback.
	KindVideo Kind = "VIDEO"
)

// Status is the lifecycle state of one logical media asset.
type Status string

const (
	// StatusUploading awaits immutable source content finalization.
	StatusUploading Status = "UPLOADING"
	// StatusProcessing has a durable processing generation in flight.
	StatusProcessing Status = "PROCESSING"
	// StatusReady has a current immutable generation available for scoped reads.
	StatusReady Status = "READY"
	// StatusFailed has no usable current generation after processing failed.
	StatusFailed Status = "FAILED"
	// StatusDeleted is a logical deletion that retains object provenance.
	StatusDeleted Status = "DELETED"
)

// ProcessingKind classifies a durable transformation job.
type ProcessingKind string

const (
	// ProcessingInitial builds the first canonical generation for an upload.
	ProcessingInitial ProcessingKind = "INITIAL"
)

// Variant identifies an immutable original object or a presentation derivative.
type Variant string

const (
	// VariantSmall is the smallest presentation WebP derivative.
	VariantSmall Variant = "SMALL"
	// VariantMedium is the medium presentation WebP derivative.
	VariantMedium Variant = "MEDIUM"
	// VariantLarge is the largest presentation WebP derivative.
	VariantLarge Variant = "LARGE"
	// VariantPlayback is the compressed H.264/AAC MP4 video derivative.
	VariantPlayback Variant = "PLAYBACK"
	// VariantOriginal is the immutable canonical source-generation object.
	VariantOriginal Variant = "ORIGINAL"
)

// Rotation is retained only to read legacy media metadata. New processing is
// always physically oriented by the client and uses Rotation0.
type Rotation int16

// Rotation0 is the only supported orientation for new media processing.
const Rotation0 Rotation = 0

// KindForContentType maps an allowed image or video media type to its media
// kind after stripping parameters and normalizing case.
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

// Asset is the in-memory state machine for one logical media aggregate.
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

// ProcessingJob identifies the exact pending media generation to process.
type ProcessingJob struct {
	MediaID    string
	Generation int
	Rotation   Rotation
	Kind       ProcessingKind
}

// QueueInitialProcessing reserves the next immutable generation for an upload
// that has completed ingress.
func (asset *Asset) QueueInitialProcessing() (ProcessingJob, error) {
	if asset.Status != StatusUploading {
		return ProcessingJob{}, fmt.Errorf("media %s cannot begin processing from %s", asset.ID, asset.Status)
	}
	if asset.CurrentGeneration != 0 || asset.PendingGeneration != nil {
		return ProcessingJob{}, fmt.Errorf("media %s already has a processing generation", asset.ID)
	}
	return asset.queueProcessing(asset.allocateGeneration())
}

// CompleteProcessing promotes the matching pending generation to current READY
// state after all required objects were durably written.
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

// FailProcessing records the matching generation failure without reusing its
// object-key generation or invalidating a previously verified generation.
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
