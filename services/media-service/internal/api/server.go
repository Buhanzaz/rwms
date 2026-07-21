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
	"path/filepath"
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
}

type readiness interface {
	Ready(context.Context) error
}

type repository interface {
	CreateUpload(context.Context, persistence.CreateUploadCommand) (persistence.AssetRecord, bool, error)
	AcquireUploadSessionContentLock(context.Context, uuid.UUID) (func() error, error)
	UploadSessionForSubject(context.Context, uuid.UUID, uuid.UUID) (persistence.AssetRecord, error)
	FinalizeUpload(context.Context, persistence.FinalizeCommand) (persistence.AssetRecord, bool, error)
	ReadOwnerAssets(context.Context, string, string, uuid.UUID, int, *uuid.UUID,
		func([]persistence.AssetWithVariants) error) error
	ReadCabinCovers(context.Context, uuid.UUID, []uuid.UUID,
		func([]persistence.CabinCoverRecord) error) error
	ReadOriginal(context.Context, uuid.UUID, string, string, uuid.UUID,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	ReadCurrentVariant(context.Context, uuid.UUID, string, string, uuid.UUID, int, media.Variant,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	GetAssetScoped(context.Context, uuid.UUID, string, string, uuid.UUID) (persistence.AssetRecord, error)
	UpsertServiceOwnerProof(context.Context, persistence.ServiceOwnerProofCommand) (persistence.ServiceOwnerProofRecord, bool, error)
	ValidateLogisticsReferences(context.Context, persistence.ValidateLogisticsReferencesCommand) error
	Rotate(context.Context, persistence.RotateCommand) (persistence.AssetRecord, bool, error)
	Delete(context.Context, persistence.DeleteCommand) (persistence.AssetRecord, bool, error)
}

type tokenValidator interface {
	Validate(context.Context, string) (auth.Principal, error)
	ValidateService(context.Context, string) (auth.ServicePrincipal, error)
}

type objectStore interface {
	EnsureVersioning(context.Context) error
	PutIngressVersion(context.Context, string, io.Reader, int64, string, string) (media.ObjectMetadata, error)
	StatVersion(context.Context, string, string) (media.ObjectMetadata, error)
	GetVersion(context.Context, string, string) (io.ReadCloser, media.ObjectMetadata, error)
}

var (
	errObjectMismatch    = errors.New("uploaded object mismatch")
	errUploadBodyLength  = errors.New("upload body length mismatch")
	errStorageDependency = errors.New("storage dependency unavailable")
	errMediaNotReady     = errors.New("media is not ready")
	errOriginalMissing   = errors.New("original media is unavailable")
)

type boundedUploadReader struct {
	source    io.Reader
	remaining int64
	underflow bool
}

func (reader *boundedUploadReader) Read(buffer []byte) (int, error) {
	if len(buffer) == 0 {
		return 0, nil
	}
	if reader.remaining == 0 {
		return 0, io.EOF
	}
	if int64(len(buffer)) > reader.remaining {
		buffer = buffer[:reader.remaining]
	}
	read, err := reader.source.Read(buffer)
	reader.remaining -= int64(read)
	if errors.Is(err, io.EOF) && reader.remaining > 0 {
		reader.underflow = true
		return read, errUploadBodyLength
	}
	return read, err
}

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
	if configuration.MaxUploadBytes <= 0 || configuration.UploadExpiry <= 0 || len(configuration.AllowedMIMETypes) == 0 {
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
	server.mux.HandleFunc("PUT /api/media/v1/upload-sessions/{uploadSessionId}/content", server.uploadSessionContent)
	server.mux.HandleFunc("POST /api/media/v1/upload-sessions/{uploadSessionId}/complete", server.finalizeUpload)
	server.mux.HandleFunc("GET /api/media/v1/assets", server.listOwner)
	server.mux.HandleFunc("POST /api/media/v1/cabin-covers", server.listCabinCovers)
	server.mux.HandleFunc("GET /api/media/v1/assets/{mediaId}/original", server.getOriginal)
	server.mux.HandleFunc("GET /api/media/v1/assets/{mediaId}/variants/{variant}/content", server.getVariantContent)
	server.mux.HandleFunc("POST /api/media/v1/assets/{mediaId}/rotation", server.rotate)
	server.mux.HandleFunc("POST /api/media/v1/assets/{mediaId}/deletion", server.deleteAsset)
	server.mux.HandleFunc("POST /api/internal/media/v1/owner-proofs", server.upsertOwnerProof)
	server.mux.HandleFunc("POST /api/internal/media/v1/logistics/references/validate", server.validateLogisticsReferences)
	server.mux.HandleFunc("/health/live", server.methodNotAllowed)
	server.mux.HandleFunc("/health/ready", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions/{uploadSessionId}/content", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions/{uploadSessionId}/complete", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/cabin-covers", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/original", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/variants/{variant}/content", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/rotation", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/deletion", server.methodNotAllowed)
	server.mux.HandleFunc("/api/internal/media/v1/owner-proofs", server.methodNotAllowed)
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

type serviceOwnerProofRequest struct {
	OwnerType        string `json:"ownerType"`
	OwnerID          string `json:"ownerId"`
	DocumentID       string `json:"documentId"`
	LineID           string `json:"lineId"`
	WarehouseID      string `json:"warehouseId"`
	OwnerRevision    *int64 `json:"ownerRevision"`
	AggregateVersion *int64 `json:"aggregateVersion"`
	ProofEventID     string `json:"proofEventId"`
	Active           *bool  `json:"active"`
}

func (server *Server) upsertOwnerProof(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.servicePrincipal(response, request)
	if !ok {
		return
	}
	var body serviceOwnerProofRequest
	if !server.decode(response, request, &body) {
		return
	}
	definition, validType := persistence.ServiceOwnerScope(body.OwnerType)
	if !validType || principal.RequireExact(definition.SourceService, definition.ServiceScope) != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	warehouseID, warehouseErr := uuid.Parse(body.WarehouseID)
	proofEventID, eventErr := uuid.Parse(body.ProofEventID)
	if warehouseErr != nil || eventErr != nil || warehouseID == uuid.Nil || proofEventID == uuid.Nil ||
		body.OwnerRevision == nil || *body.OwnerRevision < 0 || body.AggregateVersion == nil ||
		*body.AggregateVersion < 0 || body.Active == nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_OWNER_PROOF", "Invalid owner proof")
		return
	}
	command := persistence.ServiceOwnerProofCommand{
		SourceService: definition.SourceService, OwnerType: body.OwnerType,
		WarehouseID: warehouseID, OwnerRevision: *body.OwnerRevision,
		AggregateVersion: *body.AggregateVersion, ProofEventID: proofEventID,
		Active: *body.Active,
	}
	if definition.Structured {
		documentID, documentErr := uuid.Parse(body.DocumentID)
		lineID, lineErr := uuid.Parse(body.LineID)
		if body.OwnerID != "" || documentErr != nil || lineErr != nil ||
			documentID == uuid.Nil || lineID == uuid.Nil {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_OWNER_PROOF", "Invalid owner proof")
			return
		}
		command.DocumentID = documentID
		command.LineID = lineID
	} else {
		ownerID, ownerErr := uuid.Parse(body.OwnerID)
		if body.DocumentID != "" || body.LineID != "" || ownerErr != nil || ownerID == uuid.Nil {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_OWNER_PROOF", "Invalid owner proof")
			return
		}
		command.OwnerID = ownerID
	}
	record, replayed, err := server.repository.UpsertServiceOwnerProof(request.Context(), command)
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	result := map[string]any{
		"ownerType": record.OwnerType, "warehouseId": record.WarehouseID,
		"ownerRevision": record.OwnerRevision, "aggregateVersion": record.AggregateVersion,
		"proofEventId": record.ProofEventID, "active": record.Active,
	}
	if definition.Structured {
		result["documentId"] = record.DocumentID
		result["lineId"] = record.LineID
	} else {
		result["ownerId"] = record.OwnerID
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, status, result)
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
	DocumentID     string `json:"documentId"`
	LineID         string `json:"lineId"`
	WarehouseID    string `json:"warehouseId"`
	Context        string `json:"context"`
	FolderID       string `json:"folderId"`
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
	if err != nil || !validPublicOwnerScope(body.OwnerType, body.Context) {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid upload request")
		return
	}
	ownerID, ok := resolvePublicOwnerID(body.OwnerType, body.OwnerID, body.DocumentID, body.LineID)
	if !ok {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_OWNER", "Invalid upload owner")
		return
	}
	if persistence.IsLogisticsOwnerType(body.OwnerType) {
		documentID, lineID, _ := persistence.LogisticsOwnerParts(ownerID)
		body.DocumentID, body.LineID = documentID.String(), lineID.String()
	} else {
		body.OwnerID = ownerID
	}
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
	folderID := mediaID
	if body.FolderID != "" {
		folderID, err = uuid.Parse(body.FolderID)
		if err != nil {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid photo folder")
			return
		}
		body.FolderID = folderID.String()
	}
	sessionID := uuid.New()
	extension := extensionForContentType(contentType)
	objectKey := media.IngressObjectKey(mediaID.String(), extension)
	fingerprint := requestFingerprint(map[string]any{
		"ownerType": body.OwnerType, "ownerId": body.OwnerID, "documentId": body.DocumentID,
		"lineId": body.LineID, "warehouseId": warehouseID,
		"context": body.Context, "folderId": body.FolderID,
		"fileName": fileName, "contentType": contentType, "contentLength": body.ContentLength,
		"checksumSha256": body.ChecksumSHA256, "sortOrder": body.SortOrder,
	})
	asset, replayed, err := server.repository.CreateUpload(request.Context(), persistence.CreateUploadCommand{
		MediaID: mediaID, FolderID: folderID, UploadSessionID: sessionID, SubjectID: principal.SubjectID,
		IdempotencyKey: idempotencyKey, RequestSHA256: fingerprint, OwnerType: body.OwnerType,
		OwnerID: ownerID, WarehouseID: warehouseID, Kind: kind, FileName: fileName,
		ContentType: contentType, ContentLength: body.ContentLength, ChecksumSHA256: body.ChecksumSHA256,
		SortOrder: body.SortOrder, SourceObjectKey: objectKey,
		UploadExpiresAt: time.Now().UTC().Add(server.config.UploadExpiry), CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, status, map[string]any{
		"uploadSessionId": asset.UploadSessionID, "mediaId": asset.ID,
		"expiresAt":        asset.UploadExpiresAt,
		"contentUploadUrl": "/api/media/v1/upload-sessions/" + asset.UploadSessionID.String() + "/content",
	})
}

type uploadedObjectResponse struct {
	ObjectVersionID string `json:"objectVersionId"`
	ETag            string `json:"etag"`
	ChecksumSHA256  string `json:"checksumSha256"`
}

// uploadSessionContent is the only browser byte-ingress path. It streams the
// exact authorized body to private MinIO and commits finalization before
// acknowledging the upload, so a session cannot accept a second production
// object. The regular completion endpoint then provides an exact idempotent
// confirmation using the same key and immutable object metadata.
func (server *Server) uploadSessionContent(response http.ResponseWriter, request *http.Request) {
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
	release, err := server.repository.AcquireUploadSessionContentLock(request.Context(), sessionID)
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	defer func() {
		if err := release(); err != nil {
			server.logger.Error("release upload session content lock",
				"correlationId", correlationID(request.Context()), "error", safeError(err))
		}
	}()

	asset, err := server.repository.UploadSessionForSubject(request.Context(), sessionID, principal.SubjectID)
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if err := principal.Require("rwms.write", asset.WarehouseID, auth.Edit); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}

	if asset.UploadCompletedAt != nil {
		server.confirmContentReplay(response, request, asset, sessionID, principal.SubjectID, idempotencyKey)
		return
	}
	if !time.Now().Before(asset.UploadExpiresAt) {
		server.problem(response, request, http.StatusConflict, "MEDIA_UPLOAD_EXPIRED", "Upload session has expired")
		return
	}
	contentType := normalizeContentType(request.Header.Get("Content-Type"))
	knownLengthMismatch := request.ContentLength != -1 &&
		(request.ContentLength <= 0 || request.ContentLength != asset.ExpectedLength || request.ContentLength > server.config.MaxUploadBytes)
	if contentType != asset.ContentType || asset.ExpectedLength <= 0 || asset.ExpectedLength > server.config.MaxUploadBytes || knownLengthMismatch {
		server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
		return
	}

	hash := sha256.New()
	bounded := &boundedUploadReader{source: request.Body, remaining: asset.ExpectedLength}
	source := io.TeeReader(bounded, hash)
	metadata, err := server.store.PutIngressVersion(
		request.Context(), asset.SourceObjectKey, source, asset.ExpectedLength,
		asset.ContentType, asset.ExpectedChecksum,
	)
	if err != nil {
		if bounded.underflow || errors.Is(err, errUploadBodyLength) {
			server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
			return
		}
		server.logger.Error("stream immutable ingress object", "correlationId", correlationID(request.Context()), "error", safeError(err))
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}
	var trailing [1]byte
	trailingBytes, trailingErr := request.Body.Read(trailing[:])
	if trailingErr != nil && !errors.Is(trailingErr, io.EOF) {
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}
	checksum := hex.EncodeToString(hash.Sum(nil))
	if bounded.remaining != 0 || trailingBytes != 0 || checksum != asset.ExpectedChecksum || metadata.SizeBytes != asset.ExpectedLength ||
		normalizeContentType(metadata.ContentType) != asset.ContentType || metadata.VersionID == "" || normalizeETag(metadata.ETag) == "" {
		server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
		return
	}
	finalizeRequest := finalizeUploadRequest{
		ObjectVersionID: metadata.VersionID,
		ETag:            normalizeETag(metadata.ETag),
		ChecksumSHA256:  checksum,
	}
	verified, err := server.verifyObject(request.Context(), asset, finalizeRequest)
	if err != nil {
		if errors.Is(err, errObjectMismatch) {
			server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
			return
		}
		server.logger.Error("verify immutable ingress object", "correlationId", correlationID(request.Context()), "error", safeError(err))
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}
	fingerprint := finalizeFingerprint(sessionID, finalizeRequest)
	asset, replayed, err := server.repository.FinalizeUpload(request.Context(), persistence.FinalizeCommand{
		SessionID: sessionID, SubjectID: principal.SubjectID, IdempotencyKey: idempotencyKey,
		RequestSHA256: fingerprint, ObjectVersionID: finalizeRequest.ObjectVersionID,
		ETag: finalizeRequest.ETag, ChecksumSHA256: finalizeRequest.ChecksumSHA256,
		ContentType: verified.ContentType, SizeBytes: verified.SizeBytes, CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, status, uploadedObjectResponse{
		ObjectVersionID: asset.SourceVersionID,
		ETag:            asset.SourceETag,
		ChecksumSHA256:  asset.SourceChecksum,
	})
}

func (server *Server) confirmContentReplay(
	response http.ResponseWriter,
	request *http.Request,
	asset persistence.AssetRecord,
	sessionID, subjectID, idempotencyKey uuid.UUID,
) {
	finalizeRequest := finalizeUploadRequest{
		ObjectVersionID: asset.SourceVersionID,
		ETag:            asset.SourceETag,
		ChecksumSHA256:  asset.SourceChecksum,
	}
	asset, replayed, err := server.repository.FinalizeUpload(request.Context(), persistence.FinalizeCommand{
		SessionID: sessionID, SubjectID: subjectID, IdempotencyKey: idempotencyKey,
		RequestSHA256:   finalizeFingerprint(sessionID, finalizeRequest),
		ObjectVersionID: finalizeRequest.ObjectVersionID, ETag: finalizeRequest.ETag,
		ChecksumSHA256: finalizeRequest.ChecksumSHA256, ContentType: asset.ContentType,
		SizeBytes: asset.ExpectedLength, CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !replayed {
		server.problem(response, request, http.StatusConflict, "MEDIA_CONFLICT", "Media command conflicts with current state")
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusOK, uploadedObjectResponse{
		ObjectVersionID: asset.SourceVersionID,
		ETag:            asset.SourceETag,
		ChecksumSHA256:  asset.SourceChecksum,
	})
}

type finalizeUploadRequest struct {
	ObjectVersionID string `json:"objectVersionId"`
	ETag            string `json:"etag"`
	ChecksumSHA256  string `json:"checksumSha256"`
}

func finalizeFingerprint(sessionID uuid.UUID, body finalizeUploadRequest) string {
	return requestFingerprint(map[string]any{
		"uploadSessionId": sessionID, "objectVersionId": body.ObjectVersionID,
		"etag": body.ETag, "checksumSha256": body.ChecksumSHA256,
	})
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
	fingerprint := finalizeFingerprint(sessionID, body)
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
	ownerType, ownerID, warehouseID, ok := server.ownerScope(response, request)
	if !ok {
		return
	}
	if err := principal.Require("rwms.read", warehouseID, auth.View); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	limit := 50
	var err error
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
	err = server.repository.ReadOwnerAssets(request.Context(), ownerType,
		ownerID, warehouseID, limit, after,
		func(records []persistence.AssetWithVariants) error {
			assets = make([]persistence.AssetRecord, len(records))
			for index := range records {
				assets[index] = records[index].Asset
				variants := safeVariants(records[index], ownerType, ownerID, warehouseID)
				items = append(items, assetResponse(records[index].Asset, variants))
			}
			return nil
		})
	if err != nil {
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

type cabinCoversRequest struct {
	WarehouseID string   `json:"warehouseId"`
	CabinIDs    []string `json:"cabinIds"`
}

func (server *Server) listCabinCovers(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.principal(response, request)
	if !ok {
		return
	}
	var body cabinCoversRequest
	if !server.decode(response, request, &body) {
		return
	}
	warehouseID, err := uuid.Parse(body.WarehouseID)
	if err != nil || len(body.CabinIDs) < 1 || len(body.CabinIDs) > 200 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid cabin cover request")
		return
	}
	if err := principal.Require("rwms.read", warehouseID, auth.View); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	cabinIDs := make([]uuid.UUID, 0, len(body.CabinIDs))
	seen := make(map[uuid.UUID]struct{}, len(body.CabinIDs))
	for _, value := range body.CabinIDs {
		cabinID, parseErr := uuid.Parse(value)
		if parseErr != nil || cabinID == uuid.Nil {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid cabin cover request")
			return
		}
		if _, duplicate := seen[cabinID]; duplicate {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid cabin cover request")
			return
		}
		seen[cabinID] = struct{}{}
		cabinIDs = append(cabinIDs, cabinID)
	}
	items := make([]any, 0, len(cabinIDs))
	err = server.repository.ReadCabinCovers(request.Context(), warehouseID, cabinIDs,
		func(records []persistence.CabinCoverRecord) error {
			for _, record := range records {
				previews := make([]any, 0, len(record.Previews))
				for _, preview := range record.Previews {
					previews = append(previews, publicVariantResponse(preview.MediaID,
						preview.Generation, preview.Variant, persistence.OwnerTypeCabin,
						record.CabinID.String(), warehouseID))
				}
				var cover any
				if len(previews) > 0 {
					cover = previews[0]
				}
				items = append(items, map[string]any{
					"cabinId": record.CabinID, "photoCount": record.PhotoCount,
					"cover": cover, "previews": previews,
				})
			}
			return nil
		})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusOK, map[string]any{"items": items})
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
	var selectedAsset persistence.AssetRecord
	var selectedOriginal *persistence.VariantRecord
	err = server.repository.ReadOriginal(request.Context(), mediaID, ownerType, ownerID,
		warehouseID, func(asset persistence.AssetRecord, original *persistence.VariantRecord) error {
			if asset.Status != media.StatusReady || asset.Generation <= 0 {
				return errMediaNotReady
			}
			if original == nil || original.Variant != media.VariantOriginal ||
				original.ObjectVersionID == "" {
				return errOriginalMissing
			}
			selectedAsset = asset
			copyOfOriginal := *original
			selectedOriginal = &copyOfOriginal
			return nil
		})
	switch {
	case errors.Is(err, errMediaNotReady):
		server.problem(response, request, http.StatusConflict, "MEDIA_NOT_READY", "Media is not ready")
	case errors.Is(err, errOriginalMissing):
		server.problem(response, request, http.StatusServiceUnavailable,
			"MEDIA_ORIGINAL_UNAVAILABLE", "Original media is unavailable")
	case err != nil:
		server.repositoryProblem(response, request, err)
	default:
		server.streamVariant(response, request, selectedAsset.FileName, *selectedOriginal)
	}
}

func (server *Server) getVariantContent(response http.ResponseWriter, request *http.Request) {
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
	variant, ok := publicDerivedVariant(request.PathValue("variant"))
	if !ok {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid media variant")
		return
	}
	generation, err := strconv.Atoi(request.URL.Query().Get("generation"))
	if err != nil || generation <= 0 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid media generation")
		return
	}
	if err := principal.Require("rwms.read", warehouseID, auth.View); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}

	var selectedAsset persistence.AssetRecord
	var selectedVariant *persistence.VariantRecord
	err = server.repository.ReadCurrentVariant(request.Context(), mediaID, ownerType, ownerID,
		warehouseID, generation, variant, func(asset persistence.AssetRecord, record *persistence.VariantRecord) error {
			if asset.Status != media.StatusReady || asset.Generation != generation {
				return errMediaNotReady
			}
			if record == nil || record.Variant != variant || record.ObjectVersionID == "" {
				return errOriginalMissing
			}
			selectedAsset = asset
			copyOfVariant := *record
			selectedVariant = &copyOfVariant
			return nil
		})
	switch {
	case errors.Is(err, errMediaNotReady):
		server.problem(response, request, http.StatusConflict, "MEDIA_NOT_READY", "Media is not ready")
	case errors.Is(err, errOriginalMissing):
		server.problem(response, request, http.StatusServiceUnavailable,
			"MEDIA_VARIANT_UNAVAILABLE", "Media variant is unavailable")
	case err != nil:
		server.repositoryProblem(response, request, err)
	default:
		server.streamVariant(response, request, derivedFileName(selectedAsset.FileName, variant, selectedVariant.ContentType), *selectedVariant)
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

type deletionRequest struct {
	ExpectedVersion *int64 `json:"expectedVersion"`
}

func (server *Server) deleteAsset(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.principal(response, request)
	if !ok {
		return
	}
	ownerType, ownerID, warehouseID, ok := server.ownerScope(response, request)
	if !ok {
		return
	}
	if err := principal.Require("rwms.write", warehouseID, auth.Edit); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	mediaID, err := uuid.Parse(request.PathValue("mediaId"))
	if err != nil || mediaID == uuid.Nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid media ID")
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	var body deletionRequest
	if !server.decode(response, request, &body) {
		return
	}
	if body.ExpectedVersion == nil || *body.ExpectedVersion <= 0 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid deletion request")
		return
	}
	asset, _, err := server.repository.Delete(request.Context(), persistence.DeleteCommand{
		MediaID: mediaID, OwnerType: ownerType, OwnerID: ownerID, WarehouseID: warehouseID,
		SubjectID: principal.SubjectID, IdempotencyKey: idempotencyKey,
		RequestSHA256: requestFingerprint(map[string]any{
			"mediaId": mediaID, "ownerType": ownerType, "ownerId": ownerID,
			"warehouseId": warehouseID, "expectedVersion": *body.ExpectedVersion,
		}),
		ExpectedVersion: *body.ExpectedVersion, CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusOK, assetResponse(asset, nil))
}

func safeVariants(record persistence.AssetWithVariants, ownerType, ownerID string, warehouseID uuid.UUID) []any {
	if record.Asset.Status != media.StatusReady || record.Asset.Generation <= 0 {
		return []any{}
	}
	result := make([]any, 0, len(record.Variants))
	for _, variant := range record.Variants {
		if variant.Variant == media.VariantOriginal || variant.ObjectVersionID == "" {
			continue
		}
		contentPath := publicVariantContentPath(record.Asset.ID, record.Asset.Generation, variant,
			ownerType, ownerID, warehouseID)
		if contentPath == "" {
			continue
		}
		result = append(result, map[string]any{
			"kind": variant.Variant, "contentType": variant.ContentType,
			"contentPath": contentPath,
			"width":       variant.Width, "height": variant.Height,
		})
	}
	return result
}

func publicVariantResponse(mediaID uuid.UUID, generation int, variant persistence.VariantRecord,
	ownerType, ownerID string, warehouseID uuid.UUID,
) map[string]any {
	return map[string]any{"mediaId": mediaID, "generation": generation, "kind": variant.Variant,
		"contentType": variant.ContentType,
		"contentPath": publicVariantContentPath(mediaID, generation, variant, ownerType, ownerID, warehouseID),
		"width":       variant.Width, "height": variant.Height}
}

func publicVariantContentPath(mediaID uuid.UUID, generation int, variant persistence.VariantRecord,
	ownerType, ownerID string, warehouseID uuid.UUID,
) string {
	query := make(url.Values)
	query.Set("ownerType", ownerType)
	if persistence.IsLogisticsOwnerType(ownerType) {
		documentID, lineID, valid := persistence.LogisticsOwnerParts(ownerID)
		if !valid {
			return ""
		}
		query.Set("documentId", documentID.String())
		query.Set("lineId", lineID.String())
	} else {
		query.Set("ownerId", ownerID)
	}
	query.Set("warehouseId", warehouseID.String())
	query.Set("context", viewerContextForOwner(ownerType))
	query.Set("generation", strconv.Itoa(generation))
	contentPath := "/api/media/v1/assets/" + mediaID.String() + "/variants/" +
		string(variant.Variant) + "/content?" + query.Encode()
	return contentPath
}

func (server *Server) streamVariant(
	response http.ResponseWriter,
	request *http.Request,
	fileName string,
	variant persistence.VariantRecord,
) {
	object, metadata, err := server.store.GetVersion(request.Context(), variant.ObjectKey, variant.ObjectVersionID)
	if err != nil {
		server.logger.Error("open immutable media content", "correlationId", correlationID(request.Context()), "error", safeError(err))
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}
	defer object.Close()
	if metadata.VersionID != variant.ObjectVersionID || metadata.SizeBytes != variant.SizeBytes ||
		normalizeContentType(metadata.ContentType) != normalizeContentType(variant.ContentType) {
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}

	response.Header().Set("Cache-Control", "private, no-store")
	response.Header().Set("Content-Type", variant.ContentType)
	response.Header().Set("Content-Length", strconv.FormatInt(variant.SizeBytes, 10))
	response.Header().Set("Content-Disposition", contentDisposition(fileName))
	response.Header().Set("X-Content-Type-Options", "nosniff")
	response.WriteHeader(http.StatusOK)
	written, copyErr := io.CopyN(response, object, variant.SizeBytes)
	if copyErr != nil || written != variant.SizeBytes {
		server.logger.Error("stream immutable media content", "correlationId", correlationID(request.Context()), "error", safeError(copyErr))
	}
}

func contentDisposition(fileName string) string {
	if !validFileName(fileName) {
		fileName = "media"
	}
	value := mime.FormatMediaType("inline", map[string]string{"filename": fileName})
	if value == "" {
		return `inline; filename="media"`
	}
	return value
}

func derivedFileName(original string, variant media.Variant, contentType string) string {
	base := strings.TrimSuffix(original, filepath.Ext(original))
	if base == "" || !validFileName(base) {
		base = "media"
	}
	return base + "-" + strings.ToLower(string(variant)) + extensionForContentType(contentType)
}

func publicDerivedVariant(value string) (media.Variant, bool) {
	variant := media.Variant(strings.ToUpper(strings.TrimSpace(value)))
	switch variant {
	case media.VariantSmall, media.VariantMedium, media.VariantLarge:
		return variant, true
	default:
		return "", false
	}
}

func assetResponse(asset persistence.AssetRecord, variants []any) map[string]any {
	if variants == nil {
		variants = []any{}
	}
	return map[string]any{
		"id": asset.ID, "folderId": asset.FolderID, "fileName": asset.FileName, "contentType": asset.ContentType,
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
	principal, ok := server.servicePrincipal(response, request)
	if !ok {
		return false
	}
	err := principal.RequireExact("logistics-service", "media.logistics")
	if err == nil {
		return true
	}
	server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
	return false
}

func (server *Server) servicePrincipal(response http.ResponseWriter, request *http.Request) (auth.ServicePrincipal, bool) {
	principal, err := server.auth.ValidateService(request.Context(), request.Header.Get("Authorization"))
	if err != nil {
		status := http.StatusUnauthorized
		code := "MEDIA_UNAUTHORIZED"
		if errors.Is(err, auth.ErrForbidden) {
			status, code = http.StatusForbidden, "MEDIA_FORBIDDEN"
		}
		server.problem(response, request, status, code, "Access is denied")
		return auth.ServicePrincipal{}, false
	}
	return principal, true
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
	ownerType := query.Get("ownerType")
	warehouseID, warehouseErr := uuid.Parse(query.Get("warehouseId"))
	ownerID, ownerOK := resolvePublicOwnerID(ownerType, query.Get("ownerId"),
		query.Get("documentId"), query.Get("lineId"))
	if !validPublicOwnerScope(ownerType, query.Get("context")) || !ownerOK ||
		warehouseErr != nil || warehouseID == uuid.Nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_CONTEXT", "Invalid owner context")
		return "", "", uuid.Nil, false
	}
	return ownerType, ownerID, warehouseID, true
}

func validPublicOwnerScope(ownerType, context string) bool {
	_, valid := persistence.PublicOwnerScope(ownerType, context)
	return valid
}

func viewerContextForOwner(ownerType string) string {
	viewerContext, _ := persistence.ViewerContextForOwner(ownerType)
	return viewerContext
}

func resolvePublicOwnerID(ownerType, ownerID, documentID, lineID string) (string, bool) {
	definition, found := persistence.PublicOwnerScope(ownerType, viewerContextForOwner(ownerType))
	if !found {
		return "", false
	}
	if definition.Structured {
		if ownerID != "" {
			return "", false
		}
		parsedDocumentID, documentErr := uuid.Parse(documentID)
		parsedLineID, lineErr := uuid.Parse(lineID)
		if documentErr != nil || lineErr != nil || parsedDocumentID == uuid.Nil || parsedLineID == uuid.Nil {
			return "", false
		}
		return persistence.LogisticsOwnerID(parsedDocumentID, parsedLineID), true
	}
	if documentID != "" || lineID != "" {
		return "", false
	}
	parsedOwnerID, err := uuid.Parse(ownerID)
	if err != nil || parsedOwnerID == uuid.Nil {
		return "", false
	}
	return parsedOwnerID.String(), true
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
