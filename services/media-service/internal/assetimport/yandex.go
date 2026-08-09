package assetimport

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

const (
	defaultYandexResourcesURL = "https://cloud-api.yandex.net/v1/disk/public/resources"
	yandexResponseLimit       = 4 << 20
	yandexPageLimit           = 100
	// A finite page bound prevents a remote tree containing only directories or
	// ignored resource types from consuming an unbounded preflight traversal.
	maxYandexResourcePagesPerSource = 64
	maxYandexRedirects              = 3
)

var (
	// ErrExternalUnavailable identifies a transient Yandex.Disk dependency
	// failure that may be retried by the durable worker.
	ErrExternalUnavailable = errors.New("Yandex public resources unavailable")
	// ErrExternalRejected identifies a remote source that violates import rules.
	ErrExternalRejected = errors.New("Yandex public resource rejected")
	// ErrUnsafeDownload prevents an untrusted URL or network target from being
	// requested by the Yandex.Disk import client.
	ErrUnsafeDownload = errors.New("unsafe Yandex download target")
	nonPublicIPRanges = []netip.Prefix{
		netip.MustParsePrefix("192.0.0.0/24"),    // IETF protocol assignments
		netip.MustParsePrefix("192.0.2.0/24"),    // TEST-NET-1
		netip.MustParsePrefix("192.88.99.0/24"),  // deprecated 6to4 relay anycast
		netip.MustParsePrefix("198.18.0.0/15"),   // benchmarking
		netip.MustParsePrefix("198.51.100.0/24"), // TEST-NET-2
		netip.MustParsePrefix("203.0.113.0/24"),  // TEST-NET-3
		netip.MustParsePrefix("240.0.0.0/4"),     // reserved for future use
		netip.MustParsePrefix("2001:db8::/32"),   // documentation
	}
)

// YandexOptions is only for local fake-HTTP tests. Production constructs the
// client through NewYandexClient, which fixes the official API endpoint and
// strict HTTPS/DNS policy.
type YandexOptions struct {
	ResourcesURL string
	HTTPClient   *http.Client
	ValidateURL  func(*url.URL) error
}

// YandexClient is the hardened client for the official public-resources API.
// It retains public keys and transient download URLs within this package.
type YandexClient struct {
	resourcesURL *url.URL
	downloadURL  *url.URL
	client       *http.Client
	validateURL  func(*url.URL) error
}

// NewYandexClient creates a production client pinned to Yandex.Disk's official
// HTTPS public-resources endpoint and DNS/redirect safety policy.
func NewYandexClient() (*YandexClient, error) {
	client := newSafeYandexHTTPClient()
	return newYandexClient(YandexOptions{
		ResourcesURL: defaultYandexResourcesURL,
		HTTPClient:   client,
		ValidateURL:  validateYandexURL,
	}, false)
}

// NewYandexClientForTest creates a client with injectable HTTP dependencies
// for isolated tests. Production must use NewYandexClient.
func NewYandexClientForTest(options YandexOptions) (*YandexClient, error) {
	return newYandexClient(options, true)
}

func newYandexClient(options YandexOptions, allowTestEndpoint bool) (*YandexClient, error) {
	resourcesURL, err := url.Parse(strings.TrimSpace(options.ResourcesURL))
	if err != nil || resourcesURL == nil || resourcesURL.Scheme == "" || resourcesURL.Host == "" ||
		resourcesURL.User != nil || resourcesURL.Fragment != "" || resourcesURL.RawQuery != "" {
		return nil, fmt.Errorf("Yandex public resources endpoint is invalid")
	}
	if !allowTestEndpoint && (resourcesURL.String() != defaultYandexResourcesURL || resourcesURL.Scheme != "https") {
		return nil, fmt.Errorf("Yandex public resources endpoint must be canonical HTTPS")
	}
	downloadURL := *resourcesURL
	if !strings.HasSuffix(downloadURL.Path, "/resources") {
		return nil, fmt.Errorf("Yandex public resources endpoint is invalid")
	}
	downloadURL.Path += "/download"
	client := options.HTTPClient
	if client == nil {
		client = newSafeYandexHTTPClient()
	}
	validate := options.ValidateURL
	if validate == nil {
		validate = validateYandexURL
	}
	return &YandexClient{resourcesURL: resourcesURL, downloadURL: &downloadURL, client: client, validateURL: validate}, nil
}

