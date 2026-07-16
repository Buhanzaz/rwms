package media

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"math"

	"github.com/h2non/bimg"
)

const canonicalImageContentType = "image/jpeg"

// ObjectStore deliberately contains only operations required by processing.
// HTTP signing is kept at the API/storage boundary.
type ObjectStore interface {
	Get(context.Context, string) (io.ReadCloser, ObjectMetadata, error)
	Put(context.Context, string, io.Reader, int64, string) error
}

type ObjectMetadata struct {
	SizeBytes int64
}

type ImageProcessRequest struct {
	MediaID string
	// SourceObjectKey is always the immutable ingress object. Rotation is an
	// absolute orientation, so regenerating from a previous generation would
	// compound or fail to undo an earlier rotation.
	SourceObjectKey string
	Generation      int
	Rotation        Rotation
	Variants        VariantConfiguration
}

type ProcessedVariant struct {
	Variant        Variant
	ObjectKey      string
	ContentType    string
	SizeBytes      int64
	Width          int
	Height         int
	ChecksumSHA256 string
}

// generatedObject keeps transformation bytes inside the processing boundary.
// Metadata returned to PostgreSQL and API callers never carries media content.
type generatedObject struct {
	variant ProcessedVariant
	data    []byte
}

type ImageProcessResult struct {
	Original ProcessedVariant
	Variants []ProcessedVariant
}

type ImageProcessor struct {
	Store ObjectStore
}

func (processor ImageProcessor) Process(ctx context.Context, request ImageProcessRequest) (ImageProcessResult, error) {
	if processor.Store == nil {
		return ImageProcessResult{}, fmt.Errorf("image processor has no object store")
	}
	if request.MediaID == "" || request.SourceObjectKey == "" || request.Generation < 0 {
		return ImageProcessResult{}, fmt.Errorf("invalid image processing request")
	}

	if _, err := ParseRotation(int16(request.Rotation)); err != nil {
		return ImageProcessResult{}, err
	}

	source, err := processor.read(ctx, request.SourceObjectKey)
	if err != nil {
		return ImageProcessResult{}, fmt.Errorf("read image source: %w", err)
	}

	canonicalBytes, err := bimg.NewImage(source).Process(bimg.Options{
		Type:          bimg.JPEG,
		Quality:       92,
		StripMetadata: true,
		NoAutoRotate:  false,
		Rotate:        bimg.Angle(request.Rotation),
	})
	if err != nil {
		return ImageProcessResult{}, fmt.Errorf("build canonical original: %w", err)
	}
	canonicalSize, err := bimg.NewImage(canonicalBytes).Size()
	if err != nil {
		return ImageProcessResult{}, fmt.Errorf("read canonical image dimensions: %w", err)
	}

	original := newGeneratedObject(
		VariantOriginal,
		OriginalObjectKey(request.MediaID, request.Generation, ".jpg"),
		canonicalImageContentType,
		canonicalBytes,
		canonicalSize.Width,
		canonicalSize.Height,
	)
	if err := processor.write(ctx, original); err != nil {
		return ImageProcessResult{}, fmt.Errorf("write canonical original: %w", err)
	}

	result := ImageProcessResult{Original: original.variant}
	for _, specification := range request.Variants.ImageVariants() {
		variant, err := buildWebPVariant(request.MediaID, request.Generation, canonicalBytes, specification)
		if err != nil {
			return ImageProcessResult{}, err
		}
		if err := processor.write(ctx, variant); err != nil {
			return ImageProcessResult{}, fmt.Errorf("write %s variant: %w", variant.variant.Variant, err)
		}
		result.Variants = append(result.Variants, variant.variant)
	}
	return result, nil
}

func (processor ImageProcessor) read(ctx context.Context, key string) ([]byte, error) {
	object, _, err := processor.Store.Get(ctx, key)
	if err != nil {
		return nil, err
	}
	defer object.Close()
	return io.ReadAll(object)
}

func (processor ImageProcessor) write(ctx context.Context, object generatedObject) error {
	return processor.Store.Put(
		ctx,
		object.variant.ObjectKey,
		bytes.NewReader(object.data),
		object.variant.SizeBytes,
		object.variant.ContentType,
	)
}

func newGeneratedObject(variant Variant, objectKey, contentType string, data []byte, width, height int) generatedObject {
	checksum := sha256.Sum256(data)
	return generatedObject{
		variant: newProcessedVariant(
			variant,
			objectKey,
			contentType,
			int64(len(data)),
			width,
			height,
			hex.EncodeToString(checksum[:]),
		),
		data: data,
	}
}

func newProcessedVariant(
	variant Variant,
	objectKey, contentType string,
	sizeBytes int64,
	width, height int,
	checksumSHA256 string,
) ProcessedVariant {
	return ProcessedVariant{
		Variant:        variant,
		ObjectKey:      objectKey,
		ContentType:    contentType,
		SizeBytes:      sizeBytes,
		Width:          width,
		Height:         height,
		ChecksumSHA256: checksumSHA256,
	}
}

func buildWebPVariant(mediaID string, generation int, canonical []byte, specification VariantSpec) (generatedObject, error) {
	size, err := bimg.NewImage(canonical).Size()
	if err != nil {
		return generatedObject{}, fmt.Errorf("read %s source dimensions: %w", specification.Variant, err)
	}
	width, height := scaleToLongEdge(size.Width, size.Height, specification.LongEdge)
	data, err := bimg.NewImage(canonical).Process(bimg.Options{
		Type:          bimg.WEBP,
		Width:         width,
		Height:        height,
		Quality:       specification.Quality,
		StripMetadata: true,
		NoAutoRotate:  true,
		Enlarge:       false,
	})
	if err != nil {
		return generatedObject{}, fmt.Errorf("build %s WebP: %w", specification.Variant, err)
	}
	processedSize, err := bimg.NewImage(data).Size()
	if err != nil {
		return generatedObject{}, fmt.Errorf("read %s dimensions: %w", specification.Variant, err)
	}
	return newGeneratedObject(
		specification.Variant,
		ImageVariantObjectKey(mediaID, generation, specification.Variant),
		"image/webp",
		data,
		processedSize.Width,
		processedSize.Height,
	), nil
}

func scaleToLongEdge(width, height, longEdge int) (int, int) {
	if width <= 0 || height <= 0 || longEdge <= 0 {
		return width, height
	}
	currentLongEdge := maxInt(width, height)
	if currentLongEdge <= longEdge {
		return width, height
	}
	scale := float64(longEdge) / float64(currentLongEdge)
	targetWidth := maxInt(1, int(math.Round(float64(width)*scale)))
	targetHeight := maxInt(1, int(math.Round(float64(height)*scale)))
	return targetWidth, targetHeight
}

func maxInt(left, right int) int {
	if left > right {
		return left
	}
	return right
}
