package assetimport

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

func TestYandexPublicResourcesUseCanonicalShareURLWithoutOAuthAndDownloadsFreshHref(t *testing.T) {
	var apiCalls atomic.Int32
	var downloadCalls atomic.Int32
	const publicKey = "AbCdEfGhIjKlMn"
	const publicURL = "https://disk.yandex.ru/d/AbCdEfGhIjKlMn"
	var server *httptest.Server
	server = httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "" {
			t.Fatal("public Yandex request unexpectedly carried OAuth authorization")
		}
		switch request.URL.Path {
		case "/v1/disk/public/resources":
			apiCalls.Add(1)
			if request.URL.Query().Get("public_key") != publicURL {
				t.Fatalf("public key = %q", request.URL.Query().Get("public_key"))
			}
			switch request.URL.Query().Get("path") {
			case "":
				_, _ = io.WriteString(response, `{"type":"dir","_embedded":{"total":2,"items":[{"type":"dir","path":"disk:/nested"},{"type":"file","name":"movie.mp4","path":"disk:/movie.mp4","mime_type":"video/mp4","size":24}]}}`)
			case "disk:/nested":
				_, _ = io.WriteString(response, `{"type":"dir","_embedded":{"total":1,"items":[{"type":"file","name":"photo.jpg","path":"disk:/nested/photo.jpg","mime_type":"image/jpeg","size":4}]}}`)
			default:
				t.Fatalf("unexpected resource path %q", request.URL.Query().Get("path"))
			}
		case "/v1/disk/public/resources/download":
			if request.URL.Query().Get("public_key") != publicURL {
				t.Fatalf("download public key = %q", request.URL.Query().Get("public_key"))
			}
			if request.URL.Query().Get("path") != "disk:/nested/photo.jpg" {
				t.Fatalf("download path = %q", request.URL.Query().Get("path"))
			}
			// Yandex can include link metadata that is not needed to fetch the
			// href. The worker accepts it while retaining size and URL guards.
			_, _ = io.WriteString(response, `{"href":"`+server.URL+`/download/photo","method":"GET","templated":false}`)
		case "/download/photo":
			downloadCalls.Add(1)
			response.Header().Set("Content-Type", "image/jpeg")
			_, _ = response.Write([]byte{0xff, 0xd8, 0xff, 0xd9})
		default:
			t.Fatalf("unexpected Yandex path %q", request.URL.Path)
		}
	}))
	defer server.Close()
	client := newTestYandexClient(t, server)

	entries, err := client.Enumerate(context.Background(), publicKey)
	if err != nil {
		t.Fatalf("Enumerate() error = %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("entries = %#v, want image plus skipped video", entries)
	}
	var image, video DiscoveredEntry
	for _, entry := range entries {
		if entry.ResourcePath == "disk:/nested/photo.jpg" {
			image = entry
		}
		if entry.ResourcePath == "disk:/movie.mp4" {
			video = entry
		}
	}
	if image.Status != EntryPrepared || image.ContentType != "image/jpeg" || video.Status != EntrySkipped || video.WarningCode != "UNSUPPORTED_MEDIA" {
		t.Fatalf("enumerated entries = %#v", entries)
	}
	if downloadCalls.Load() != 0 {
		t.Fatalf("preflight downloaded %d files", downloadCalls.Load())
	}
	body, metadata, err := client.Download(context.Background(), publicKey, image.ResourcePath)
	if err != nil {
		t.Fatalf("Download() error = %v", err)
	}
	defer body.Close()
	bytes, err := io.ReadAll(body)
	if err != nil || len(bytes) != 4 || metadata.ContentType != "image/jpeg" || downloadCalls.Load() != 1 || apiCalls.Load() < 2 {
		t.Fatalf("download bytes=%x metadata=%#v calls api=%d download=%d err=%v", bytes, metadata, apiCalls.Load(), downloadCalls.Load(), err)
	}
}