// Enumerate recursively lists bounded, supported entries for one private
// public key without downloading their content.
func (client *YandexClient) Enumerate(ctx context.Context, publicKey string) ([]DiscoveredEntry, error) {
	if client == nil || client.resourcesURL == nil || client.client == nil || client.validateURL == nil || !ValidYandexPublicKey(publicKey) {
		return nil, ErrExternalRejected
	}
	entries := make([]DiscoveredEntry, 0, MaxEntriesPerSource)
	visited := make(map[string]struct{})
	resourcePageCount := 0
	loadPage := func(resourcePath string, offset int) (yandexResourcePage, error) {
		if resourcePageCount >= maxYandexResourcePagesPerSource {
			return yandexResourcePage{}, ErrExternalRejected
		}
		resourcePageCount++
		return client.resources(ctx, publicKey, resourcePath, offset)
	}
	appendEntry := func(resource yandexResource) error {
		entry, include := discoveredResource(resource)
		if !include {
			return nil
		}
		if len(entries) >= MaxEntriesPerSource {
			return ErrExternalRejected
		}
		entries = append(entries, entry)
		return nil
	}
	var walk func(string) error
	walk = func(resourcePath string) error {
		if len(resourcePath) > 4096 {
			return ErrExternalRejected
		}
		if _, exists := visited[resourcePath]; exists {
			return nil
		}
		visited[resourcePath] = struct{}{}
		page, err := loadPage(resourcePath, 0)
		if err != nil {
			return err
		}
		if strings.EqualFold(page.Type, "file") {
			return appendEntry(page.Resource)
		}
		if !strings.EqualFold(page.Type, "dir") || page.Embedded == nil {
			return ErrExternalRejected
		}
		for offset := 0; ; offset += yandexPageLimit {
			current := page
			if offset > 0 {
				current, err = loadPage(resourcePath, offset)
				if err != nil {
					return err
				}
				if !strings.EqualFold(current.Type, "dir") || current.Embedded == nil {
					return ErrExternalRejected
				}
			}
			for _, item := range current.Embedded.Items {
				if strings.EqualFold(item.Type, "dir") {
					if item.Path == "" {
						return ErrExternalRejected
					}
					if err := walk(item.Path); err != nil {
						return err
					}
					continue
				}
				if err := appendEntry(item); err != nil {
					return err
				}
			}
			total := current.Embedded.Total
			if total <= offset+yandexPageLimit {
				return nil
			}
		}
	}
	if err := walk(""); err != nil {
		return nil, err
	}
	return entries, nil
}

// Download opens one approved resource path behind a public key. Callers own
// the returned stream and must close it without logging its transient URL.
func (client *YandexClient) Download(ctx context.Context, publicKey, resourcePath string) (io.ReadCloser, DownloadMetadata, error) {
	if client == nil || client.downloadURL == nil || client.client == nil || client.validateURL == nil || !ValidYandexPublicKey(publicKey) ||
		strings.TrimSpace(resourcePath) == "" || len(resourcePath) > 4096 {
		return nil, DownloadMetadata{}, ErrExternalRejected
	}
	requestURL := *client.downloadURL
	query := requestURL.Query()
	query.Set("public_key", yandexPublicResourceURL(publicKey))
	query.Set("path", resourcePath)
	requestURL.RawQuery = query.Encode()
	return client.download(ctx, &requestURL)
}

