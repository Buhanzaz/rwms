package media

import "fmt"

// Object keys use opaque media IDs. Owner identifiers never enter a MinIO key,
// preventing client-visible object names from leaking cabin or estimate data.
func IngressObjectKey(mediaID, extension string) string {
	return fmt.Sprintf("media/%s/source/upload%s", mediaID, normalizeExtension(extension, ".bin"))
}

func OriginalObjectKey(mediaID string, generation int, extension string) string {
	return fmt.Sprintf("media/%s/generations/%d/original%s", mediaID, generation, normalizeExtension(extension, ".bin"))
}

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
	if extension == "" {
		return fallback
	}
	if extension[0] == '.' {
		return extension
	}
	return "." + extension
}