func TestYandexPublicResourcesAcceptsDocumentedAdditionalMetadata(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/disk/public/resources" {
			response.WriteHeader(http.StatusNotFound)
			return
		}
		// The public Yandex API includes metadata that the import worker does
		// not need to persist. It must not turn a valid share into a rejected
		// resource merely because Yandex adds or returns those fields.
		_, _ = io.WriteString(response, `{
			"type":"file",
			"name":"photo.jpg",
			"path":"disk:/photo.jpg",
			"mime_type":"image/jpeg",
			"size":4,
			"resource_id":"123456:disk:/photo.jpg",
			"public_key":"AbCdEfGhIjKlMn",
			"public_url":"https://disk.yandex.ru/d/AbCdEfGhIjKlMn",
			"modified":"2026-07-30T10:00:00+00:00",
			"antivirus_status":"clean"
		}`)
	}))
	defer server.Close()

	entries, err := newTestYandexClient(t, server).Enumerate(context.Background(), "AbCdEfGhIjKlMn")
	if err != nil {
		t.Fatalf("Enumerate() with additional Yandex metadata = %v", err)
	}
	if len(entries) != 1 || entries[0].ResourcePath != "disk:/photo.jpg" || entries[0].Status != EntryPrepared {
		t.Fatalf("entries = %#v", entries)
	}
}

func TestYandexDownloadArchiveUsesCanonicalShareURLWithoutPath(t *testing.T) {
	const publicKey = "AbCdEfGhIjKlMn"
	const publicURL = "https://disk.yandex.ru/d/AbCdEfGhIjKlMn"
	var server *httptest.Server
	server = httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/v1/disk/public/resources/download":
			if request.URL.Query().Get("public_key") != publicURL {
				t.Fatalf("archive public key = %q", request.URL.Query().Get("public_key"))
			}
			if request.URL.Query().Get("path") != "" {
				t.Fatalf("archive download unexpectedly includes path %q", request.URL.Query().Get("path"))
			}
			_, _ = io.WriteString(response, `{"href":"`+server.URL+`/download/all"}`)
		case "/download/all":
			response.Header().Set("Content-Type", "application/zip")
			_, _ = response.Write([]byte("PK\x03\x04archive"))
		default:
			response.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	body, metadata, err := newTestYandexClient(t, server).DownloadArchive(context.Background(), publicKey)
	if err != nil {
		t.Fatalf("DownloadArchive() error = %v", err)
	}
	defer body.Close()
	payload, err := io.ReadAll(body)
	if err != nil || string(payload) != "PK\x03\x04archive" || metadata.ContentType != "application/zip" {
		t.Fatalf("archive payload=%q metadata=%#v err=%v", payload, metadata, err)
	}
}

func TestYandexDownloadRejectsNonHTTPSOrForeignHref(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path == "/v1/disk/public/resources/download" {
			_, _ = io.WriteString(response, `{"href":"http://127.0.0.1/private"}`)
			return
		}
		response.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()
	client := newTestYandexClient(t, server)

	_, _, err := client.Download(context.Background(), "AbCdEfGhIjKlMn", "disk:/photo.jpg")
	if !errors.Is(err, ErrUnsafeDownload) {
		t.Fatalf("Download() error = %v, want unsafe target", err)
	}
}

func TestYandexEnumerationEnforcesPerSourceFileBound(t *testing.T) {
	for _, scenario := range []struct {
		name     string
		files    int
		rejected bool
	}{
		{name: "one hundred files accepted", files: MaxEntriesPerSource},
		{name: "one hundred one files rejected", files: MaxEntriesPerSource + 1, rejected: true},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
				if request.URL.Path != "/v1/disk/public/resources" {
					response.WriteHeader(http.StatusNotFound)
					return
				}
				offset, err := strconv.Atoi(request.URL.Query().Get("offset"))
				if err != nil {
					t.Fatalf("parse page offset: %v", err)
				}
				_, _ = io.WriteString(response, yandexDirectoryListing(scenario.files, offset))
			}))
			defer server.Close()
			client := newTestYandexClient(t, server)

			entries, err := client.Enumerate(context.Background(), "AbCdEfGhIjKlMn")
			if scenario.rejected {
				if !errors.Is(err, ErrExternalRejected) {
					t.Fatalf("Enumerate() error = %v, want ErrExternalRejected", err)
				}
				return
			}
			if err != nil || len(entries) != scenario.files {
				t.Fatalf("Enumerate() entries=%d error=%v, want %d entries", len(entries), err, scenario.files)
			}
		})
	}
}