// DownloadArchive fetches Yandex.Disk's fresh "download all" payload for one
// public resource. A directory yields a ZIP; a public single-file resource
// yields that original file. The worker validates and handles both forms
// without ever exposing the short-lived downloader URL outside this package.
func (client *YandexClient) DownloadArchive(ctx context.Context, publicKey string) (io.ReadCloser, DownloadMetadata, error) {
	if client == nil || client.downloadURL == nil || client.client == nil || client.validateURL == nil || !ValidYandexPublicKey(publicKey) {
		return nil, DownloadMetadata{}, ErrExternalRejected
	}
	requestURL := *client.downloadURL
	query := requestURL.Query()
	query.Set("public_key", yandexPublicResourceURL(publicKey))
	requestURL.RawQuery = query.Encode()
	return client.download(ctx, &requestURL)
}

func (client *YandexClient) download(ctx context.Context, requestURL *url.URL) (io.ReadCloser, DownloadMetadata, error) {
	if requestURL == nil {
		return nil, DownloadMetadata{}, ErrExternalRejected
	}
	if err := client.validateURL(requestURL); err != nil {
		return nil, DownloadMetadata{}, ErrUnsafeDownload
	}
	response, err := client.do(ctx, requestURL)
	if err != nil {
		return nil, DownloadMetadata{}, err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotFound || response.StatusCode == http.StatusForbidden || response.StatusCode == http.StatusBadRequest {
		return nil, DownloadMetadata{}, ErrExternalRejected
	}
	if response.StatusCode != http.StatusOK {
		return nil, DownloadMetadata{}, ErrExternalUnavailable
	}
	var payload struct {
		Href string `json:"href"`
	}
	if err := decodeBoundedJSON(response.Body, &payload); err != nil || strings.TrimSpace(payload.Href) == "" {
		return nil, DownloadMetadata{}, ErrExternalRejected
	}
	href, err := url.Parse(payload.Href)
	if err != nil || href == nil || client.validateURL(href) != nil {
		return nil, DownloadMetadata{}, ErrUnsafeDownload
	}
	download, err := client.do(ctx, href)
	if err != nil {
		return nil, DownloadMetadata{}, err
	}
	if download.StatusCode == http.StatusNotFound || download.StatusCode == http.StatusForbidden || download.StatusCode == http.StatusBadRequest {
		download.Body.Close()
		return nil, DownloadMetadata{}, ErrExternalRejected
	}
	if download.StatusCode != http.StatusOK {
		download.Body.Close()
		return nil, DownloadMetadata{}, ErrExternalUnavailable
	}
	return download.Body, DownloadMetadata{
		ContentType:   strings.ToLower(strings.TrimSpace(strings.Split(download.Header.Get("Content-Type"), ";")[0])),
		ContentLength: download.ContentLength,
	}, nil
}

func (client *YandexClient) resources(ctx context.Context, publicKey, resourcePath string, offset int) (yandexResourcePage, error) {
	if !ValidYandexPublicKey(publicKey) {
		return yandexResourcePage{}, ErrExternalRejected
	}
	requestURL := *client.resourcesURL
	query := requestURL.Query()
	// The public resources API accepts a public URL as its public_key value.
	// The durable source intentionally stores only the opaque key, so rebuild
	// the canonical share URL only at this outbound boundary.
	query.Set("public_key", yandexPublicResourceURL(publicKey))
	query.Set("limit", strconv.Itoa(yandexPageLimit))
	query.Set("offset", strconv.Itoa(offset))
	if resourcePath != "" {
		query.Set("path", resourcePath)
	}
	requestURL.RawQuery = query.Encode()
	if err := client.validateURL(&requestURL); err != nil {
		return yandexResourcePage{}, ErrUnsafeDownload
	}
	response, err := client.do(ctx, &requestURL)
	if err != nil {
		return yandexResourcePage{}, err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotFound || response.StatusCode == http.StatusForbidden || response.StatusCode == http.StatusBadRequest {
		return yandexResourcePage{}, ErrExternalRejected
	}
	if response.StatusCode != http.StatusOK {
		return yandexResourcePage{}, ErrExternalUnavailable
	}
	var result yandexResourcePage
	if err := decodeBoundedJSON(response.Body, &result); err != nil {
		return yandexResourcePage{}, ErrExternalRejected
	}
	return result, nil
}

func (client *YandexClient) do(ctx context.Context, target *url.URL) (*http.Response, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return nil, ErrExternalRejected
	}
	response, err := client.client.Do(request)
	if err != nil {
		if errors.Is(err, ErrUnsafeDownload) {
			return nil, ErrUnsafeDownload
		}
		return nil, ErrExternalUnavailable
	}
	return response, nil
}

type yandexResourcePage struct {
	Type     string                 `json:"type"`
	Resource yandexResource         `json:"-"`
	Embedded *yandexEmbeddedListing `json:"_embedded"`
	yandexResource
}

type yandexEmbeddedListing struct {
	Items []yandexResource `json:"items"`
	Total int              `json:"total"`
}

type yandexResource struct {
	Type      string `json:"type"`
	Name      string `json:"name"`
	Path      string `json:"path"`
	MimeType  string `json:"mime_type"`
	MediaType string `json:"media_type"`
	Size      *int64 `json:"size"`
}

// UnmarshalJSON accepts the bounded Yandex resource-page shape used by the import adapter.
func (page *yandexResourcePage) UnmarshalJSON(body []byte) error {
	type rawPage struct {
		Type      string                 `json:"type"`
		Name      string                 `json:"name"`
		Path      string                 `json:"path"`
		MimeType  string                 `json:"mime_type"`
		MediaType string                 `json:"media_type"`
		Size      *int64                 `json:"size"`
		Embedded  *yandexEmbeddedListing `json:"_embedded"`
	}
	var decoded rawPage
	if err := json.Unmarshal(body, &decoded); err != nil {
		return err
	}
	page.Type = decoded.Type
	page.Resource = yandexResource{Type: decoded.Type, Name: decoded.Name, Path: decoded.Path,
		MimeType: decoded.MimeType, MediaType: decoded.MediaType, Size: decoded.Size}
	page.Embedded = decoded.Embedded
	return nil
}

func discoveredResource(resource yandexResource) (DiscoveredEntry, bool) {
	if !strings.EqualFold(resource.Type, "file") || strings.TrimSpace(resource.Path) == "" || len(resource.Path) > 4096 {
		return DiscoveredEntry{}, false
	}
	contentType, supported := supportedImageContentType(resource.MimeType, resource.Name)
	entry := DiscoveredEntry{
		ResourcePath: resource.Path,
		FileName:     safeFileName(resource.Name, contentType),
		ContentType:  contentType,
		SizeBytes:    resource.Size,
		Status:       EntryPrepared,
	}
	if !supported {
		entry.Status = EntrySkipped
		entry.WarningCode = "UNSUPPORTED_MEDIA"
	}
	return entry, true
}

func yandexPublicResourceURL(publicKey string) string {
	return "https://disk.yandex.ru/d/" + publicKey
}

func supportedImageContentType(mimeType, name string) (string, bool) {
	normalized := strings.ToLower(strings.TrimSpace(strings.Split(mimeType, ";")[0]))
	switch normalized {
	case "image/jpeg", "image/png", "image/webp":
		return normalized, true
	case "video/mp4", "video/webm", "application/zip", "application/x-zip-compressed":
		return normalized, false
	}
	switch strings.ToLower(filepath.Ext(name)) {
	case ".jpg", ".jpeg":
		return "image/jpeg", true
	case ".png":
		return "image/png", true
	case ".webp":
		return "image/webp", true
	default:
		return normalized, false
	}
}

func safeFileName(value, contentType string) string {
	value = strings.TrimSpace(filepath.Base(strings.ReplaceAll(value, "\\", "/")))
	if value == "." || value == ".." || value == "/" || value == "" || len(value) > 512 {
		value = "import" + extensionForContentType(contentType)
	}
	for _, character := range value {
		if character < 0x20 || character == 0x7f {
			return "import" + extensionForContentType(contentType)
		}
	}
	return value
}

func extensionForContentType(contentType string) string {
	switch contentType {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	default:
		return ".bin"
	}
}

func decodeBoundedJSON(reader io.Reader, target any) error {
	decoder := json.NewDecoder(io.LimitReader(reader, yandexResponseLimit+1))
	if err := decoder.Decode(target); err != nil {
		return err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return errors.New("trailing JSON")
	}
	return nil
}

func newSafeYandexHTTPClient() *http.Client {
	dialer := &net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}
	transport := &http.Transport{
		Proxy:                 nil,
		DialContext:           safeYandexDialContext(dialer),
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          8,
		MaxIdleConnsPerHost:   2,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: 10 * time.Second,
		ExpectContinueTimeout: time.Second,
	}
	return &http.Client{
		Transport: transport,
		Timeout:   30 * time.Second,
		CheckRedirect: func(request *http.Request, via []*http.Request) error {
			if len(via) >= maxYandexRedirects || validateYandexURL(request.URL) != nil {
				return ErrUnsafeDownload
			}
			return nil
		},
	}
}

