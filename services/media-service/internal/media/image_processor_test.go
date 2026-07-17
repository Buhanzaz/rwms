package media

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"testing"
)

func TestImageProcessorRotatesCanonicalOriginalAndCreatesWebPVariants(t *testing.T) {
	store := newMemoryObjectStore(map[string][]byte{
		"media/m-1/source/upload.jpg": testJPEG(t, 64, 32),
	})

	result, err := (ImageProcessor{Store: store, Limits: testProcessingLimits()}).Process(context.Background(), ImageProcessRequest{
		MediaID:         "m-1",
		SourceObjectKey: "media/m-1/source/upload.jpg",
		SourceVersionID: "version-1",
		Generation:      2,
		Rotation:        Rotation90,
		Variants: VariantConfiguration{
			SmallLongEdge:  16,
			MediumLongEdge: 32,
			LargeLongEdge:  48,
		},
	})
	if err != nil {
		t.Fatalf("Process() error = %v", err)
	}

	if got, want := result.Original.ObjectKey, "media/m-1/generations/2/original.jpg"; got != want {
		t.Fatalf("original key = %q, want %q", got, want)
	}
	if got, want := result.Original.ContentType, "image/jpeg"; got != want {
		t.Fatalf("original content type = %q, want %q", got, want)
	}
	if got, want := [2]int{result.Original.Width, result.Original.Height}, [2]int{32, 64}; got != want {
		t.Fatalf("rotated original dimensions = %v, want %v", got, want)
	}
	if len(result.Variants) != 3 {
		t.Fatalf("variant count = %d, want 3", len(result.Variants))
	}

	wantDimensions := map[Variant][2]int{
		VariantSmall:  {8, 16},
		VariantMedium: {16, 32},
		VariantLarge:  {24, 48},
	}
	for _, variant := range result.Variants {
		if got, want := variant.ContentType, "image/webp"; got != want {
			t.Fatalf("%s content type = %q, want %q", variant.Variant, got, want)
		}
		if got, want := [2]int{variant.Width, variant.Height}, wantDimensions[variant.Variant]; got != want {
			t.Fatalf("%s dimensions = %v, want %v", variant.Variant, got, want)
		}
		if data := store.objects[variant.ObjectKey]; len(data) == 0 {
			t.Fatalf("%s object %q was not written", variant.Variant, variant.ObjectKey)
		}
	}
}

func testJPEG(t *testing.T, width, height int) []byte {
	t.Helper()
	canvas := image.NewRGBA(image.Rect(0, 0, width, height))
	for x := 0; x < width; x++ {
		for y := 0; y < height; y++ {
			canvas.Set(x, y, color.RGBA{R: uint8(x), G: uint8(y), B: 180, A: 255})
		}
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, canvas, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatalf("encode test JPEG: %v", err)
	}
	return output.Bytes()
}
