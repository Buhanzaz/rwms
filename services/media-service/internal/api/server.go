package api

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/auth"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/storage"
	"github.com/google/uuid"
)

const correlationHeader = "X-Correlation-Id"

var checksumPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

type Configuration struct {
	MaxUploadBytes   int64
	AllowedMIMETypes map[string]struct{}
	UploadExpiry     time.Duration
	DownloadExpiry   time.Duration
}

type readiness interface {
	Ready(context.Context) error
}

type repository interface {
	CreateUpload(context.Context, persistence.CreateUploadCommand) (persistence.AssetRecord, bool, error)
	UploadSessionForSubject(context.Context, uuid.UUID, uuid.UUID) (persistence.AssetRecord, error)
	FinalizeUpload(context.Context, persistence.FinalizeCommand) (persistence.AssetRecord, bool, error)
	ReadOwnerAssets(context.Context, string, string, uuid.UUID, int, *uuid.UUID,
		func([]persistence.AssetWithVariants) error) error
	ReadOriginal(context.Context, uuid.UUID, string, string, uuid.UUID,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	GetAssetScoped(context.Context, uuid.UUID, string, string, uuid.UUID) (persistence.AssetRecord, error)
	ValidateLogisticsReferences(context.Context, persistence.ValidateLogisticsReferencesCommand) error
	Rotate(context.Context, persistence.RotateCommand) (persistence.AssetRecord, bool, error)
}

type tokenValidator interface {
	Validate(context.Context, string) (auth.Principal, error)
	ValidateService(context.Context, string) (auth.ServicePrincipal, error)
}

type objectStore interface {
	EnsureVersioning(context.Context) error
	SignedUploadPolicy(context.Context, string, string, string, int64, time.Duration) (storage.UploadPolicy, error)
	StatVersion(context.Context, string, string) (media.ObjectMetadata, error)
	GetVersion(context.Context, string, string) (io.ReadCloser, media.ObjectMetadata, error)
	SignedVersionDownloadURL(context.Context, string, string, time.Duration) (*url.URL, error)
}

var (
	errObjectMismatch    = errors.New("uploaded object mismatch")
	errStorageDependency = errors.New("storage dependency unavailable")
	errMediaNotReady     = errors.New("media is not ready")
	errOriginalMissing   = errors.New("original media is unavailable")
)

type Server struct {
	repository repository
	database   readiness
	auth       tokenValidator
	store      objectStore
	config     Configuration
	logger     *slog.Logger
	mux        *http.ServeMux
}

func NewServer(repository repository, database readiness, validator tokenValidator, store objectStore, configuration Configuration, logger *slog.Logger) (*Server, error) {
	if repository == nil || database == nil || validator == nil || store == nil || logger == nil {
		return nil, fmt.Errorf("media API dependencies are required")
	}
	if configuration.MaxUploadBytes <= 0 || configuration.UploadExpiry <= 0 || configuration.DownloadExpiry <= 0 || len(configuration.AllowedMIMETypes) == 0 {
		return nil, fmt.Errorf("media API limits and allowlist are required")
	}
	server := &Server{repository: repository, database: database, auth: validator, store: store, config: configuration, logger: logger, mux: http.NewServeMux()}
	server.routes()
	return server, nil
}

func (server *Server) Handler() http.Handler {
	return server.correlation(server.recover(server.mux))
}

func (server *Server) routes() {
	server.mux.HandleFunc("GET /health/live", server.live)
	server.mux.HandleFunc("GET /health/ready", server.ready)
	server.mux.HandleFunc("POST /api/media/v1/upload-sessions", server.createUpload)
	server.mux.HandleFunc("POST /api/media/v1/upload-sessions/{uploadSessionId}/complete", server.finalizeUpload)
	server.mux.HandleFunc("GET /api/media/v1/assets", server.listOwner)
	server.mux.HandleFunc("GET /api/media/v1/assets/{mediaId}/original", server.getOriginal)
	server.mux.HandleFunc("POST /api/media/v1/assets/{mediaId}/rotation", server.rotate)
	server.mux.HandleFunc("POST /api/internal/media/v1/logistics/references/validate", server.validateLogisticsReferences)
	server.mux.HandleFunc("/health/live", server.methodNotAllowed)
	server.mux.HandleFunc("/health/ready", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions/{uploadSessionId}/complete", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/original", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/rotation", server.methodNotAllowed)
	server.mux.HandleFunc("/api/internal/media/v1/logistics/references/validate", server.methodNotAllowed)
	server.mux.HandleFunc("/", server.notFound)
}

func (server *Server) methodNotAllowed(response http.ResponseWriter, request *http.Request) {
	server.problem(response, request, http.StatusMethodNotAllowed, "MEDIA_METHOD_NOT_ALLOWED", "HTTP method is not allowed")
}

func (server *Server) notFound(response http.ResponseWriter, request *http.Request) {
	server.problem(response, request, http.StatusNotFound, "MEDIA_NOT_FOUND", "Media resource was not found")
}

func (server *Server) live(response http.ResponseWriter, request *http.Request) {
	writeJSON(response, http.StatusOK, map[string]string{"status": "UP"})
}

func (server *Server) ready(response http.ResponseWriter, request *http.Request) {
	ctx, cancel := context.WithTimeout(request.Context(), 2*time.Second)
	defer cancel()
	if err := server.database.Ready(ctx); err != nil {
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_NOT_READY", "Service is not ready")
		return
	}
	if err := server.store.EnsureVersioning(ctx); err != nil {
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_NOT_READY", "Service is not ready")
		return
	}
	writeJSON(response, http.StatusOK, map[string]string{"status": "UP"})
}

type logisticsMediaReferenceRequest struct {
	MediaID    string `json:"mediaId"`
	Generation int    `json:"generation"`
}

type validateLogisticsReferencesRequest struct {
	OwnerType   string                           `json:"ownerType"`
	DocumentID  string                           `json:"documentId"`
	LineID      string                           `json:"lineId"`
	WarehouseID string                           `json:"warehouseId"`
	References  []logisticsMediaReferenceRequest `json:"references"`
}

type logisticsMediaReferenceResponse struct {
	MediaID    uuid.UUID `json:"mediaId"`
	Generation int       `json:"generation"`
}

type validateLogisticsReferencesResponse struct {
	OwnerType   string                            `json:"ownerType"`
	DocumentID  uuid.UUID                         `json:"documentId"`
	LineID      uuid.UUID                         `json:"lineId"`
	WarehouseID uuid.UUID                         `json:"warehouseId"`
	References  []logisticsMediaReferenceResponse `json:"references"`
}

// validateLogisticsReferences proves only the caller-supplied opaque
// mediaId/generation references. It never returns an object key, signed URL,
// filename, content type, status or any other media-policy detail.
func (server *Server) validateLogisticsReferences(response http.ResponseWriter, request *http.Request) {
	if !server.logisticsPrincipal(response, request) {
		return
	}
	var body validateLogisticsReferencesRequest
	if !server.decode(response, request, &body) {
		return
	}
	documentID, documentErr := uuid.Parse(body.DocumentID)
	lineID, lineErr := uuid.Parse(body.LineID)
	warehouseID, warehouseErr := uuid.Parse(body.WarehouseID)
	if documentErr != nil || lineErr != nil || warehouseErr != nil || !persistence.IsLogisticsOwnerType(body.OwnerType) ||
		len(body.References) < 1 || len(body.References) > 20 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_LOGISTICS_REFERENCE", "Invalid logistics media reference request")
		return
	}
	references := make([]persistence.ReadyMediaReference, 0, len(body.References))
	responseReferences := make([]logisticsMediaReferenceResponse, 0, len(body.References))
	seen := make(map[uuid.UUID]struct{}, len(body.References))
	for _, reference := range body.References {
		mediaID, err := uuid.Parse(reference.MediaID)
		if err != nil || reference.Generation <= 0 {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_LOGISTICS_REFERENCE", "Invalid logistics media reference request")
			return
		}
		if _, duplicate := seen[mediaID]; duplicate {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_LOGISTICS_REFERENCE", "Invalid logistics media reference request")
			return
		}
		seen[mediaID] = struct{}{}
		references = append(references, persistence.ReadyMediaReference{MediaID: mediaID, Generation: reference.Generation})
		responseReferences = append(responseReferences, logisticsMediaReferenceResponse{MediaID: mediaID, Generation: reference.Generation})
	}
	err := server.repository.ValidateLogisticsReferences(request.Context(), persistence.ValidateLogisticsReferencesCommand{
		OwnerType: body.OwnerType, OwnerID: persistence.LogisticsOwnerID(documentID, lineID),
		WarehouseID: warehouseID, References: references,
	})
	if errors.Is(err, persistence.ErrReferenceNotReady) {
		server.problem(response, request, http.StatusConflict, "MEDIA_REFERENCE_NOT_READY", "A media reference is not ready for this logistics owner")
		return
	}
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusOK, validateLogisticsReferencesResponse{
		OwnerType: body.OwnerType, DocumentID: documentID, LineID: lineID,
		WarehouseID: warehouseID, References: responseReferences,
	})
}