func validateYandexURL(target *url.URL) error {
	if target == nil || target.Scheme != "https" || target.User != nil || target.Hostname() == "" ||
		target.Fragment != "" || !allowedYandexHost(target.Hostname()) {
		return ErrUnsafeDownload
	}
	if target.Port() != "" && target.Port() != "443" {
		return ErrUnsafeDownload
	}
	if ip, err := netip.ParseAddr(target.Hostname()); err == nil && !safePublicIP(ip) {
		return ErrUnsafeDownload
	}
	return nil
}

func allowedYandexHost(host string) bool {
	host = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(host), "."))
	return host == "cloud-api.yandex.net" || host == "disk.yandex.ru" ||
		strings.HasSuffix(host, ".disk.yandex.ru") || host == "disk.yandex.net" ||
		strings.HasSuffix(host, ".disk.yandex.net")
}

func safeYandexDialContext(dialer *net.Dialer) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil || !allowedYandexHost(host) || (port != "443" && port != "") {
			return nil, ErrUnsafeDownload
		}
		addresses, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		if err != nil || len(addresses) == 0 {
			return nil, ErrExternalUnavailable
		}
		for _, address := range addresses {
			if !safePublicIP(address) {
				return nil, ErrUnsafeDownload
			}
		}
		// Each selected numeric address is revalidated at connection time, which
		// prevents a DNS rebinding response from being handed to the default
		// dialer after URL validation. Public Yandex DNS can return IPv6 before
		// IPv4; try every validated result so a host without IPv6 connectivity
		// can still fetch the archive over IPv4.
		var lastErr error
		for _, candidate := range addresses {
			connection, err := dialer.DialContext(ctx, network, net.JoinHostPort(candidate.String(), port))
			if err == nil {
				return connection, nil
			}
			lastErr = err
		}
		if lastErr != nil {
			return nil, ErrExternalUnavailable
		}
		return nil, ErrExternalUnavailable
	}
}

func safePublicIP(address netip.Addr) bool {
	if !address.IsValid() || !address.IsGlobalUnicast() || address.IsPrivate() || address.IsLoopback() ||
		address.IsLinkLocalUnicast() || address.IsLinkLocalMulticast() || address.IsMulticast() ||
		address.IsUnspecified() {
		return false
	}
	if address.Is4() {
		value := address.As4()
		// Carrier-grade NAT is not publicly routable and must not become an
		// SSRF escape hatch merely because netip does not mark it private.
		if value[0] == 100 && value[1] >= 64 && value[1] <= 127 {
			return false
		}
	}
	for _, prefix := range nonPublicIPRanges {
		if prefix.Contains(address) {
			return false
		}
	}
	return true
}

func entryID(jobID, sourceRowID uuid.UUID, resourcePath string) uuid.UUID {
	return uuid.NewSHA1(uuid.NameSpaceOID, []byte("rwms:asset-import-entry:"+jobID.String()+":"+sourceRowID.String()+":"+resourcePath))
}

func importedMediaID(jobID, entryID uuid.UUID) uuid.UUID {
	return uuid.NewSHA1(uuid.NameSpaceOID, []byte("rwms:asset-import-media:"+jobID.String()+":"+entryID.String()))
}
