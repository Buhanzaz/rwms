package worker

import (
	"context"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
)

func TestProcessorPromotesClientImageVariantsWithoutObjectStore(t *testing.T) {
	uploadedAt := time.Now()
	mediaID := uuid.New()
	job := persistence.WorkerJob{
		MediaID: mediaID, MediaKind: media.KindImage, ProcessingKind: media.ProcessingInitial,
		Generation: 1, Rotation: media.Rotation0, UploadMode: persistence.UploadModeImageVariants,
		SourceObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantLarge),
		SourceVersionID: "large-version", SourceChecksum: workerHex64('c'), SourceSizeBytes: 300,
		ContentType: "image/webp",
		ImageVariants: []persistence.UploadImageVariantPart{
			{UploadImageVariantExpectation: persistence.UploadImageVariantExpectation{
				Variant: media.VariantSmall, ContentLength: 100, ChecksumSHA256: workerHex64('a'),
				Width: 320, Height: 180, ObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantSmall),
			}, ObjectVersionID: "small-version", UploadedAt: &uploadedAt},
			{UploadImageVariantExpectation: persistence.UploadImageVariantExpectation{
				Variant: media.VariantMedium, ContentLength: 200, ChecksumSHA256: workerHex64('b'),
				Width: 640, Height: 360, ObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantMedium),
			}, ObjectVersionID: "medium-version", UploadedAt: &uploadedAt},
			{UploadImageVariantExpectation: persistence.UploadImageVariantExpectation{
				Variant: media.VariantLarge, ContentLength: 300, ChecksumSHA256: workerHex64('c'),
				Width: 1280, Height: 720, ObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantLarge),
			}, ObjectVersionID: "large-version", UploadedAt: &uploadedAt},
		},
	}

	variants, err := (Processor{}).Process(context.Background(), job)
	if err != nil || len(variants) != 4 {
		t.Fatalf("Process() = %#v, %v", variants, err)
	}
	if variants[0].Variant != media.VariantOriginal || variants[0].ObjectKey != job.SourceObjectKey ||
		variants[0].ObjectVersionID != job.SourceVersionID || variants[0].ContentType != "image/webp" ||
		variants[0].Width != 1280 || variants[0].Height != 720 {
		t.Fatalf("logical original = %#v", variants[0])
	}
}

func TestProcessorAliasesCompatibilityImageSourceWithoutDecoding(t *testing.T) {
	mediaID := uuid.New()
	job := persistence.WorkerJob{
		MediaID: mediaID, MediaKind: media.KindImage, ProcessingKind: media.ProcessingInitial,
		Generation: 1, Rotation: media.Rotation0, UploadMode: persistence.UploadModeSource,
		SourceObjectKey: media.IngressObjectKey(mediaID.String(), ".jpg"), SourceVersionID: "source-version",
		SourceChecksum: workerHex64('a'), SourceSizeBytes: 512, ContentType: "image/jpeg",
	}
	variants, err := (Processor{}).Process(context.Background(), job)
	if err != nil || len(variants) != 4 {
		t.Fatalf("Process() = %#v, %v", variants, err)
	}
	for _, variant := range variants {
		if variant.ObjectKey != job.SourceObjectKey || variant.ObjectVersionID != job.SourceVersionID ||
			variant.ContentType != job.ContentType || variant.SizeBytes != job.SourceSizeBytes ||
			variant.Width != 0 || variant.Height != 0 {
			t.Fatalf("compatibility alias = %#v", variant)
		}
	}
}

func workerHex64(character byte) string {
	value := make([]byte, 64)
	for index := range value {
		value[index] = character
	}
	return string(value)
}
