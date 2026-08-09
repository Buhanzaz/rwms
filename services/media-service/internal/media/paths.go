package media

import (
	"fmt"
	"strings"
)

// IngressObjectKey returns the opaque key for bytes entering the media upload pipeline.
// Object keys use opaque media IDs. Owner identifiers never enter a MinIO key,
// preventing client-visible object names from leaking cabin or estimate data.
func IngressObjectKey(mediaID, extension string) string {
	return fmt.Sprintf("media/%s/source/upload%s", mediaID, normalizeExtension(extension, ".bin"))
}

// OriginalObjectKey returns the opaque key for one immutable canonical
// generation object.
func OriginalObjectKey(mediaID string, generation int, extension string) string {
	return fmt.Sprintf("media/%s/generations/%d/original%s", mediaID, generation, normalizeExtension(extension, ".bin"))
}

// ImageVariantObjectKey returns the opaque key for one WebP derivative of a
// media generation.
func ImageVariantObjectKey(mediaID string, generation int, variant Variant) string {
	return fmt.Sprintf("media/%s/generations/%d/%s.webp", mediaID, generation, lowerVariant(variant))
}

func lowerVariant(variant Variant) string {
	switch variant {
	case VariantSmall:
		return "small"
	case VariantMedium:
		return "medium"
	case VariantLarge:
		return "large"
	default:
		panic(fmt.Sprintf("%s is not a WebP derivative", variant))
	}
}

func normalizeExtension(extension, fallback string) string {
	extension = strings.ToLower(strings.TrimSpace(extension))
	extension = strings.TrimPrefix(extension, ".")
	if extension == "" || len(extension) > 10 {
		return fallback
	}
	for _, character := range extension {
		if !((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
			return fallback
		}
	}
	return "." + extension
}
