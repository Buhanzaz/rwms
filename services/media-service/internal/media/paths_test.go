package media

import "testing"

func TestObjectKeysAreOpaqueAndGenerationScoped(t *testing.T) {
	if got, want := IngressObjectKey("a-1", "jpg"), "media/a-1/source/upload.jpg"; got != want {
		t.Fatalf("ingress key = %q, want %q", got, want)
	}
	if got, want := OriginalObjectKey("a-1", 3, ".mp4"), "media/a-1/generations/3/original.mp4"; got != want {
		t.Fatalf("original key = %q, want %q", got, want)
	}
	if got, want := ImageVariantObjectKey("a-1", 3, VariantMedium), "media/a-1/generations/3/medium.webp"; got != want {
		t.Fatalf("variant key = %q, want %q", got, want)
	}
}