type createUploadRequest struct {
	OwnerType      string `json:"ownerType"`
	OwnerID        string `json:"ownerId"`
	WarehouseID    string `json:"warehouseId"`
	Context        string `json:"context"`
	FileName       string `json:"fileName"`
	ContentType    string `json:"contentType"`
	ContentLength  int64  `json:"contentLength"`
	ChecksumSHA256 string `json:"checksumSha256"`
	SortOrder      int64  `json:"sortOrder"`
}

func (server *Server) createUpload(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.principal(response, request)
	if !ok {
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	var body createUploadRequest
	if !server.decode(response, request, &body) {
		return
	}
	warehouseID, err := uuid.Parse(body.WarehouseID)
	if err != nil || body.OwnerType != persistence.OwnerTypeInventoryFinding || body.Context != persistence.ViewerContextInspection {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid upload request")
		return
	}
	ownerID, err := uuid.Parse(body.OwnerID)
	if err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_OWNER", "Invalid upload owner")
		return
	}
	body.OwnerID = ownerID.String()
	if !validFileName(body.FileName) {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_FILE_NAME", "Invalid file name")
		return
	}
	if err := principal.Require("rwms.write", warehouseID, auth.Edit); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	contentType := normalizeContentType(body.ContentType)
	if _, allowed := server.config.AllowedMIMETypes[contentType]; !allowed {
		server.problem(response, request, http.StatusUnsupportedMediaType, "MEDIA_UNSUPPORTED_TYPE", "Unsupported media type")
		return
	}
	if body.ContentLength <= 0 || body.ContentLength > server.config.MaxUploadBytes || !checksumPattern.MatchString(body.ChecksumSHA256) || strings.TrimSpace(body.FileName) == "" || len(body.FileName) > 512 || body.SortOrder < 0 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid upload request")
		return
	}
	kind, accepted := media.KindForContentType(contentType)
	if !accepted {
		server.problem(response, request, http.StatusUnsupportedMediaType, "MEDIA_UNSUPPORTED_TYPE", "Unsupported media type")
		return
	}
	fileName := strings.TrimSpace(body.FileName)
	mediaID := uuid.New()
	sessionID := uuid.New()
	extension := extensionForContentType(contentType)
	objectKey := media.IngressObjectKey(mediaID.String(), extension)
	fingerprint := requestFingerprint(map[string]any{
		"ownerType": body.OwnerType, "ownerId": body.OwnerID, "warehouseId": warehouseID,
		"context":  body.Context,
		"fileName": fileName, "contentType": contentType, "contentLength": body.ContentLength,
		"checksumSha256": body.ChecksumSHA256, "sortOrder": body.SortOrder,
	})
	asset, replayed, err := server.repository.CreateUpload(request.Context(), persistence.CreateUploadCommand{
		MediaID: mediaID, UploadSessionID: sessionID, SubjectID: principal.SubjectID,
		IdempotencyKey: idempotencyKey, RequestSHA256: fingerprint, OwnerType: body.OwnerType,
		OwnerID: body.OwnerID, WarehouseID: warehouseID, Kind: kind, FileName: fileName,
		ContentType: contentType, ContentLength: body.ContentLength, ChecksumSHA256: body.ChecksumSHA256,
		SortOrder: body.SortOrder, SourceObjectKey: objectKey,
		UploadExpiresAt: time.Now().UTC().Add(server.config.UploadExpiry), CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	policy, err := server.store.SignedUploadPolicy(request.Context(), asset.SourceObjectKey, asset.ContentType, asset.ExpectedChecksum, asset.ExpectedLength, time.Until(asset.UploadExpiresAt))
	if err != nil {
		server.logger.Error("sign constrained upload policy", "correlationId", correlationID(request.Context()), "error", safeError(err))
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, status, map[string]any{
		"uploadSessionId": asset.UploadSessionID, "mediaId": asset.ID,
		"expiresAt": asset.UploadExpiresAt, "uploadUrl": policy.URL.String(),
		"formFields": policy.Fields,
	})
}

type finalizeUploadRequest struct {
	ObjectVersionID string `json:"objectVersionId"`
	ETag            string `json:"etag"`
	ChecksumSHA256  string `json:"checksumSha256"`
}

func (server *Server) finalizeUpload(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.principal(response, request)
	if !ok {
		return
	}
	sessionID, err := uuid.Parse(request.PathValue("uploadSessionId"))
	if err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid upload session")
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	var body finalizeUploadRequest
	if !server.decode(response, request, &body) {
		return
	}
	objectVersionID := strings.TrimSpace(body.ObjectVersionID)
	etag := normalizeETag(body.ETag)
	if objectVersionID == "" || len(objectVersionID) > 255 || etag == "" || len(etag) > 255 ||
		!checksumPattern.MatchString(body.ChecksumSHA256) {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid finalize request")
		return
	}
	body.ObjectVersionID = objectVersionID
	body.ETag = etag
	asset, err := server.repository.UploadSessionForSubject(request.Context(), sessionID, principal.SubjectID)
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if err := principal.Require("rwms.write", asset.WarehouseID, auth.Edit); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	fingerprint := requestFingerprint(map[string]any{
		"uploadSessionId": sessionID, "objectVersionId": objectVersionID,
		"etag": etag, "checksumSha256": body.ChecksumSHA256,
	})
	if asset.UploadCompletedAt != nil {
		asset, replayed, err := server.repository.FinalizeUpload(request.Context(), persistence.FinalizeCommand{
			SessionID: sessionID, SubjectID: principal.SubjectID, IdempotencyKey: idempotencyKey,
			RequestSHA256: fingerprint, ObjectVersionID: objectVersionID,
			ETag: etag, ChecksumSHA256: body.ChecksumSHA256,
			ContentType: asset.ContentType, SizeBytes: asset.ExpectedLength, CorrelationID: correlationID(request.Context()),
		})
		if err != nil || !replayed {
			server.repositoryProblem(response, request, err)
			return
		}
		writeJSON(response, http.StatusOK, assetResponse(asset, nil))
		return
	}
	if !time.Now().Before(asset.UploadExpiresAt) {
		server.problem(response, request, http.StatusConflict, "MEDIA_UPLOAD_EXPIRED", "Upload session has expired")
		return
	}
	if asset.ExpectedChecksum != body.ChecksumSHA256 {
		server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
		return
	}
	metadata, err := server.verifyObject(request.Context(), asset, body)
	if err != nil {
		if errors.Is(err, errObjectMismatch) {
			server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
			return
		}
		server.logger.Error("verify immutable object", "correlationId", correlationID(request.Context()), "error", safeError(err))
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}
	asset, replayed, err := server.repository.FinalizeUpload(request.Context(), persistence.FinalizeCommand{
		SessionID: sessionID, SubjectID: principal.SubjectID, IdempotencyKey: idempotencyKey,
		RequestSHA256: fingerprint, ObjectVersionID: objectVersionID,
		ETag: etag, ChecksumSHA256: body.ChecksumSHA256,
		ContentType: metadata.ContentType, SizeBytes: metadata.SizeBytes, CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	status := http.StatusAccepted
	if replayed {
		status = http.StatusOK
	}
	writeJSON(response, status, assetResponse(asset, nil))
}

func (server *Server) verifyObject(ctx context.Context, asset persistence.AssetRecord, request finalizeUploadRequest) (media.ObjectMetadata, error) {
	metadata, err := server.store.StatVersion(ctx, asset.SourceObjectKey, request.ObjectVersionID)
	if err != nil {
		if errors.Is(err, storage.ErrObjectVersionMismatch) {
			return media.ObjectMetadata{}, fmt.Errorf("%w: stat immutable object", errObjectMismatch)
		}
		return media.ObjectMetadata{}, fmt.Errorf("%w: stat immutable object", errStorageDependency)
	}
	if metadata.SizeBytes != asset.ExpectedLength || metadata.SizeBytes > server.config.MaxUploadBytes || metadata.ContentType != asset.ContentType || normalizeETag(metadata.ETag) != normalizeETag(request.ETag) || metadata.VersionID != strings.TrimSpace(request.ObjectVersionID) {
		return media.ObjectMetadata{}, errObjectMismatch
	}
	object, readMetadata, err := server.store.GetVersion(ctx, asset.SourceObjectKey, request.ObjectVersionID)
	if err != nil {
		if errors.Is(err, storage.ErrObjectVersionMismatch) {
			return media.ObjectMetadata{}, fmt.Errorf("%w: read immutable object", errObjectMismatch)
		}
		return media.ObjectMetadata{}, fmt.Errorf("%w: read immutable object", errStorageDependency)
	}
	defer object.Close()
	hash := sha256.New()
	prefix := make([]byte, 512)
	read, readErr := io.ReadFull(object, prefix)
	if readErr != nil && !errors.Is(readErr, io.EOF) && !errors.Is(readErr, io.ErrUnexpectedEOF) {
		return media.ObjectMetadata{}, fmt.Errorf("%w: read immutable object prefix", errStorageDependency)
	}
	prefix = prefix[:read]
	_, _ = hash.Write(prefix)
	written, err := io.Copy(hash, io.LimitReader(object, server.config.MaxUploadBytes-int64(read)+1))
	if err != nil {
		return media.ObjectMetadata{}, fmt.Errorf("%w: read immutable object body", errStorageDependency)
	}
	if int64(read)+written != readMetadata.SizeBytes || int64(read)+written > server.config.MaxUploadBytes {
		return media.ObjectMetadata{}, errObjectMismatch
	}
	checksum := hex.EncodeToString(hash.Sum(nil))
	if checksum != strings.ToLower(request.ChecksumSHA256) || checksum != asset.ExpectedChecksum {
		return media.ObjectMetadata{}, errObjectMismatch
	}
	sniffed := normalizeContentType(http.DetectContentType(prefix))
	if !sniffMatches(asset.ContentType, sniffed, prefix) {
		return media.ObjectMetadata{}, errObjectMismatch
	}
	primary := strings.ToLower(strings.TrimSpace(readMetadata.UserMetadata["x-amz-meta-sha256"]))
	fallback := strings.ToLower(strings.TrimSpace(readMetadata.UserMetadata["sha256"]))
	if primary == "" {
		primary = fallback
	}
	if primary == "" || primary != checksum || (fallback != "" && fallback != checksum) {
		return media.ObjectMetadata{}, errObjectMismatch
	}
	return metadata, nil
}

func (server *Server) listOwner(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.principal(response, request)
	if !ok {
		return
	}
	query := request.URL.Query()
	if query.Get("ownerType") != persistence.OwnerTypeInventoryFinding || query.Get("context") != persistence.ViewerContextInspection {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_CONTEXT", "Invalid owner context")
		return
	}
	ownerID, err := uuid.Parse(query.Get("ownerId"))
	if err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_OWNER", "Invalid upload owner")
		return
	}
	warehouseID, err := uuid.Parse(query.Get("warehouseId"))
	if err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid warehouse")
		return
	}
	if err := principal.Require("rwms.read", warehouseID, auth.View); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	limit := 50
	if raw := query.Get("limit"); raw != "" {
		limit, err = strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > 100 {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_PAGE", "Invalid page limit")
			return
		}
	}
	var after *uuid.UUID
	if raw := query.Get("cursor"); raw != "" {
		parsed, parseErr := decodeCursor(raw)
		if parseErr != nil {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_PAGE", "Invalid page cursor")
			return
		}
		after = &parsed
	}
	var assets []persistence.AssetRecord
	items := make([]any, 0, limit)
	err = server.repository.ReadOwnerAssets(request.Context(), query.Get("ownerType"),
		ownerID.String(), warehouseID, limit, after,
		func(records []persistence.AssetWithVariants) error {
			assets = make([]persistence.AssetRecord, len(records))
			for index := range records {
				assets[index] = records[index].Asset
				variants, variantErr := server.safeVariants(request.Context(), records[index])
				if variantErr != nil {
					return variantErr
				}
				items = append(items, assetResponse(records[index].Asset, variants))
			}
			return nil
		})
	if err != nil {
		if errors.Is(err, errStorageDependency) {
			server.problem(response, request, http.StatusServiceUnavailable,
				"MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
			return
		}
		server.repositoryProblem(response, request, err)
		return
	}
	var next any
	if len(assets) == limit {
		next = encodeCursor(assets[len(assets)-1].ID)
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusOK, map[string]any{"items": items, "next": next})
}

func (server *Server) getOriginal(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.principal(response, request)
	if !ok {
		return
	}
	ownerType, ownerID, warehouseID, ok := server.ownerScope(response, request)
	if !ok {
		return
	}
	mediaID, err := uuid.Parse(request.PathValue("mediaId"))
	if err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid media ID")
		return
	}
	if err := principal.Require("rwms.read", warehouseID, auth.View); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	var signedURL string
	err = server.repository.ReadOriginal(request.Context(), mediaID, ownerType, ownerID,
		warehouseID, func(asset persistence.AssetRecord, original *persistence.VariantRecord) error {
			if asset.Status != media.StatusReady || asset.Generation <= 0 {
				return errMediaNotReady
			}
			if original == nil || original.Variant != media.VariantOriginal ||
				original.ObjectVersionID == "" {
				return errOriginalMissing
			}
			downloadURL, signErr := server.store.SignedVersionDownloadURL(request.Context(),
				original.ObjectKey, original.ObjectVersionID, server.config.DownloadExpiry)
			if signErr != nil {
				return errStorageDependency
			}
			signedURL = downloadURL.String()
			return nil
		})
	switch {
	case errors.Is(err, errMediaNotReady):
		server.problem(response, request, http.StatusConflict, "MEDIA_NOT_READY", "Media is not ready")
	case errors.Is(err, errOriginalMissing), errors.Is(err, errStorageDependency):
		server.problem(response, request, http.StatusServiceUnavailable,
			"MEDIA_ORIGINAL_UNAVAILABLE", "Original media is unavailable")
	case err != nil:
		server.repositoryProblem(response, request, err)
	default:
		response.Header().Set("Cache-Control", "no-store")
		writeJSON(response, http.StatusOK, map[string]any{"url": signedURL,
			"expiresAt": time.Now().UTC().Add(server.config.DownloadExpiry)})
	}
}

type rotationRequest struct {
	RotationDegrees int16 `json:"rotationDegrees"`
	ExpectedVersion int64 `json:"expectedVersion"`
}

func (server *Server) rotate(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.principal(response, request)
	if !ok {
		return
	}
	ownerType, ownerID, warehouseID, ok := server.ownerScope(response, request)
	if !ok {
		return
	}
	mediaID, err := uuid.Parse(request.PathValue("mediaId"))
	if err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid media ID")
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	var body rotationRequest
	if !server.decode(response, request, &body) {
		return
	}
	rotation, err := media.ParseRotation(body.RotationDegrees)
	if err != nil || body.ExpectedVersion <= 0 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid rotation request")
		return
	}
	asset, err := server.repository.GetAssetScoped(request.Context(), mediaID, ownerType, ownerID, warehouseID)
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if err := principal.Require("rwms.write", asset.WarehouseID, auth.Edit); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	asset, replayed, err := server.repository.Rotate(request.Context(), persistence.RotateCommand{
		MediaID: mediaID, SubjectID: principal.SubjectID, IdempotencyKey: idempotencyKey,
		RequestSHA256: requestFingerprint(map[string]any{"mediaId": mediaID, "rotationDegrees": rotation, "expectedVersion": body.ExpectedVersion}), ExpectedVersion: body.ExpectedVersion,
		Rotation: rotation, CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	status := http.StatusAccepted
	if replayed {
		status = http.StatusOK
	}
	writeJSON(response, status, assetResponse(asset, nil))
}

func (server *Server) safeVariants(ctx context.Context, record persistence.AssetWithVariants) ([]any, error) {
	if record.Asset.Status != media.StatusReady || record.Asset.Generation <= 0 {
		return []any{}, nil
	}
	result := make([]any, 0, len(record.Variants))
	for _, variant := range record.Variants {
		if variant.Variant == media.VariantOriginal || variant.ObjectVersionID == "" {
			continue
		}
		url, err := server.store.SignedVersionDownloadURL(ctx, variant.ObjectKey, variant.ObjectVersionID, server.config.DownloadExpiry)
		if err != nil {
			return nil, errStorageDependency
		}
		result = append(result, map[string]any{"kind": variant.Variant, "contentType": variant.ContentType,
			"url": url.String(), "width": variant.Width, "height": variant.Height})
	}
	return result, nil
}

func assetResponse(asset persistence.AssetRecord, variants []any) map[string]any {
	if variants == nil {
		variants = []any{}
	}
	return map[string]any{
		"id": asset.ID, "fileName": asset.FileName, "contentType": asset.ContentType,
		"kind": asset.Kind, "status": asset.Status, "version": asset.Version,
		"generation": asset.Generation, "rotationDegrees": asset.Rotation,
		"sortOrder": asset.SortOrder, "sizeBytes": asset.SizeBytes, "createdAt": asset.CreatedAt,
		"variants": variants,
	}
}

func (server *Server) principal(response http.ResponseWriter, request *http.Request) (auth.Principal, bool) {
	principal, err := server.auth.Validate(request.Context(), request.Header.Get("Authorization"))
	if err != nil {
		status := http.StatusUnauthorized
		code := "MEDIA_UNAUTHORIZED"
		if errors.Is(err, auth.ErrForbidden) {
			status, code = http.StatusForbidden, "MEDIA_FORBIDDEN"
		}
		server.problem(response, request, status, code, "Access is denied")
		return auth.Principal{}, false
	}
	return principal, true
}

func (server *Server) logisticsPrincipal(response http.ResponseWriter, request *http.Request) bool {
	principal, err := server.auth.ValidateService(request.Context(), request.Header.Get("Authorization"))
	if err == nil {
		err = principal.RequireExact("logistics-service", "media.logistics")
	}
	if err == nil {
		return true
	}
	status := http.StatusUnauthorized
	code := "MEDIA_UNAUTHORIZED"
	if errors.Is(err, auth.ErrForbidden) {
		status, code = http.StatusForbidden, "MEDIA_FORBIDDEN"
	}
	server.problem(response, request, status, code, "Access is denied")
	return false
}

func (server *Server) decode(response http.ResponseWriter, request *http.Request, target any) bool {
	request.Body = http.MaxBytesReader(response, request.Body, 64<<10)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_JSON", "Invalid JSON request")
		return false
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_JSON", "Invalid JSON request")
		return false
	}
	return true
}

func (server *Server) repositoryProblem(response http.ResponseWriter, request *http.Request, err error) {
	switch {
	case errors.Is(err, persistence.ErrNotFound):
		server.problem(response, request, http.StatusNotFound, "MEDIA_NOT_FOUND", "Media resource was not found")
	case errors.Is(err, persistence.ErrOwnerProofMissing):
		server.problem(response, request, http.StatusForbidden, "MEDIA_OWNER_PROOF_REQUIRED", "Owner authorization proof is unavailable")
	case errors.Is(err, persistence.ErrConflict), errors.Is(err, persistence.ErrIdempotencyMismatch):
		server.problem(response, request, http.StatusConflict, "MEDIA_CONFLICT", "Media command conflicts with current state")
	default:
		server.logger.Error("media repository operation", "correlationId", correlationID(request.Context()), "error", safeError(err))
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_DEPENDENCY_UNAVAILABLE", "A required dependency is unavailable")
	}
}

func (server *Server) problem(response http.ResponseWriter, request *http.Request, status int, code, detail string) {
	response.Header().Set("Content-Type", "application/problem+json")
	response.Header().Set(correlationHeader, correlationID(request.Context()).String())
	response.WriteHeader(status)
	_ = json.NewEncoder(response).Encode(map[string]any{
		"type":  "https://rwms.local/problems/" + strings.ToLower(strings.ReplaceAll(code, "_", "-")),
		"title": http.StatusText(status), "status": status, "detail": detail,
		"instance": request.URL.Path, "code": code, "correlationId": correlationID(request.Context()),
	})
}

func (server *Server) correlation(next http.Handler) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		id, err := uuid.Parse(request.Header.Get(correlationHeader))
		if err != nil {
			id = uuid.New()
		}
		response.Header().Set(correlationHeader, id.String())
		next.ServeHTTP(response, request.WithContext(context.WithValue(request.Context(), correlationKey{}, id)))
	})
}

