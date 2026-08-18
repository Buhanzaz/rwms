package persistence

import (
	"testing"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
)

func TestValidateProcessedVariantsRequiresExactGenerationSet(t *testing.T) {
	mediaID := uuid.New()
	job := WorkerJob{
		MediaID: mediaID, MediaKind: media.KindImage, Generation: 2, ContentType: "image/jpeg",
		ProcessingKind: media.ProcessingInitial, Rotation: media.Rotation0,
	}
	valid := processedImageVariants(mediaID, 2)
	if err := validateProcessedVariants(job, valid); err != nil {
		t.Fatalf("valid exact image set error = %v", err)
	}

	tests := []struct {
		name   string
		mutate func([]media.ProcessedVariant) []media.ProcessedVariant
	}{
		{name: "missing", mutate: func(variants []media.ProcessedVariant) []media.ProcessedVariant { return variants[:3] }},
		{name: "extra", mutate: func(variants []media.ProcessedVariant) []media.ProcessedVariant { return append(variants, variants[0]) }},
		{name: "wrong generation key", mutate: func(variants []media.ProcessedVariant) []media.ProcessedVariant {
			variants[0].ObjectKey = media.OriginalObjectKey(mediaID.String(), 1, ".jpg")
			return variants
		}},
		{name: "blank object version", mutate: func(variants []media.ProcessedVariant) []media.ProcessedVariant {
			variants[1].ObjectVersionID = ""
			return variants
		}},
		{name: "wrong content type", mutate: func(variants []media.ProcessedVariant) []media.ProcessedVariant {
			variants[2].ContentType = "image/jpeg"
			return variants
		}},
		{name: "missing dimensions", mutate: func(variants []media.ProcessedVariant) []media.ProcessedVariant {
			variants[3].Width = 0
			return variants
		}},
	}
	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			copyOfValid := append([]media.ProcessedVariant(nil), valid...)
			if err := validateProcessedVariants(job, testCase.mutate(copyOfValid)); err == nil {
				t.Fatal("validateProcessedVariants() error = nil, want exact-set rejection")
			}
		})
	}
}

func TestValidateProcessedVariantsRequiresExactVideoOriginalAndPlayback(t *testing.T) {
	mediaID := uuid.New()
	job := WorkerJob{
		MediaID: mediaID, MediaKind: media.KindVideo, Generation: 3, ContentType: "video/mp4",
		ProcessingKind: media.ProcessingInitial, Rotation: media.Rotation0,
	}
	valid := []media.ProcessedVariant{
		{
			Variant: media.VariantOriginal, ObjectKey: media.OriginalObjectKey(mediaID.String(), 3, ".mp4"),
			ObjectVersionID: "minio-video-version", ContentType: "video/mp4", SizeBytes: 128,
			ChecksumSHA256: hex64('a'),
		},
		{
			Variant: media.VariantPlayback, ObjectKey: media.VideoPlaybackObjectKey(mediaID.String(), 3),
			ObjectVersionID: "minio-playback-version", ContentType: "video/mp4", SizeBytes: 64,
			ChecksumSHA256: hex64('b'),
		},
	}
	if err := validateProcessedVariants(job, valid); err != nil {
		t.Fatalf("valid exact video set error = %v", err)
	}
	invalid := append([]media.ProcessedVariant(nil), valid...)
	invalid[0].Width = 10
	if err := validateProcessedVariants(job, invalid); err == nil {
		t.Fatal("video dimensions must remain unproved/zero")
	}
	if err := validateProcessedVariants(job, valid[:1]); err == nil {
		t.Fatal("video generation without PLAYBACK must be rejected")
	}
	invalid = append([]media.ProcessedVariant(nil), valid...)
	invalid[1].ContentType = "video/webm"
	if err := validateProcessedVariants(job, invalid); err == nil {
		t.Fatal("PLAYBACK must be canonical video/mp4")
	}
}
