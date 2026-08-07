package media

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"testing"

	"github.com/h2non/bimg"
)

func TestImageProcessorKeepsUploadedPixelOrientationAndCreatesWebPVariants(t *testing.T) {
	store := newMemoryObjectStore(map[string][]byte{
		"media/m-1/source/upload.jpg": testJPEG(t, 64, 32),
	})

	result, err := (ImageProcessor{Store: store, Limits: testProcessingLimits()}).Process(context.Background(), ImageProcessRequest{
		MediaID:         "m-1",
		SourceObjectKey: "media/m-1/source/upload.jpg",
		SourceVersionID: "version-1",
		Generation:      2,
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
	if got, want := [2]int{result.Original.Width, result.Original.Height}, [2]int{64, 32}; got != want {
		t.Fatalf("original dimensions = %v, want %v", got, want)
	}
	if len(result.Variants) != 3 {
		t.Fatalf("variant count = %d, want 3", len(result.Variants))
	}

	wantDimensions := map[Variant][2]int{
		VariantSmall:  {16, 8},
		VariantMedium: {32, 16},
		VariantLarge:  {48, 24},
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

func TestImageProcessorDoesNotApplyEXIFOrientation(t *testing.T) {
	const (
		sourceWidth  = 80
		sourceHeight = 40
	)
	for _, test := range []struct {
		name   string
		source []byte
	}{
		{name: "short EXIF orientation", source: testJPEGWithEXIFOrientation(t, sourceWidth, sourceHeight, 6)},
		{name: "Android long EXIF orientation", source: testJPEGWithLongEXIFOrientation(t, sourceWidth, sourceHeight, 6)},
	} {
		t.Run(test.name, func(t *testing.T) {
			metadata, err := bimg.NewImage(test.source).Metadata()
			if err != nil {
				t.Fatalf("read source EXIF orientation: %v", err)
			}
			if got, want := metadata.Orientation, 6; got != want {
				t.Fatalf("source EXIF orientation = %d, want %d", got, want)
			}

			store := newMemoryObjectStore(map[string][]byte{"media/m-1/source/upload.jpg": test.source})
			result, err := (ImageProcessor{Store: store, Limits: testProcessingLimits()}).Process(context.Background(), ImageProcessRequest{
				MediaID:         "m-1",
				SourceObjectKey: "media/m-1/source/upload.jpg",
				SourceVersionID: "version-1",
				Generation:      1,
				Variants: VariantConfiguration{
					SmallLongEdge:  80,
					MediumLongEdge: 80,
					LargeLongEdge:  80,
				},
			})
			if err != nil {
				t.Fatalf("Process() error = %v", err)
			}
			if got, want := [2]int{result.Original.Width, result.Original.Height}, [2]int{sourceWidth, sourceHeight}; got != want {
				t.Fatalf("canonical dimensions = %v, want uploaded pixels %v", got, want)
			}
			assertRawOrientation(t, decodeJPEG(t, store.objects[result.Original.ObjectKey]))

			large := processedVariant(t, result.Variants, VariantLarge)
			if got, want := [2]int{large.Width, large.Height}, [2]int{sourceWidth, sourceHeight}; got != want {
				t.Fatalf("large dimensions = %v, want uploaded pixels %v", got, want)
			}
			assertRawOrientation(t, decodeWebP(t, store.objects[large.ObjectKey]))
		})
	}
}

func processedVariant(t *testing.T, variants []ProcessedVariant, wanted Variant) ProcessedVariant {
	t.Helper()
	for _, variant := range variants {
		if variant.Variant == wanted {
			return variant
		}
	}
	t.Fatalf("processed %s variant was not returned", wanted)
	return ProcessedVariant{}
}

func decodeJPEG(t *testing.T, source []byte) image.Image {
	t.Helper()
	decoded, err := jpeg.Decode(bytes.NewReader(source))
	if err != nil {
		t.Fatalf("decode canonical JPEG: %v", err)
	}
	return decoded
}

func decodeWebP(t *testing.T, source []byte) image.Image {
	t.Helper()
	pngBytes, err := bimg.NewImage(source).Process(bimg.Options{Type: bimg.PNG, NoAutoRotate: true})
	if err != nil {
		t.Fatalf("convert WebP derivative to PNG: %v", err)
	}
	decoded, err := png.Decode(bytes.NewReader(pngBytes))
	if err != nil {
		t.Fatalf("decode converted WebP derivative: %v", err)
	}
	return decoded
}

func assertRawOrientation(t *testing.T, decoded image.Image) {
	t.Helper()
	bounds := decoded.Bounds()
	if got, want := [2]int{bounds.Dx(), bounds.Dy()}, [2]int{80, 40}; got != want {
		t.Fatalf("decoded dimensions = %v, want %v", got, want)
	}
	assertQuadrants(t, decoded, []string{
		"red", "green",
		"blue", "yellow",
	})
}

func assertQuadrants(t *testing.T, decoded image.Image, wanted []string) {
	t.Helper()
	if len(wanted) != 4 {
		t.Fatalf("quadrant expectations = %d, want 4", len(wanted))
	}

	tests := []struct {
		name string
		x    int
		y    int
		want string
	}{
		{name: "top left", x: decoded.Bounds().Dx() / 4, y: decoded.Bounds().Dy() / 4, want: wanted[0]},
		{name: "top right", x: 3 * decoded.Bounds().Dx() / 4, y: decoded.Bounds().Dy() / 4, want: wanted[1]},
		{name: "bottom left", x: decoded.Bounds().Dx() / 4, y: 3 * decoded.Bounds().Dy() / 4, want: wanted[2]},
		{name: "bottom right", x: 3 * decoded.Bounds().Dx() / 4, y: 3 * decoded.Bounds().Dy() / 4, want: wanted[3]},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			bounds := decoded.Bounds()
			assertDominantColour(t, decoded.At(bounds.Min.X+test.x, bounds.Min.Y+test.y), test.want)
		})
	}
}

func assertDominantColour(t *testing.T, sampled color.Color, wanted string) {
	t.Helper()
	red, green, blue, _ := sampled.RGBA()
	var matches bool
	switch wanted {
	case "red":
		matches = red > 2*green && red > 2*blue
	case "green":
		matches = green > 2*red && green > 2*blue
	case "blue":
		matches = blue > 2*red && blue > 2*green
	case "yellow":
		matches = red > 2*blue && green > 2*blue
	default:
		t.Fatalf("unsupported expected colour %q", wanted)
	}
	if !matches {
		t.Fatalf("sampled colour = (%d, %d, %d), want predominantly %s", red>>8, green>>8, blue>>8, wanted)
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

func testJPEGWithEXIFOrientation(t *testing.T, width, height int, orientation uint16) []byte {
	t.Helper()
	canvas := image.NewRGBA(image.Rect(0, 0, width, height))
	for x := 0; x < width; x++ {
		for y := 0; y < height; y++ {
			canvas.Set(x, y, testQuadrantColour(x, y, width, height))
		}
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, canvas, &jpeg.Options{Quality: 100}); err != nil {
		t.Fatalf("encode EXIF-oriented test JPEG: %v", err)
	}
	return withEXIFOrientation(t, output.Bytes(), orientation)
}

func testJPEGWithLongEXIFOrientation(t *testing.T, width, height int, orientation uint32) []byte {
	t.Helper()
	canvas := image.NewRGBA(image.Rect(0, 0, width, height))
	for x := 0; x < width; x++ {
		for y := 0; y < height; y++ {
			canvas.Set(x, y, testQuadrantColour(x, y, width, height))
		}
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, canvas, &jpeg.Options{Quality: 100}); err != nil {
		t.Fatalf("encode Android EXIF-oriented test JPEG: %v", err)
	}
	return withLongEXIFOrientation(t, output.Bytes(), orientation)
}

func testQuadrantColour(x, y, width, height int) color.RGBA {
	switch {
	case x < width/2 && y < height/2:
		return color.RGBA{R: 255, A: 255}
	case x >= width/2 && y < height/2:
		return color.RGBA{G: 255, A: 255}
	case x < width/2 && y >= height/2:
		return color.RGBA{B: 255, A: 255}
	default:
		return color.RGBA{R: 255, G: 255, A: 255}
	}
}

func withEXIFOrientation(t *testing.T, source []byte, orientation uint16) []byte {
	t.Helper()
	if len(source) < 2 || source[0] != 0xff || source[1] != 0xd8 {
		t.Fatal("test JPEG is missing the SOI marker")
	}

	// APP1 carries a little-endian TIFF IFD with one SHORT Orientation entry.
	exif := []byte{
		0xff, 0xe1, 0x00, 0x22,
		'E', 'x', 'i', 'f', 0x00, 0x00,
		'I', 'I', 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
		0x01, 0x00,
		0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
		byte(orientation), byte(orientation >> 8), 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
	}
	withEXIF := make([]byte, 0, len(source)+len(exif))
	withEXIF = append(withEXIF, source[:2]...)
	withEXIF = append(withEXIF, exif...)
	return append(withEXIF, source[2:]...)
}

func withLongEXIFOrientation(t *testing.T, source []byte, orientation uint32) []byte {
	t.Helper()
	if len(source) < 2 || source[0] != 0xff || source[1] != 0xd8 {
		t.Fatal("test JPEG is missing the SOI marker")
	}

	// CameraX files observed on Nothing OS encode Orientation as TIFF LONG.
	exif := []byte{
		0xff, 0xe1, 0x00, 0x22,
		'E', 'x', 'i', 'f', 0x00, 0x00,
		'I', 'I', 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
		0x01, 0x00,
		0x12, 0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00,
		byte(orientation), byte(orientation >> 8), byte(orientation >> 16), byte(orientation >> 24),
		0x00, 0x00, 0x00, 0x00,
	}
	withEXIF := make([]byte, 0, len(source)+len(exif))
	withEXIF = append(withEXIF, source[:2]...)
	withEXIF = append(withEXIF, exif...)
	return append(withEXIF, source[2:]...)
}