func (server *Server) recover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				server.logger.Error("media API panic", "correlationId", correlationID(request.Context()))
				server.problem(response, request, http.StatusInternalServerError, "MEDIA_INTERNAL_ERROR", "Internal server error")
			}
		}()
		next.ServeHTTP(response, request)
	})
}

type correlationKey struct{}

func correlationID(ctx context.Context) uuid.UUID {
	if value, ok := ctx.Value(correlationKey{}).(uuid.UUID); ok {
		return value
	}
	return uuid.Nil
}

func requireUUIDHeader(response http.ResponseWriter, request *http.Request, name string, server *Server) (uuid.UUID, bool) {
	value, err := uuid.Parse(request.Header.Get(name))
	if err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_IDEMPOTENCY_KEY", "A UUID Idempotency-Key is required")
		return uuid.Nil, false
	}
	return value, true
}

func requestFingerprint(value any) string {
	body, _ := json.Marshal(value)
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

func normalizeContentType(value string) string {
	parsed, _, err := mime.ParseMediaType(value)
	if err != nil {
		return ""
	}
	return strings.ToLower(parsed)
}

func extensionForContentType(contentType string) string {
	return map[string]string{"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "video/mp4": ".mp4", "video/webm": ".webm"}[contentType]
}

func sniffMatches(expected, sniffed string, prefix []byte) bool {
	if expected == sniffed {
		return true
	}
	if expected == "video/mp4" && len(prefix) >= 12 && string(prefix[4:8]) == "ftyp" {
		return true
	}
	if expected == "video/webm" && len(prefix) >= 4 && prefix[0] == 0x1a && prefix[1] == 0x45 && prefix[2] == 0xdf && prefix[3] == 0xa3 {
		return true
	}
	return false
}

func writeJSON(response http.ResponseWriter, status int, value any) {
	response.Header().Set("Content-Type", "application/json")
	response.WriteHeader(status)
	_ = json.NewEncoder(response).Encode(value)
}

func (server *Server) ownerScope(response http.ResponseWriter, request *http.Request) (string, string, uuid.UUID, bool) {
	query := request.URL.Query()
	ownerType, ownerID := query.Get("ownerType"), query.Get("ownerId")
	warehouseID, warehouseErr := uuid.Parse(query.Get("warehouseId"))
	parsedOwnerID, ownerErr := uuid.Parse(ownerID)
	if ownerType != persistence.OwnerTypeInventoryFinding || query.Get("context") != persistence.ViewerContextInspection || ownerErr != nil || warehouseErr != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_CONTEXT", "Invalid owner context")
		return "", "", uuid.Nil, false
	}
	return ownerType, parsedOwnerID.String(), warehouseID, true
}

func validFileName(value string) bool {
	value = strings.TrimSpace(value)
	if value == "" || value == "." || value == ".." || len(value) > 512 || strings.ContainsAny(value, "/\\") {
		return false
	}
	for _, character := range value {
		if character < 0x20 || character == 0x7f {
			return false
		}
	}
	return true
}

func normalizeETag(value string) string {
	return strings.ToLower(strings.TrimSpace(strings.Trim(value, "\"")))
}

func encodeCursor(id uuid.UUID) string {
	return base64.RawURLEncoding.EncodeToString([]byte(id.String()))
}

func decodeCursor(value string) (uuid.UUID, error) {
	if len(value) > 64 {
		return uuid.Nil, errors.New("cursor too long")
	}
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(decoded) != 36 {
		return uuid.Nil, errors.New("invalid cursor")
	}
	return uuid.Parse(string(decoded))
}

func safeError(err error) string {
	if err == nil {
		return ""
	}
	return fmt.Sprintf("%T", err)
}