func TestYandexEnumerationBoundsMetadataTraversalPerSource(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/disk/public/resources" {
			response.WriteHeader(http.StatusNotFound)
			return
		}
		calls.Add(1)
		_, _ = io.WriteString(response, fmt.Sprintf(`{"type":"dir","_embedded":{"total":%d,"items":[]}}`,
			maxYandexResourcePagesPerSource*yandexPageLimit+1))
	}))
	defer server.Close()
	client := newTestYandexClient(t, server)

	_, err := client.Enumerate(context.Background(), "AbCdEfGhIjKlMn")
	if !errors.Is(err, ErrExternalRejected) {
		t.Fatalf("Enumerate() error = %v, want ErrExternalRejected", err)
	}
	if calls.Load() != maxYandexResourcePagesPerSource {
		t.Fatalf("metadata page calls = %d, want %d", calls.Load(), maxYandexResourcePagesPerSource)
	}
}

func TestParsePublicURLAndPublicIPGuards(t *testing.T) {
	key, err := ParsePublicURL("https://disk.yandex.ru/d/AbCdEfGhIjKlMn")
	if err != nil || key != "AbCdEfGhIjKlMn" {
		t.Fatalf("ParsePublicURL() = %q, %v", key, err)
	}
	for _, invalid := range []string{
		"http://disk.yandex.ru/d/AbCdEfGhIjKlMn",
		"https://disk.yandex.ru/d/AbCdEfGhIjKlMn?x=1",
		"https://disk.yandex.ru/d/AbCdEfGhIjKlMn/",
		"https://evil.example/d/AbCdEfGhIjKlMn",
	} {
		if _, err := ParsePublicURL(invalid); err == nil {
			t.Fatalf("ParsePublicURL accepted %q", invalid)
		}
	}
	for _, raw := range []string{
		"127.0.0.1", "10.0.0.1", "169.254.1.1", "192.0.2.1", "198.18.0.1", "198.51.100.1",
		"203.0.113.1", "240.0.0.1", "::1", "fc00::1", "2001:db8::1",
	} {
		parsed := strings.TrimSpace(raw)
		address, err := netip.ParseAddr(parsed)
		if err != nil || safePublicIP(address) {
			t.Fatalf("safePublicIP(%s) = true, err=%v", raw, err)
		}
	}
	for _, host := range []string{"downloader.disk.yandex.ru", "zipper-external.disk.yandex.net"} {
		if !allowedYandexHost(host) {
			t.Fatalf("allowedYandexHost(%q) = false", host)
		}
	}
	if allowedYandexHost("zipper-external.disk.yandex.example") {
		t.Fatal("allowedYandexHost accepted a foreign archive host")
	}
}

func newTestYandexClient(t *testing.T, server *httptest.Server) *YandexClient {
	t.Helper()
	base, err := url.Parse(server.URL)
	if err != nil {
		t.Fatalf("parse test URL: %v", err)
	}
	validate := func(candidate *url.URL) error {
		if candidate == nil || candidate.Scheme != "https" || candidate.Host != base.Host {
			return ErrUnsafeDownload
		}
		return nil
	}
	client, err := NewYandexClientForTest(YandexOptions{
		ResourcesURL: server.URL + "/v1/disk/public/resources",
		HTTPClient:   server.Client(),
		ValidateURL:  validate,
	})
	if err != nil {
		t.Fatalf("NewYandexClientForTest() error = %v", err)
	}
	return client
}

func yandexDirectoryListing(total, offset int) string {
	end := offset + yandexPageLimit
	if end > total {
		end = total
	}
	var body strings.Builder
	fmt.Fprintf(&body, `{"type":"dir","_embedded":{"total":%d,"items":[`, total)
	for index := offset; index < end; index++ {
		if index > offset {
			body.WriteByte(',')
		}
		fmt.Fprintf(&body, `{"type":"file","name":"photo-%d.jpg","path":"disk:/fixture/photo-%d.jpg","mime_type":"image/jpeg","size":4}`,
			index, index)
	}
	body.WriteString(`]}}`)
	return body.String()
}
