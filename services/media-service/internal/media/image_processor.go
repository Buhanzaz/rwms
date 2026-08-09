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
	GetVersion(context.Context, string, string) (io.ReadCloser, ObjectMetadata, error)
	PutVersion(context.Context, string, io.Reader, int64, string) (ObjectMetadata, error)
}

// ObjectMetadata is the immutable storage metadata verified by media
// processing and persisted with a media generation.
type ObjectMetadata struct {
	SizeBytes    int64
	ContentType  string
	ETag         string
	VersionID    string
	UserMetadata map[string]string
}

// ImageProcessRequest names one immutable ingress object and the generation
// for which its canonical image and WebP variants must be built.
type ImageProcessRequest struct {
	MediaID string
	// SourceObjectKey is always the immutable ingress object.
	SourceObjectKey string
	SourceVersionID string
	Generation      int
	Variants        VariantConfiguration
}

// ProcessedVariant describes one version-pinned output object without carrying
// its media bytes.
type ProcessedVariant struct {
	Variant         Variant
	ObjectKey       string
	ContentType     string
	SizeBytes       int64
	Width           int
	Height          int
	ChecksumSHA256  string
	ObjectVersionID string
}

// generatedObject keeps transformation bytes inside the processing boundary.
// Metadata returned to PostgreSQL and API callers never carries media content.
type generatedObject struct {
	variant ProcessedVariant
	data    []byte
}

// ImageProcessResult contains the canonical original and all WebP derivatives
// created from one image ingress object.
type ImageProcessResult struct {
	Original ProcessedVariant
	Variants []ProcessedVariant
}

// ImageProcessor builds canonical JPEG originals and bounded WebP variants
// while preserving the pixels' supplied orientation.
type ImageProcessor struct {
	Store  ObjectStore
	Limits ProcessingLimits
}

// Process reads one pinned image ingress version, validates its bounds, and
// writes the canonical original plus SMALL, MEDIUM, and LARGE WebP variants.
func (processor ImageProcessor) Process(ctx context.Context, request ImageProcessRequest) (ImageProcessResult, error) {
	if processor.Store == nil {
		return ImageProcessResult{}, fmt.Errorf("image processor has no object store")
	}
	if !processor.Limits.Valid() {
		return ImageProcessResult{}, fmt.Errorf("image processor limits are required")
	}
	if request.MediaID == "" || request.SourceObjectKey == "" || request.SourceVersionID == "" || request.Generation <= 0 {
		return ImageProcessResult{}, fmt.Errorf("invalid image processing request")
	}

	source, err := processor.read(ctx, request.SourceObjectKey, request.SourceVersionID)
	if err != nil {
		return ImageProcessResult{}, fmt.Errorf("read image source: %w", err)
	}
	sourceSize, err := bimg.NewImage(source).Size()
	if err != nil {
		return ImageProcessResult{}, fmt.Errorf("read source image dimensions: %w", err)
	}
	if pixels := int64(sourceSize.Width) * int64(sourceSize.Height); pixels <= 0 || pixels > processor.Limits.MaxDecodedPixels {
		return ImageProcessResult{}, fmt.Errorf("decoded image exceeds pixel limit")
	}

	// Camera clients normalize the pixels before upload. Keep that uploaded
	// orientation exactly: the service must neither apply EXIF orientation nor
	// accept an explicit server-side rotation.
	canonicalBytes, err := bimg.NewImage(source).Process(bimg.Options{
		Type:          bimg.JPEG,
		Quality:       92,
		StripMetadata: true,
		NoAutoRotate:  true,
	})
	if err != nil {
		return ImageProcessResult{}, fmt.Errorf("build canonical original: %w", err)
	}
	if int64(len(canonicalBytes)) > processor.Limits.MaxImageOutputBytes {
		return ImageProcessResult{}, fmt.Errorf("canonical image exceeds output limit")
	}
	canonicalSize, err := bimg.NewImage(canonicalBytes).Size()
	if err != nil {
		return ImageProcessResult{}, fmt.Errorf("read canonical image dimensions: %w", err)
	}
	if pixels := int64(canonicalSize.Width) * int64(canonicalSize.Height); pixels <= 0 || pixels > processor.Limits.MaxDecodedPixels {
		return ImageProcessResult{}, fmt.Errorf("decoded image exceeds pixel limit")
	}

	original := newGeneratedObject(
		VariantOriginal,
		OriginalObjectKey(request.MediaID, request.Generation, ".jpg"),
		canonicalImageContentType,
		canonicalBytes,
		canonicalSize.Width,
		canonicalSize.Height,
	)
	if err := processor.write(ctx, &original); err != nil {
		return ImageProcessResult{}, fmt.Errorf("write canonical original: %w", err)
	}

	result := ImageProcessResult{Original: original.variant}
	for _, specification := range request.Variants.ImageVariants() {
		variant, err := buildWebPVariant(request.MediaID, request.Generation, canonicalBytes, specification)
		if err != nil {
			return ImageProcessResult{}, err
		}
		if variant.variant.SizeBytes > processor.Limits.MaxImageOutputBytes {
			return ImageProcessResult{}, fmt.Errorf("%s variant exceeds output limit", variant.variant.Variant)
		}
		if err := processor.write(ctx, &variant); err != nil {
			return ImageProcessResult{}, fmt.Errorf("write %s variant: %w", variant.variant.Variant, err)
		}
		result.Variants = append(result.Variants, variant.variant)
	}
	return result, nil
}

func (processor ImageProcessor) read(ctx context.Context, key, versionID string) ([]byte, error) {
	object, metadata, err := getObject(ctx, processor.Store, key, versionID)
	if err != nil {
		return nil, err
	}
	defer object.Close()
	limit := processor.Limits.MaxImageBytes
	if metadata.SizeBytes <= 0 || metadata.SizeBytes > limit {
		return nil, fmt.Errorf("image source exceeds byte limit")
	}
	data, err := io.ReadAll(io.LimitReader(object, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) != metadata.SizeBytes || int64(len(data)) > limit {
		return nil, fmt.Errorf("image source length changed while reading")
	}
	return data, nil
}

func getObject(ctx context.Context, store ObjectStore, key, versionID string) (io.ReadCloser, ObjectMetadata, error) {
	if versionID == "" {
		return nil, ObjectMetadata{}, fmt.Errorf("immutable object version is required")
	}
	return store.GetVersion(ctx, key, versionID)
}

func (processor ImageProcessor) write(ctx context.Context, object *generatedObject) error {
	metadata, err := putObject(ctx, processor.Store, object.variant.ObjectKey,
		bytes.NewReader(object.data), object.variant.SizeBytes, object.variant.ContentType)
	if err != nil {
		return err
	}
	object.variant.ObjectVersionID = metadata.VersionID
	return nil
}

func putObject(ctx context.Context, store ObjectStore, key string, source io.Reader, size int64, contentType string) (ObjectMetadata, error) {
	return store.PutVersion(ctx, key, source, size, contentType)
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
