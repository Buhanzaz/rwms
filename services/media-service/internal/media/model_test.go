package media

import "testing"

func TestSortForPresentationPlacesVideosAfterImages(t *testing.T) {
	assets := []Asset{
		{ID: "video-first", Kind: KindVideo, SortOrder: 0, CreatedAtUnix: 1},
		{ID: "image-later", Kind: KindImage, SortOrder: 10, CreatedAtUnix: 2},
		{ID: "image-first", Kind: KindImage, SortOrder: 0, CreatedAtUnix: 3},
		{ID: "video-later", Kind: KindVideo, SortOrder: 10, CreatedAtUnix: 4},
	}

	SortForPresentation(assets)

	got := []string{assets[0].ID, assets[1].ID, assets[2].ID, assets[3].ID}
	want := []string{"image-first", "image-later", "video-first", "video-later"}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("ordered assets = %v, want %v", got, want)
		}
	}
}

func TestKindForContentType(t *testing.T) {
	tests := []struct {
		contentType string
		kind        Kind
		ok          bool
	}{
		{"image/jpeg", KindImage, true},
		{"image/webp; charset=binary", KindImage, true},
		{"video/mp4", KindVideo, true},
		{"application/pdf", "", false},
	}

	for _, test := range tests {
		kind, ok := KindForContentType(test.contentType)
		if kind != test.kind || ok != test.ok {
			t.Fatalf("KindForContentType(%q) = (%q, %t), want (%q, %t)", test.contentType, kind, ok, test.kind, test.ok)
		}
	}
}

func TestImageVariantsKeepLegacyDefaultsWhenConfigurationIsUnset(t *testing.T) {
	variants := (VariantConfiguration{}).ImageVariants()
	if variants[0].LongEdge != 320 || variants[1].LongEdge != 640 || variants[2].LongEdge != 1280 {
		t.Fatalf("unexpected default variants: %#v", variants)
	}
}
