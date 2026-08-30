// Package api implements the authenticated HTTP boundary for media upload,
// read, ownership-proof, import, and logistics presentation operations.
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
	"dev.buhanzaz.rwms/media-service/internal/realtime"
	"dev.buhanzaz.rwms/media-service/internal/storage"
	"github.com/google/uuid"
)

const correlationHeader = "X-Correlation-Id"

var checksumPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

const maxStoredGeneration = int64(1<<31 - 1)

// Configuration contains the ingress limits and collaborators required by a
// Server. The caller must provide an explicit MIME allowlist and upload expiry.
type Configuration struct {
	MaxUploadBytes   int64
	AllowedMIMETypes map[string]struct{}
	UploadExpiry     time.Duration
	AssetImports     assetImportService
}

type readiness interface {
	Ready(context.Context) error
}

type repository interface {
	CreateUpload(context.Context, persistence.CreateUploadCommand) (persistence.AssetRecord, bool, error)
	AcquireUploadSessionContentLock(context.Context, uuid.UUID) (func() error, error)
	AcquireUploadImageVariantContentLock(context.Context, uuid.UUID, media.Variant) (func() error, error)
	UploadSessionForPrincipal(context.Context, uuid.UUID, uuid.UUID, string) (persistence.AssetRecord, error)
	UploadSessionForCustomer(context.Context, uuid.UUID, uuid.UUID) (persistence.AssetRecord, error)
	UploadImageVariantForPrincipal(context.Context, uuid.UUID, uuid.UUID, string, media.Variant) (persistence.AssetRecord, persistence.UploadImageVariantPart, error)
	UploadImageVariantForCustomer(context.Context, uuid.UUID, uuid.UUID, media.Variant) (persistence.AssetRecord, persistence.UploadImageVariantPart, error)
	CompleteUploadImageVariant(context.Context, persistence.CompleteUploadImageVariantCommand) (persistence.UploadImageVariantPart, bool, error)
	FinalizeUpload(context.Context, persistence.FinalizeCommand) (persistence.AssetRecord, bool, error)
	ReadOwnerAssets(context.Context, string, string, uuid.UUID, int, *uuid.UUID,
		func([]persistence.AssetWithVariants) error) error
	ReadOwnerAssetsForCustomer(context.Context, string, string, uuid.UUID, uuid.UUID, int, *uuid.UUID,
		func([]persistence.AssetWithVariants) error) error
	ReadTaskBoardEntryAssetsForWorker(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, int, *uuid.UUID,
		func([]persistence.AssetWithVariants) error) error
	ReadDriverShiftAssetsForWorker(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, int, *uuid.UUID,
		func([]persistence.AssetWithVariants) error) error
	ReadCabinCovers(context.Context, uuid.UUID, []uuid.UUID,
		func([]persistence.CabinCoverRecord) error) error
	ReadCabinPresentationSnapshots(context.Context, uuid.UUID, []uuid.UUID,
		func([]persistence.CabinPresentationSnapshotRecord) error) error
	ReadOriginal(context.Context, uuid.UUID, string, string, uuid.UUID, *int,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	ReadOriginalForCustomer(context.Context, uuid.UUID, string, string, uuid.UUID, uuid.UUID, *int,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	ReadCurrentVariant(context.Context, uuid.UUID, string, string, uuid.UUID, int, media.Variant,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	ReadCurrentVariantForCustomer(context.Context, uuid.UUID, string, string, uuid.UUID, uuid.UUID, int, media.Variant,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	ReadTaskBoardEntryOriginalForWorker(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, uuid.UUID, *int,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	ReadTaskBoardEntryVariantForWorker(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, uuid.UUID, int, media.Variant,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	ReadDriverShiftOriginalForWorker(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, uuid.UUID, *int,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	ReadDriverShiftVariantForWorker(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, uuid.UUID, int, media.Variant,
		func(persistence.AssetRecord, *persistence.VariantRecord) error) error
	AuthorizeTaskBoardEntryWorker(context.Context, uuid.UUID, uuid.UUID, uuid.UUID) error
	AuthorizeDriverShiftWorker(context.Context, uuid.UUID, uuid.UUID, uuid.UUID) error
	GetAssetScoped(context.Context, uuid.UUID, string, string, uuid.UUID) (persistence.AssetRecord, error)
	UpsertServiceOwnerProof(context.Context, persistence.ServiceOwnerProofCommand) (persistence.ServiceOwnerProofRecord, bool, error)
	ValidateLogisticsReferences(context.Context, persistence.ValidateLogisticsReferencesCommand) error
	ApplyInventoryCabinPhotos(context.Context, persistence.ApplyInventoryCabinPhotosCommand) (persistence.InventoryCabinPhotoResult, bool, bool, error)
	SetCabinCoverFromTaskEvidence(context.Context, persistence.SetCabinCoverFromTaskEvidenceCommand) (persistence.CabinCoverChangeRecord, bool, error)
	Delete(context.Context, persistence.DeleteCommand) (persistence.AssetRecord, bool, error)
}

type tokenValidator interface {
	Validate(context.Context, string) (auth.Principal, error)
	ValidateWorker(context.Context, string) (auth.WorkerPrincipal, error)
	ValidateService(context.Context, string) (auth.ServicePrincipal, error)
}

// mediaRequestPrincipal deliberately keeps the OAuth subject used to own an
// upload session separate from the actor identity in facts. For WORKER, the
// former is auth-service's session subject while the latter is worker_id.
type mediaRequestPrincipal struct {
	subjectID     uuid.UUID
	principalType string
	actor         persistence.ActorReference
	user          *auth.Principal
	worker        *auth.WorkerPrincipal
}

func (principal mediaRequestPrincipal) isWorker() bool {
	return principal.worker != nil
}

func (principal mediaRequestPrincipal) isCustomerRental() bool {
	return principal.user != nil && principal.user.IsCustomerRental()
}

func authorizedSubjectFor(principal mediaRequestPrincipal) *uuid.UUID {
	if !principal.isCustomerRental() {
		return nil
	}
	subjectID := principal.subjectID
	return &subjectID
}

func (principal mediaRequestPrincipal) requireWorkerOwner(ownerType, ownerID string, warehouseID uuid.UUID) (uuid.UUID, error) {
	if principal.worker == nil {
		return uuid.Nil, auth.ErrForbidden
	}
	switch ownerType {
	case persistence.OwnerTypeTaskBoardEntry:
		if err := principal.worker.RequireTaskAccess(warehouseID); err != nil {
			return uuid.Nil, err
		}
	case persistence.OwnerTypeDriverShift:
		if err := principal.worker.RequireDriverTaskAccess(warehouseID); err != nil {
			return uuid.Nil, err
		}
	default:
		return uuid.Nil, auth.ErrForbidden
	}
	ownerUUID, err := uuid.Parse(ownerID)
	if err != nil || ownerUUID == uuid.Nil || ownerUUID.String() != ownerID {
		return uuid.Nil, auth.ErrForbidden
	}
	return ownerUUID, nil
}

func workerIDFor(principal mediaRequestPrincipal) *uuid.UUID {
	if principal.worker == nil {
		return nil
	}
	workerID := principal.worker.WorkerID
	return &workerID
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

// Read enforces the declared upload length and detects an early end of the request body.
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

// Server exposes the media HTTP contract while delegating durable state and
// authorization decisions to its injected dependencies.
type Server struct {
	repository    repository
	database      readiness
	auth          tokenValidator
	store         objectStore
	config        Configuration
	assetImports  assetImportService
	logger        *slog.Logger
	mux           *http.ServeMux
	invalidations invalidationStream
}

type invalidationStream interface {
	realtime.Publisher
	ServeHTTP(http.ResponseWriter, *http.Request, uuid.UUID)
}

// NewServer validates dependencies and constructs a Server with every media
// and private service route registered on its internal multiplexer.
func NewServer(repository repository, database readiness, validator tokenValidator, store objectStore, configuration Configuration, logger *slog.Logger) (*Server, error) {
	if repository == nil || database == nil || validator == nil || store == nil || logger == nil {
		return nil, fmt.Errorf("media API dependencies are required")
	}
	if configuration.MaxUploadBytes <= 0 || configuration.UploadExpiry <= 0 || len(configuration.AllowedMIMETypes) == 0 {
		return nil, fmt.Errorf("media API limits and allowlist are required")
	}
	server := &Server{repository: repository, database: database, auth: validator, store: store, config: configuration, assetImports: configuration.AssetImports, logger: logger, mux: http.NewServeMux(), invalidations: realtime.NewHub()}
	server.routes()
	return server, nil
}

// Handler returns the HTTP handler with correlation and panic-recovery
// middleware applied around the registered media routes.
func (server *Server) Handler() http.Handler {
	return server.correlation(server.recover(server.mux))
}

// Invalidations exposes the process-local publisher to asynchronous media
// workers. The HTTP server remains the sole owner of subscriber lifecycles.
func (server *Server) Invalidations() realtime.Publisher {
	return server.invalidations
}

func (server *Server) routes() {
	server.mux.HandleFunc("GET /health/live", server.live)
	server.mux.HandleFunc("GET /health/ready", server.ready)
	server.mux.HandleFunc("GET /api/media/v1/events", server.events)
	server.mux.HandleFunc("POST /api/media/v1/upload-sessions", server.createUpload)
	server.mux.HandleFunc("PUT /api/media/v1/upload-sessions/{uploadSessionId}/content", server.uploadSessionContent)
	server.mux.HandleFunc("PUT /api/media/v1/upload-sessions/{uploadSessionId}/variants/{variant}/content", server.uploadSessionImageVariantContent)
	server.mux.HandleFunc("POST /api/media/v1/upload-sessions/{uploadSessionId}/complete", server.finalizeUpload)
	server.mux.HandleFunc("GET /api/media/v1/assets", server.listOwner)
	server.mux.HandleFunc("POST /api/media/v1/cabin-covers", server.listCabinCovers)
	server.mux.HandleFunc("GET /api/media/v1/assets/{mediaId}/original", server.getOriginal)
	server.mux.HandleFunc("GET /api/media/v1/assets/{mediaId}/variants/{variant}/content", server.getVariantContent)
	server.mux.HandleFunc("POST /api/media/v1/assets/{mediaId}/deletion", server.deleteAsset)
	server.mux.HandleFunc("POST /api/internal/media/v1/owner-proofs", server.upsertOwnerProof)
	server.mux.HandleFunc("POST /api/internal/media/v1/asset-imports/preflight", server.preflightAssetImport)
	server.mux.HandleFunc("GET /api/internal/media/v1/asset-imports/{jobId}", server.getAssetImport)
	server.mux.HandleFunc("POST /api/internal/media/v1/asset-imports/{jobId}/activate", server.activateAssetImport)
	server.mux.HandleFunc("POST /api/internal/media/v1/asset-imports/{jobId}/replace-sources", server.replaceAssetImportSources)
	server.mux.HandleFunc("POST /api/internal/media/v1/asset-imports/{jobId}/retry", server.retryAssetImport)
	server.mux.HandleFunc("POST /api/internal/media/v1/logistics/references/validate", server.validateLogisticsReferences)
	server.mux.HandleFunc("PUT /api/internal/media/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/cabin-photos", server.applyInventoryCabinPhotos)
	server.mux.HandleFunc("POST /api/internal/media/v1/logistics/cabin-presentations/snapshots", server.listLogisticsCabinPresentationSnapshots)
	server.mux.HandleFunc("POST /api/internal/media/v1/logistics/cabins/{cabinId}/cover-from-task-evidence", server.setCabinCoverFromTaskEvidence)
	server.mux.HandleFunc("GET /api/internal/media/v1/logistics/cabin-presentations/assets/{mediaId}/variants/{variant}/content", server.getLogisticsCabinPresentationVariantContent)
	server.mux.HandleFunc("/health/live", server.methodNotAllowed)
	server.mux.HandleFunc("/health/ready", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/events", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions/{uploadSessionId}/content", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions/{uploadSessionId}/variants/{variant}/content", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/upload-sessions/{uploadSessionId}/complete", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/cabin-covers", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/original", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/variants/{variant}/content", server.methodNotAllowed)
	server.mux.HandleFunc("/api/media/v1/assets/{mediaId}/deletion", server.methodNotAllowed)
	server.mux.HandleFunc("/api/internal/media/v1/owner-proofs", server.methodNotAllowed)
	// A trailing-prefix fallback is less specific than every method-qualified
	// import route above, while avoiding the ambiguous exact-path fallbacks that
	// would conflict with the GET {jobId} pattern in net/http's ServeMux.
	server.mux.HandleFunc("/api/internal/media/v1/asset-imports/", server.methodNotAllowed)
	server.mux.HandleFunc("/api/internal/media/v1/logistics/references/validate", server.methodNotAllowed)
	server.mux.HandleFunc("/api/internal/media/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/cabin-photos", server.methodNotAllowed)
	server.mux.HandleFunc("/api/internal/media/v1/logistics/cabin-presentations/snapshots", server.methodNotAllowed)
	server.mux.HandleFunc("/api/internal/media/v1/logistics/cabins/{cabinId}/cover-from-task-evidence", server.methodNotAllowed)
	server.mux.HandleFunc("/api/internal/media/v1/logistics/cabin-presentations/assets/{mediaId}/variants/{variant}/content", server.methodNotAllowed)
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

func (server *Server) events(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.principal(response, request)
	if !ok {
		return
	}
	warehouseID, err := uuid.Parse(strings.TrimSpace(request.URL.Query().Get("warehouseId")))
	if err != nil || warehouseID == uuid.Nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid warehouse ID")
		return
	}
	if err := principal.Require("rwms.read", warehouseID, auth.View); err != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	// The server keeps a bounded write deadline for every ordinary media
	// response. Only this authenticated SSE response is allowed to stay open.
	_ = http.NewResponseController(response).SetWriteDeadline(time.Time{})
	server.invalidations.ServeHTTP(response, request, warehouseID)
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
	OwnerType           string `json:"ownerType"`
	OwnerID             string `json:"ownerId"`
	DocumentID          string `json:"documentId"`
	LineID              string `json:"lineId"`
	AuthorizedSubjectID string `json:"authorizedSubjectId"`
	WarehouseID         string `json:"warehouseId"`
	OwnerRevision       *int64 `json:"ownerRevision"`
	AggregateVersion    *int64 `json:"aggregateVersion"`
	ProofEventID        string `json:"proofEventId"`
	Active              *bool  `json:"active"`
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
	if persistence.IsCustomerSubjectBoundOwnerType(body.OwnerType) {
		subjectID, subjectErr := uuid.Parse(body.AuthorizedSubjectID)
		if subjectErr != nil || subjectID == uuid.Nil || subjectID.String() != body.AuthorizedSubjectID {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_OWNER_PROOF", "Invalid owner proof")
			return
		}
		command.AuthorizedSubjectID = &subjectID
	} else if body.AuthorizedSubjectID != "" {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_OWNER_PROOF", "Invalid owner proof")
		return
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
	if record.AuthorizedSubjectID != nil {
		result["authorizedSubjectId"] = *record.AuthorizedSubjectID
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
	OwnerType           string                           `json:"ownerType"`
	OwnerID             string                           `json:"ownerId"`
	DocumentID          string                           `json:"documentId"`
	LineID              string                           `json:"lineId"`
	AuthorizedSubjectID string                           `json:"authorizedSubjectId"`
	WarehouseID         string                           `json:"warehouseId"`
	Context             string                           `json:"context"`
	References          []logisticsMediaReferenceRequest `json:"references"`
}

type logisticsMediaReferenceResponse struct {
	MediaID    uuid.UUID `json:"mediaId"`
	Generation int       `json:"generation"`
}

type validateLogisticsReferencesResponse struct {
	OwnerType           string                            `json:"ownerType"`
	OwnerID             *uuid.UUID                        `json:"ownerId,omitempty"`
	DocumentID          *uuid.UUID                        `json:"documentId,omitempty"`
	LineID              *uuid.UUID                        `json:"lineId,omitempty"`
	AuthorizedSubjectID *uuid.UUID                        `json:"authorizedSubjectId,omitempty"`
	WarehouseID         uuid.UUID                         `json:"warehouseId"`
	Context             string                            `json:"context,omitempty"`
	References          []logisticsMediaReferenceResponse `json:"references"`
}

// inventoryCabinPhotoReferenceRequest is one exact generation decoded from
// the private completed-inventory request.
type inventoryCabinPhotoReferenceRequest struct {
	MediaID    string `json:"mediaId"`
	Generation int    `json:"generation"`
}

// applyInventoryCabinPhotosRequest is the closed JSON shape accepted from
// inventory-service after a final plan has been completed.
type applyInventoryCabinPhotosRequest struct {
	WarehouseID      string                                `json:"warehouseId"`
	CabinID          string                                `json:"cabinId"`
	CompletedAt      time.Time                             `json:"completedAt"`
	SourceRevision   int64                                 `json:"sourceRevision"`
	FinalPlanVersion int64                                 `json:"finalPlanVersion"`
	FinalPlanSHA256  string                                `json:"finalPlanSha256"`
	CoverMediaID     string                                `json:"coverMediaId"`
	MediaReferences  []inventoryCabinPhotoReferenceRequest `json:"mediaReferences"`
}

// applyInventoryCabinPhotos accepts only inventory-service's exact private
// scope and delegates the authoritative folder selection to one serializable
// media-owned transaction.
func (server *Server) applyInventoryCabinPhotos(response http.ResponseWriter, request *http.Request) {
	if !server.inventoryPrincipal(response, request) {
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	inventoryID, inventoryErr := uuid.Parse(request.PathValue("inventoryId"))
	findingID, findingErr := uuid.Parse(request.PathValue("findingId"))
	var body applyInventoryCabinPhotosRequest
	if !server.decode(response, request, &body) {
		return
	}
	warehouseID, warehouseErr := uuid.Parse(body.WarehouseID)
	cabinID, cabinErr := uuid.Parse(body.CabinID)
	coverMediaID, coverErr := uuid.Parse(body.CoverMediaID)
	completedAt := body.CompletedAt.UTC().Truncate(time.Microsecond)
	if inventoryErr != nil || findingErr != nil || warehouseErr != nil ||
		cabinErr != nil || coverErr != nil || inventoryID == uuid.Nil ||
		findingID == uuid.Nil || warehouseID == uuid.Nil || cabinID == uuid.Nil ||
		coverMediaID == uuid.Nil || completedAt.IsZero() || body.SourceRevision < 1 ||
		body.FinalPlanVersion < 1 || !checksumPattern.MatchString(body.FinalPlanSHA256) ||
		len(body.MediaReferences) < 1 || len(body.MediaReferences) > 100 {
		server.problem(response, request, http.StatusBadRequest,
			"MEDIA_INVALID_INVENTORY_CABIN_PHOTOS", "Invalid inventory cabin photo outcome")
		return
	}
	references := make([]persistence.InventoryCabinPhotoReference, 0, len(body.MediaReferences))
	canonicalReferences := make([]map[string]any, 0, len(body.MediaReferences))
	seen := make(map[uuid.UUID]struct{}, len(body.MediaReferences))
	coverFound := false
	for _, value := range body.MediaReferences {
		mediaID, err := uuid.Parse(value.MediaID)
		if err != nil || mediaID == uuid.Nil || value.Generation < 1 {
			server.problem(response, request, http.StatusBadRequest,
				"MEDIA_INVALID_INVENTORY_CABIN_PHOTOS", "Invalid inventory cabin photo outcome")
			return
		}
		if _, duplicate := seen[mediaID]; duplicate {
			server.problem(response, request, http.StatusBadRequest,
				"MEDIA_INVALID_INVENTORY_CABIN_PHOTOS", "Invalid inventory cabin photo outcome")
			return
		}
		seen[mediaID] = struct{}{}
		coverFound = coverFound || mediaID == coverMediaID
		references = append(references, persistence.InventoryCabinPhotoReference{
			MediaID: mediaID, Generation: value.Generation,
		})
		canonicalReferences = append(canonicalReferences, map[string]any{
			"mediaId": mediaID, "generation": value.Generation,
		})
	}
	if !coverFound {
		server.problem(response, request, http.StatusBadRequest,
			"MEDIA_INVALID_INVENTORY_CABIN_PHOTOS", "Invalid inventory cabin photo outcome")
		return
	}
	fingerprint := requestFingerprint(map[string]any{
		"inventoryId": inventoryID, "findingId": findingID,
		"warehouseId": warehouseID, "cabinId": cabinID,
		"completedAt": completedAt, "sourceRevision": body.SourceRevision,
		"finalPlanVersion": body.FinalPlanVersion,
		"finalPlanSha256":  body.FinalPlanSHA256,
		"coverMediaId":     coverMediaID, "mediaReferences": canonicalReferences,
	})
	result, replayed, changed, err := server.repository.ApplyInventoryCabinPhotos(
		request.Context(), persistence.ApplyInventoryCabinPhotosCommand{
			InventoryID: inventoryID, FindingID: findingID,
			WarehouseID: warehouseID, CabinID: cabinID, CompletedAt: completedAt,
			SourceRevision: body.SourceRevision, FinalPlanVersion: body.FinalPlanVersion,
			FinalPlanSHA256: body.FinalPlanSHA256, CoverMediaID: coverMediaID,
			MediaReferences: references, IdempotencyKey: idempotencyKey,
			RequestSHA256: fingerprint, CorrelationID: correlationID(request.Context()),
		})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if changed {
		server.publishCabinCoverChange(persistence.CabinCoverChangeRecord{
			CabinID: result.CabinID, WarehouseID: result.WarehouseID,
			MediaID: result.CoverMediaID, Generation: result.CoverGeneration,
			Version: result.LibraryVersion, ChangedAt: result.ChangedAt,
		})
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, status, map[string]any{
		"inventoryId": result.InventoryID, "findingId": result.FindingID,
		"cabinId": result.CabinID, "folderId": result.FolderID,
		"coverMediaId": result.CoverMediaID, "photoCount": result.PhotoCount,
		"libraryVersion": result.LibraryVersion, "replay": replayed,
	})
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
	warehouseID, warehouseErr := uuid.Parse(body.WarehouseID)
	if warehouseErr != nil || warehouseID == uuid.Nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_LOGISTICS_REFERENCE", "Invalid logistics media reference request")
		return
	}
	var ownerID string
	var profileOwnerID, documentID, lineID *uuid.UUID
	var authorizedSubjectID *uuid.UUID
	if body.OwnerType == persistence.OwnerTypeLogisticsCustomerProfile {
		parsedOwnerID, ownerErr := uuid.Parse(body.OwnerID)
		parsedSubjectID, subjectErr := uuid.Parse(body.AuthorizedSubjectID)
		if ownerErr != nil || subjectErr != nil || parsedOwnerID == uuid.Nil || parsedSubjectID == uuid.Nil ||
			parsedOwnerID.String() != body.OwnerID || parsedSubjectID.String() != body.AuthorizedSubjectID ||
			body.DocumentID != "" || body.LineID != "" || body.Context != persistence.ViewerContextProfileAvatar ||
			len(body.References) != 1 {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_LOGISTICS_REFERENCE", "Invalid logistics media reference request")
			return
		}
		ownerID = parsedOwnerID.String()
		profileOwnerID = &parsedOwnerID
		authorizedSubjectID = &parsedSubjectID
	} else {
		parsedDocumentID, documentErr := uuid.Parse(body.DocumentID)
		parsedLineID, lineErr := uuid.Parse(body.LineID)
		if documentErr != nil || lineErr != nil || parsedDocumentID == uuid.Nil || parsedLineID == uuid.Nil ||
			!persistence.IsLogisticsOwnerType(body.OwnerType) || body.OwnerID != "" ||
			body.AuthorizedSubjectID != "" || body.Context != "" || len(body.References) < 1 || len(body.References) > 20 {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_LOGISTICS_REFERENCE", "Invalid logistics media reference request")
			return
		}
		documentID, lineID = &parsedDocumentID, &parsedLineID
		ownerID = persistence.LogisticsOwnerID(parsedDocumentID, parsedLineID)
	}
	references := make([]persistence.ReadyMediaReference, 0, len(body.References))
	responseReferences := make([]logisticsMediaReferenceResponse, 0, len(body.References))
	seen := make(map[uuid.UUID]struct{}, len(body.References))
	for _, reference := range body.References {
		mediaID, err := uuid.Parse(reference.MediaID)
		if err != nil || mediaID == uuid.Nil || reference.Generation <= 0 {
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
		OwnerType: body.OwnerType, OwnerID: ownerID, AuthorizedSubjectID: authorizedSubjectID,
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
		OwnerType: body.OwnerType, OwnerID: profileOwnerID, DocumentID: documentID,
		LineID: lineID, AuthorizedSubjectID: authorizedSubjectID,
		WarehouseID: warehouseID, Context: body.Context, References: responseReferences,
	})
}

type cabinPresentationSnapshotsRequest struct {
	WarehouseID string   `json:"warehouseId"`
	CabinIDs    []string `json:"cabinIds"`
}

type setCabinCoverFromTaskEvidenceRequest struct {
	TaskBoardEntryID string `json:"taskBoardEntryId"`
	EvidenceMediaID  string `json:"evidenceMediaId"`
}

func (server *Server) setCabinCoverFromTaskEvidence(response http.ResponseWriter, request *http.Request) {
	if !server.logisticsPrincipal(response, request) {
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	cabinID, cabinErr := uuid.Parse(request.PathValue("cabinId"))
	var body setCabinCoverFromTaskEvidenceRequest
	if !server.decode(response, request, &body) {
		return
	}
	entryID, entryErr := uuid.Parse(body.TaskBoardEntryID)
	evidenceID, evidenceErr := uuid.Parse(body.EvidenceMediaID)
	if cabinErr != nil || entryErr != nil || evidenceErr != nil ||
		cabinID == uuid.Nil || entryID == uuid.Nil || evidenceID == uuid.Nil {
		server.problem(response, request, http.StatusBadRequest,
			"MEDIA_INVALID_CABIN_COVER", "Invalid cabin cover request")
		return
	}
	record, replayed, err := server.repository.SetCabinCoverFromTaskEvidence(
		request.Context(), persistence.SetCabinCoverFromTaskEvidenceCommand{
			CabinID: cabinID, TaskBoardEntryID: entryID, EvidenceMediaID: evidenceID,
			IdempotencyKey: idempotencyKey,
			RequestSHA256: requestFingerprint(map[string]any{
				"cabinId": cabinID, "taskBoardEntryId": entryID,
				"evidenceMediaId": evidenceID,
			}),
			CorrelationID: correlationID(request.Context()),
		})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !replayed {
		server.publishCabinCoverChange(record)
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, status, map[string]any{
		"cabinId": record.CabinID, "warehouseId": record.WarehouseID,
		"coverMediaId": record.MediaID, "generation": record.Generation,
		"taskBoardEntryId": record.TaskBoardEntryID, "version": record.Version,
		"changedAt": record.ChangedAt,
	})
}

// listLogisticsCabinPresentationSnapshots is intentionally a private
// projection boundary: logistics receives only opaque current media references
// and the variants it may subsequently stream. It never receives a public URL,
// MinIO location, filename, MIME type, or processing detail.
func (server *Server) listLogisticsCabinPresentationSnapshots(response http.ResponseWriter, request *http.Request) {
	if !server.logisticsPrincipal(response, request) {
		return
	}
	var body cabinPresentationSnapshotsRequest
	if !server.decode(response, request, &body) {
		return
	}
	warehouseID, err := uuid.Parse(body.WarehouseID)
	if err != nil || warehouseID == uuid.Nil || len(body.CabinIDs) < 1 || len(body.CabinIDs) > 100 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_CABIN_PRESENTATION", "Invalid cabin presentation request")
		return
	}
	cabinIDs := make([]uuid.UUID, 0, len(body.CabinIDs))
	seen := make(map[uuid.UUID]struct{}, len(body.CabinIDs))
	for _, value := range body.CabinIDs {
		cabinID, parseErr := uuid.Parse(value)
		if parseErr != nil || cabinID == uuid.Nil {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_CABIN_PRESENTATION", "Invalid cabin presentation request")
			return
		}
		if _, duplicate := seen[cabinID]; duplicate {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_CABIN_PRESENTATION", "Invalid cabin presentation request")
			return
		}
		seen[cabinID] = struct{}{}
		cabinIDs = append(cabinIDs, cabinID)
	}
	items := make([]any, 0, len(cabinIDs))
	err = server.repository.ReadCabinPresentationSnapshots(request.Context(), warehouseID, cabinIDs,
		func(records []persistence.CabinPresentationSnapshotRecord) error {
			for _, record := range records {
				photos := make([]any, 0, len(record.Photos))
				for _, photo := range record.Photos {
					variants := make([]string, 0, 2)
					if photo.HasSmall {
						variants = append(variants, string(media.VariantSmall))
					}
					if photo.HasLarge {
						variants = append(variants, string(media.VariantLarge))
					}
					photos = append(photos, map[string]any{
						"mediaId": photo.MediaID, "generation": photo.Generation,
						"sortOrder": photo.SortOrder, "availableVariants": variants,
					})
				}
				items = append(items, map[string]any{
					"cabinId": record.CabinID, "coverMediaId": record.CoverMediaID,
					"photoCount": record.PhotoCount, "photos": photos,
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

// getLogisticsCabinPresentationVariantContent streams exactly one approved
// cabin image variant. Every owner, warehouse, state, current-generation, and
// variant mismatch is folded into the same 404 response so this endpoint does
// not become a media-existence oracle.
func (server *Server) getLogisticsCabinPresentationVariantContent(response http.ResponseWriter, request *http.Request) {
	if !server.logisticsPrincipal(response, request) {
		return
	}
	mediaID, mediaErr := uuid.Parse(request.PathValue("mediaId"))
	variant := media.Variant(request.PathValue("variant"))
	query := request.URL.Query()
	warehouseID, warehouseErr := uuid.Parse(query.Get("warehouseId"))
	cabinID, cabinErr := uuid.Parse(query.Get("cabinId"))
	generation, generationErr := strconv.ParseInt(query.Get("generation"), 10, 64)
	if mediaErr != nil || mediaID == uuid.Nil || warehouseErr != nil || warehouseID == uuid.Nil ||
		cabinErr != nil || cabinID == uuid.Nil || generationErr != nil || generation < 1 || generation > maxStoredGeneration {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_CABIN_PRESENTATION", "Invalid cabin presentation request")
		return
	}
	if variant != media.VariantSmall && variant != media.VariantLarge {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_CABIN_PRESENTATION", "Invalid cabin presentation variant")
		return
	}

	var selectedVariant *persistence.VariantRecord
	err := server.repository.ReadCurrentVariant(request.Context(), mediaID, persistence.OwnerTypeCabin,
		cabinID.String(), warehouseID, int(generation), variant,
		func(asset persistence.AssetRecord, record *persistence.VariantRecord) error {
			if asset.ID != mediaID || asset.WarehouseID != warehouseID ||
				asset.Kind != media.KindImage || asset.Status != media.StatusReady ||
				asset.Generation != int(generation) ||
				record == nil || record.Variant != variant || record.ObjectKey == "" || record.ObjectVersionID == "" ||
				record.SizeBytes <= 0 {
				return persistence.ErrNotFound
			}
			variantKind, supportedImage := media.KindForContentType(normalizeContentType(record.ContentType))
			if !supportedImage || variantKind != media.KindImage {
				return persistence.ErrNotFound
			}
			copyOfVariant := *record
			selectedVariant = &copyOfVariant
			return nil
		})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	server.streamVariant(response, request,
		"cabin-presentation-"+strings.ToLower(string(variant))+extensionForContentType(selectedVariant.ContentType),
		*selectedVariant)
}

type createUploadRequest struct {
	OwnerType         string                      `json:"ownerType"`
	OwnerID           string                      `json:"ownerId"`
	DocumentID        string                      `json:"documentId"`
	LineID            string                      `json:"lineId"`
	ClientReferenceID string                      `json:"clientReferenceId"`
	WarehouseID       string                      `json:"warehouseId"`
	Context           string                      `json:"context"`
	FolderID          string                      `json:"folderId"`
	FileName          string                      `json:"fileName"`
	ContentType       string                      `json:"contentType"`
	ContentLength     int64                       `json:"contentLength"`
	ChecksumSHA256    string                      `json:"checksumSha256"`
	ImageVariants     []createImageVariantRequest `json:"imageVariants"`
	SortOrder         int64                       `json:"sortOrder"`
}

// createImageVariantRequest declares one client-produced WebP object without
// embedding image bytes in the JSON command.
type createImageVariantRequest struct {
	Kind           media.Variant `json:"kind"`
	ContentLength  int64         `json:"contentLength"`
	ChecksumSHA256 string        `json:"checksumSha256"`
	Width          int           `json:"width"`
	Height         int           `json:"height"`
}

func normalizeImageVariantCreateRequest(
	mediaID uuid.UUID,
	items []createImageVariantRequest,
) ([]persistence.UploadImageVariantExpectation, int64, string, bool) {
	if mediaID == uuid.Nil || len(items) != 3 {
		return nil, 0, "", false
	}
	byKind := make(map[media.Variant]createImageVariantRequest, 3)
	var total int64
	for _, item := range items {
		if item.Kind != media.VariantSmall && item.Kind != media.VariantMedium && item.Kind != media.VariantLarge {
			return nil, 0, "", false
		}
		if _, duplicate := byKind[item.Kind]; duplicate || item.ContentLength <= 0 ||
			item.ContentLength > 1<<20 || item.Width <= 0 || item.Height <= 0 ||
			!checksumPattern.MatchString(item.ChecksumSHA256) {
			return nil, 0, "", false
		}
		byKind[item.Kind] = item
		total += item.ContentLength
		if total > 1<<20 {
			return nil, 0, "", false
		}
	}
	canonical := []media.Variant{media.VariantSmall, media.VariantMedium, media.VariantLarge}
	expectations := make([]persistence.UploadImageVariantExpectation, 0, 3)
	manifest := strings.Builder{}
	manifest.WriteString("rwms-image-variants-v1\n")
	for _, variant := range canonical {
		item, found := byKind[variant]
		if !found {
			return nil, 0, "", false
		}
		manifest.WriteString(fmt.Sprintf("%s:%d:%s:%dx%d\n", variant, item.ContentLength,
			item.ChecksumSHA256, item.Width, item.Height))
		expectations = append(expectations, persistence.UploadImageVariantExpectation{
			Variant: variant, ContentLength: item.ContentLength, ChecksumSHA256: item.ChecksumSHA256,
			Width: item.Width, Height: item.Height,
			ObjectKey: media.ImageVariantObjectKey(mediaID.String(), 1, variant),
		})
	}
	digest := sha256.Sum256([]byte(manifest.String()))
	return expectations, total, hex.EncodeToString(digest[:]), true
}

func (server *Server) createUpload(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.mediaPrincipal(response, request)
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
	var clientReferenceID *uuid.UUID
	if persistence.IsWorkerEvidenceOwnerType(body.OwnerType) {
		parsed, parseErr := uuid.Parse(body.ClientReferenceID)
		if parseErr != nil || parsed == uuid.Nil || parsed.String() != body.ClientReferenceID {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid upload request")
			return
		}
		clientReferenceID = &parsed
	} else if body.ClientReferenceID != "" {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid upload request")
		return
	}
	if !validFileName(body.FileName) {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_FILE_NAME", "Invalid file name")
		return
	}
	var authorizedSubjectID *uuid.UUID
	if principal.isCustomerRental() {
		if !persistence.IsCustomerSubjectBoundOwnerType(body.OwnerType) {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		authorizedSubjectID = authorizedSubjectFor(principal)
	} else if principal.isWorker() {
		if _, err := principal.requireWorkerOwner(body.OwnerType, ownerID, warehouseID); err != nil {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
	} else if principal.user == nil || body.OwnerType == persistence.OwnerTypeLogisticsCustomerProfile ||
		principal.user.Require("rwms.write", warehouseID, auth.Edit) != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return
	}
	if strings.TrimSpace(body.FileName) == "" || len(body.FileName) > 512 || body.SortOrder < 0 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid upload request")
		return
	}
	fileName := strings.TrimSpace(body.FileName)
	mediaID := uuid.New()
	uploadMode := persistence.UploadModeSource
	contentType := normalizeContentType(body.ContentType)
	contentLength := body.ContentLength
	checksumSHA256 := body.ChecksumSHA256
	var imageVariants []persistence.UploadImageVariantExpectation
	var kind media.Kind
	if len(body.ImageVariants) > 0 {
		if body.ContentType != "" || body.ContentLength != 0 || body.ChecksumSHA256 != "" {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid image variant upload request")
			return
		}
		var valid bool
		imageVariants, contentLength, checksumSHA256, valid = normalizeImageVariantCreateRequest(mediaID, body.ImageVariants)
		if !valid {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid image variant upload request")
			return
		}
		uploadMode = persistence.UploadModeImageVariants
		contentType = "image/webp"
		kind = media.KindImage
	} else {
		if body.ContentLength <= 0 || body.ContentLength > server.config.MaxUploadBytes ||
			!checksumPattern.MatchString(body.ChecksumSHA256) {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid upload request")
			return
		}
		var accepted bool
		kind, accepted = media.KindForContentType(contentType)
		if !accepted {
			server.problem(response, request, http.StatusUnsupportedMediaType, "MEDIA_UNSUPPORTED_TYPE", "Unsupported media type")
			return
		}
	}
	if _, allowed := server.config.AllowedMIMETypes[contentType]; !allowed {
		server.problem(response, request, http.StatusUnsupportedMediaType, "MEDIA_UNSUPPORTED_TYPE", "Unsupported media type")
		return
	}
	if persistence.IsWorkerEvidenceOwnerType(body.OwnerType) && kind != media.KindImage {
		server.problem(response, request, http.StatusUnsupportedMediaType, "MEDIA_UNSUPPORTED_TYPE", "Unsupported media type")
		return
	}
	if body.OwnerType == persistence.OwnerTypeLogisticsCustomerProfile && kind != media.KindImage {
		server.problem(response, request, http.StatusUnsupportedMediaType, "MEDIA_UNSUPPORTED_TYPE", "Unsupported media type")
		return
	}
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
	objectKey := media.IngressObjectKey(mediaID.String(), extensionForContentType(contentType))
	if uploadMode == persistence.UploadModeImageVariants {
		objectKey = media.ImageVariantObjectKey(mediaID.String(), 1, media.VariantLarge)
	}
	fingerprintVariants := make([]map[string]any, 0, len(imageVariants))
	for _, item := range imageVariants {
		fingerprintVariants = append(fingerprintVariants, map[string]any{
			"kind": item.Variant, "contentLength": item.ContentLength,
			"checksumSha256": item.ChecksumSHA256, "width": item.Width, "height": item.Height,
		})
	}
	fingerprintPayload := map[string]any{
		"ownerType": body.OwnerType, "ownerId": body.OwnerID, "documentId": body.DocumentID,
		"lineId": body.LineID, "warehouseId": warehouseID,
		"clientReferenceId": body.ClientReferenceID,
		"context":           body.Context, "folderId": body.FolderID,
		"fileName": fileName, "contentType": contentType, "contentLength": contentLength,
		"checksumSha256": checksumSHA256, "sortOrder": body.SortOrder,
	}
	if len(fingerprintVariants) > 0 {
		fingerprintPayload["imageVariants"] = fingerprintVariants
	}
	fingerprint := requestFingerprint(fingerprintPayload)
	asset, replayed, err := server.repository.CreateUpload(request.Context(), persistence.CreateUploadCommand{
		MediaID: mediaID, FolderID: folderID, UploadSessionID: sessionID, SubjectID: principal.subjectID,
		PrincipalType: principal.principalType, Actor: principal.actor, WorkerID: workerIDFor(principal),
		IdempotencyKey: idempotencyKey, RequestSHA256: fingerprint, OwnerType: body.OwnerType,
		OwnerID: ownerID, WarehouseID: warehouseID, AuthorizedSubjectID: authorizedSubjectID,
		ClientReferenceID: clientReferenceID, Kind: kind, FileName: fileName,
		ContentType: contentType, ContentLength: contentLength, ChecksumSHA256: checksumSHA256,
		UploadMode: uploadMode, ImageVariants: imageVariants,
		SortOrder: body.SortOrder, SourceObjectKey: objectKey,
		UploadExpiresAt: time.Now().UTC().Add(server.config.UploadExpiry), CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !replayed {
		server.publishMediaChange(asset, "MEDIA_CHANGED")
	}
	status := http.StatusCreated
	if replayed {
		status = http.StatusOK
	}
	response.Header().Set("Cache-Control", "no-store")
	var contentUploadURL any = "/api/media/v1/upload-sessions/" + asset.UploadSessionID.String() + "/content"
	variantUploadURLs := make([]map[string]any, 0, 3)
	if asset.UploadMode == persistence.UploadModeImageVariants {
		contentUploadURL = nil
		for _, variant := range []media.Variant{media.VariantSmall, media.VariantMedium, media.VariantLarge} {
			variantUploadURLs = append(variantUploadURLs, map[string]any{
				"kind": variant,
				"contentUploadUrl": "/api/media/v1/upload-sessions/" + asset.UploadSessionID.String() +
					"/variants/" + string(variant) + "/content",
			})
		}
	}
	writeJSON(response, status, map[string]any{
		"uploadSessionId": asset.UploadSessionID, "mediaId": asset.ID,
		"expiresAt":        asset.UploadExpiresAt,
		"contentUploadUrl": contentUploadURL, "variantUploadUrls": variantUploadURLs,
	})
}

type uploadedObjectResponse struct {
	ObjectVersionID string `json:"objectVersionId"`
	ETag            string `json:"etag"`
	ChecksumSHA256  string `json:"checksumSha256"`
}

// uploadSessionContent is the compatibility source byte-ingress path for the
// panel, video and retained source clients. It streams the exact authorized
// body to private MinIO and commits finalization before acknowledging the
// upload, so a session cannot accept a second production object. The regular
// completion endpoint then provides an exact idempotent confirmation using the
// same key and immutable object metadata.
func (server *Server) uploadSessionContent(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.mediaPrincipal(response, request)
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

	var asset persistence.AssetRecord
	if principal.isCustomerRental() {
		asset, err = server.repository.UploadSessionForCustomer(request.Context(), sessionID, principal.subjectID)
	} else {
		asset, err = server.repository.UploadSessionForPrincipal(request.Context(), sessionID, principal.subjectID, principal.principalType)
	}
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !principal.isCustomerRental() && !server.authorizeUploadAsset(response, request, principal, asset) {
		return
	}

	if asset.UploadCompletedAt != nil {
		server.confirmContentReplay(response, request, asset, sessionID, principal, idempotencyKey)
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
		SessionID: sessionID, SubjectID: principal.subjectID, PrincipalType: principal.principalType,
		Actor: principal.actor, WorkerID: workerIDFor(principal), AuthorizedSubjectID: authorizedSubjectFor(principal),
		IdempotencyKey: idempotencyKey,
		RequestSHA256:  fingerprint, ObjectVersionID: finalizeRequest.ObjectVersionID,
		ETag: finalizeRequest.ETag, ChecksumSHA256: finalizeRequest.ChecksumSHA256,
		ContentType: verified.ContentType, SizeBytes: verified.SizeBytes, CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !replayed {
		server.publishMediaChange(asset, "MEDIA_CHANGED")
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

// uploadSessionImageVariantContent streams one already encoded WebP directly
// to private object storage. It verifies the declared size, checksum, object
// version and ETag, then records only immutable metadata in PostgreSQL; no Go
// image decoder or transformer is involved.
func (server *Server) uploadSessionImageVariantContent(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.mediaPrincipal(response, request)
	if !ok {
		return
	}
	sessionID, sessionErr := uuid.Parse(request.PathValue("uploadSessionId"))
	variant := media.Variant(request.PathValue("variant"))
	if sessionErr != nil || (variant != media.VariantSmall && variant != media.VariantMedium && variant != media.VariantLarge) {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid image variant upload")
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	var asset persistence.AssetRecord
	var part persistence.UploadImageVariantPart
	var err error
	if principal.isCustomerRental() {
		asset, part, err = server.repository.UploadImageVariantForCustomer(
			request.Context(), sessionID, principal.subjectID, variant)
	} else {
		asset, part, err = server.repository.UploadImageVariantForPrincipal(
			request.Context(), sessionID, principal.subjectID, principal.principalType, variant)
	}
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !principal.isCustomerRental() && !server.authorizeUploadAsset(response, request, principal, asset) {
		return
	}
	release, err := server.repository.AcquireUploadImageVariantContentLock(request.Context(), asset.ID, variant)
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	defer func() {
		if releaseErr := release(); releaseErr != nil {
			server.logger.Error("release upload image variant lock",
				"correlationId", correlationID(request.Context()), "error", safeError(releaseErr))
		}
	}()
	if principal.isCustomerRental() {
		asset, part, err = server.repository.UploadImageVariantForCustomer(
			request.Context(), sessionID, principal.subjectID, variant)
	} else {
		asset, part, err = server.repository.UploadImageVariantForPrincipal(
			request.Context(), sessionID, principal.subjectID, principal.principalType, variant)
	}
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !principal.isCustomerRental() && !server.authorizeUploadAsset(response, request, principal, asset) {
		return
	}
	if part.UploadedAt != nil {
		_, replayed, completeErr := server.repository.CompleteUploadImageVariant(request.Context(),
			persistence.CompleteUploadImageVariantCommand{
				SessionID: sessionID, SubjectID: principal.subjectID, PrincipalType: principal.principalType,
				AuthorizedSubjectID: authorizedSubjectFor(principal), MediaID: asset.ID,
				Variant: variant, IdempotencyKey: idempotencyKey,
				ObjectVersionID: part.ObjectVersionID, ETag: part.ETag,
				ChecksumSHA256: part.ChecksumSHA256, SizeBytes: part.ContentLength,
			})
		if completeErr != nil {
			server.repositoryProblem(response, request, completeErr)
			return
		}
		if !replayed {
			server.problem(response, request, http.StatusConflict, "MEDIA_CONFLICT", "Media command conflicts with current state")
			return
		}
		response.Header().Set("Cache-Control", "no-store")
		writeJSON(response, http.StatusOK, uploadedObjectResponse{
			ObjectVersionID: part.ObjectVersionID, ETag: part.ETag, ChecksumSHA256: part.ChecksumSHA256,
		})
		return
	}
	if asset.UploadCompletedAt != nil || !time.Now().Before(asset.UploadExpiresAt) {
		server.problem(response, request, http.StatusConflict, "MEDIA_UPLOAD_EXPIRED", "Upload session has expired")
		return
	}
	contentType := normalizeContentType(request.Header.Get("Content-Type"))
	knownLengthMismatch := request.ContentLength != -1 &&
		(request.ContentLength <= 0 || request.ContentLength != part.ContentLength)
	if contentType != "image/webp" || part.ContentLength <= 0 || part.ContentLength > 1<<20 || knownLengthMismatch {
		server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
		return
	}
	hash := sha256.New()
	bounded := &boundedUploadReader{source: request.Body, remaining: part.ContentLength}
	metadata, err := server.store.PutIngressVersion(request.Context(), part.ObjectKey,
		io.TeeReader(bounded, hash), part.ContentLength, "image/webp", part.ChecksumSHA256)
	if err != nil {
		if bounded.underflow || errors.Is(err, errUploadBodyLength) {
			server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
			return
		}
		server.logger.Error("stream immutable image variant", "correlationId", correlationID(request.Context()), "error", safeError(err))
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
	if bounded.remaining != 0 || trailingBytes != 0 || checksum != part.ChecksumSHA256 ||
		metadata.SizeBytes != part.ContentLength || normalizeContentType(metadata.ContentType) != "image/webp" ||
		metadata.VersionID == "" || normalizeETag(metadata.ETag) == "" {
		server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
		return
	}
	stored, statErr := server.store.StatVersion(request.Context(), part.ObjectKey, metadata.VersionID)
	if statErr != nil {
		server.logger.Error("verify immutable image variant", "correlationId", correlationID(request.Context()), "error", safeError(statErr))
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}
	if stored.VersionID != metadata.VersionID || stored.SizeBytes != part.ContentLength ||
		normalizeContentType(stored.ContentType) != "image/webp" || normalizeETag(stored.ETag) != normalizeETag(metadata.ETag) {
		server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
		return
	}
	part, replayed, err := server.repository.CompleteUploadImageVariant(request.Context(),
		persistence.CompleteUploadImageVariantCommand{
			SessionID: sessionID, SubjectID: principal.subjectID, PrincipalType: principal.principalType,
			AuthorizedSubjectID: authorizedSubjectFor(principal), MediaID: asset.ID,
			Variant: variant, IdempotencyKey: idempotencyKey,
			ObjectVersionID: metadata.VersionID, ETag: normalizeETag(metadata.ETag),
			ChecksumSHA256: checksum, SizeBytes: part.ContentLength,
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
		ObjectVersionID: part.ObjectVersionID, ETag: part.ETag, ChecksumSHA256: part.ChecksumSHA256,
	})
}

func (server *Server) authorizeUploadAsset(response http.ResponseWriter, request *http.Request, principal mediaRequestPrincipal, asset persistence.AssetRecord) bool {
	if principal.isCustomerRental() {
		return false
	}
	if principal.isWorker() {
		ownerID, err := principal.requireWorkerOwner(asset.OwnerType, asset.OwnerID, asset.WarehouseID)
		if err != nil {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return false
		}
		if asset.OwnerType == persistence.OwnerTypeTaskBoardEntry {
			err = server.repository.AuthorizeTaskBoardEntryWorker(request.Context(), ownerID, asset.WarehouseID, principal.worker.WorkerID)
		} else {
			err = server.repository.AuthorizeDriverShiftWorker(request.Context(), ownerID, asset.WarehouseID, principal.worker.WorkerID)
		}
		if err != nil {
			server.repositoryProblem(response, request, err)
			return false
		}
		return true
	}
	if principal.user == nil || principal.user.Require("rwms.write", asset.WarehouseID, auth.Edit) != nil {
		server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
		return false
	}
	return true
}

func (server *Server) confirmContentReplay(
	response http.ResponseWriter,
	request *http.Request,
	asset persistence.AssetRecord,
	sessionID uuid.UUID, principal mediaRequestPrincipal, idempotencyKey uuid.UUID,
) {
	finalizeRequest := finalizeUploadRequest{
		ObjectVersionID: asset.SourceVersionID,
		ETag:            asset.SourceETag,
		ChecksumSHA256:  asset.SourceChecksum,
	}
	asset, replayed, err := server.repository.FinalizeUpload(request.Context(), persistence.FinalizeCommand{
		SessionID: sessionID, SubjectID: principal.subjectID, PrincipalType: principal.principalType,
		Actor: principal.actor, WorkerID: workerIDFor(principal), AuthorizedSubjectID: authorizedSubjectFor(principal),
		IdempotencyKey:  idempotencyKey,
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
	ObjectVersionID string                        `json:"objectVersionId"`
	ETag            string                        `json:"etag"`
	ChecksumSHA256  string                        `json:"checksumSha256"`
	Variants        []finalizeImageVariantRequest `json:"variants"`
}

// finalizeImageVariantRequest echoes the immutable object metadata returned by
// one successful variant PUT.
type finalizeImageVariantRequest struct {
	Kind            media.Variant `json:"kind"`
	ObjectVersionID string        `json:"objectVersionId"`
	ETag            string        `json:"etag"`
	ChecksumSHA256  string        `json:"checksumSha256"`
}

func normalizeImageVariantFinalizeRequest(
	items []finalizeImageVariantRequest,
) ([]persistence.FinalizeImageVariant, []finalizeImageVariantRequest, bool) {
	if len(items) != 3 {
		return nil, nil, false
	}
	byKind := make(map[media.Variant]finalizeImageVariantRequest, 3)
	for _, item := range items {
		item.ObjectVersionID = strings.TrimSpace(item.ObjectVersionID)
		item.ETag = normalizeETag(item.ETag)
		if item.Kind != media.VariantSmall && item.Kind != media.VariantMedium && item.Kind != media.VariantLarge {
			return nil, nil, false
		}
		if _, duplicate := byKind[item.Kind]; duplicate || item.ObjectVersionID == "" ||
			len(item.ObjectVersionID) > 255 || item.ETag == "" || len(item.ETag) > 255 ||
			!checksumPattern.MatchString(item.ChecksumSHA256) {
			return nil, nil, false
		}
		byKind[item.Kind] = item
	}
	commands := make([]persistence.FinalizeImageVariant, 0, 3)
	canonical := make([]finalizeImageVariantRequest, 0, 3)
	for _, variant := range []media.Variant{media.VariantSmall, media.VariantMedium, media.VariantLarge} {
		item, found := byKind[variant]
		if !found {
			return nil, nil, false
		}
		canonical = append(canonical, item)
		commands = append(commands, persistence.FinalizeImageVariant{
			Variant: item.Kind, ObjectVersionID: item.ObjectVersionID,
			ETag: item.ETag, ChecksumSHA256: item.ChecksumSHA256,
		})
	}
	return commands, canonical, true
}

func finalizeFingerprint(sessionID uuid.UUID, body finalizeUploadRequest) string {
	payload := map[string]any{
		"uploadSessionId": sessionID, "objectVersionId": body.ObjectVersionID,
		"etag": body.ETag, "checksumSha256": body.ChecksumSHA256,
	}
	if len(body.Variants) > 0 {
		payload["variants"] = body.Variants
	}
	return requestFingerprint(payload)
}

func (server *Server) finalizeUpload(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.mediaPrincipal(response, request)
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
	var imageVariants []persistence.FinalizeImageVariant
	if len(body.Variants) > 0 {
		if body.ObjectVersionID != "" || body.ETag != "" || body.ChecksumSHA256 != "" {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid finalize request")
			return
		}
		var canonical []finalizeImageVariantRequest
		var valid bool
		imageVariants, canonical, valid = normalizeImageVariantFinalizeRequest(body.Variants)
		if !valid {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid finalize request")
			return
		}
		body.Variants = canonical
	} else {
		body.ObjectVersionID = strings.TrimSpace(body.ObjectVersionID)
		body.ETag = normalizeETag(body.ETag)
		if body.ObjectVersionID == "" || len(body.ObjectVersionID) > 255 || body.ETag == "" || len(body.ETag) > 255 ||
			!checksumPattern.MatchString(body.ChecksumSHA256) {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid finalize request")
			return
		}
	}
	var asset persistence.AssetRecord
	if principal.isCustomerRental() {
		asset, err = server.repository.UploadSessionForCustomer(request.Context(), sessionID, principal.subjectID)
	} else {
		asset, err = server.repository.UploadSessionForPrincipal(request.Context(), sessionID, principal.subjectID, principal.principalType)
	}
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !principal.isCustomerRental() && !server.authorizeUploadAsset(response, request, principal, asset) {
		return
	}
	uploadMode := asset.UploadMode
	if uploadMode == "" {
		uploadMode = persistence.UploadModeSource
	}
	if (uploadMode == persistence.UploadModeImageVariants) != (len(imageVariants) == 3) {
		server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
		return
	}
	fingerprint := finalizeFingerprint(sessionID, body)
	if asset.UploadCompletedAt != nil {
		asset, replayed, err := server.repository.FinalizeUpload(request.Context(), persistence.FinalizeCommand{
			SessionID: sessionID, SubjectID: principal.subjectID, PrincipalType: principal.principalType,
			Actor: principal.actor, WorkerID: workerIDFor(principal), AuthorizedSubjectID: authorizedSubjectFor(principal),
			IdempotencyKey: idempotencyKey,
			RequestSHA256:  fingerprint, ObjectVersionID: body.ObjectVersionID,
			ETag: body.ETag, ChecksumSHA256: body.ChecksumSHA256, ImageVariants: imageVariants,
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
	if uploadMode == persistence.UploadModeSource && asset.ExpectedChecksum != body.ChecksumSHA256 {
		server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
		return
	}
	metadata := media.ObjectMetadata{ContentType: asset.ContentType, SizeBytes: asset.ExpectedLength}
	if uploadMode == persistence.UploadModeSource {
		metadata, err = server.verifyObject(request.Context(), asset, body)
		if err != nil {
			if errors.Is(err, errObjectMismatch) {
				server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
				return
			}
			server.logger.Error("verify immutable object", "correlationId", correlationID(request.Context()), "error", safeError(err))
			server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
			return
		}
	}
	asset, replayed, err := server.repository.FinalizeUpload(request.Context(), persistence.FinalizeCommand{
		SessionID: sessionID, SubjectID: principal.subjectID, PrincipalType: principal.principalType,
		Actor: principal.actor, WorkerID: workerIDFor(principal), AuthorizedSubjectID: authorizedSubjectFor(principal),
		IdempotencyKey: idempotencyKey,
		RequestSHA256:  fingerprint, ObjectVersionID: body.ObjectVersionID,
		ETag: body.ETag, ChecksumSHA256: body.ChecksumSHA256,
		ContentType: metadata.ContentType, SizeBytes: metadata.SizeBytes,
		ImageVariants: imageVariants, CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !replayed {
		server.publishMediaChange(asset, "MEDIA_CHANGED")
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
	principal, ok := server.mediaPrincipal(response, request)
	if !ok {
		return
	}
	query := request.URL.Query()
	ownerType, ownerID, warehouseID, ok := server.ownerScope(response, request)
	if !ok {
		return
	}
	var workerOwnerID uuid.UUID
	if principal.isCustomerRental() {
		if !persistence.IsCustomerSubjectBoundOwnerType(ownerType) {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
	} else if principal.isWorker() {
		ownerUUID, err := principal.requireWorkerOwner(ownerType, ownerID, warehouseID)
		if err != nil {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		workerOwnerID = ownerUUID
	} else if principal.user == nil || ownerType == persistence.OwnerTypeLogisticsCustomerProfile ||
		principal.user.Require("rwms.read", warehouseID, auth.View) != nil {
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
	consume := func(records []persistence.AssetWithVariants) error {
		assets = make([]persistence.AssetRecord, len(records))
		for index := range records {
			assets[index] = records[index].Asset
			variants := safeVariants(records[index], ownerType, ownerID, warehouseID)
			items = append(items, assetResponse(records[index].Asset, variants))
		}
		return nil
	}
	if principal.isCustomerRental() {
		err = server.repository.ReadOwnerAssetsForCustomer(request.Context(), ownerType, ownerID,
			warehouseID, principal.subjectID, limit, after, consume)
	} else if principal.isWorker() {
		if ownerType == persistence.OwnerTypeTaskBoardEntry {
			err = server.repository.ReadTaskBoardEntryAssetsForWorker(request.Context(), workerOwnerID,
				warehouseID, principal.worker.WorkerID, limit, after, consume)
		} else {
			err = server.repository.ReadDriverShiftAssetsForWorker(request.Context(), workerOwnerID,
				warehouseID, principal.worker.WorkerID, limit, after, consume)
		}
	} else {
		err = server.repository.ReadOwnerAssets(request.Context(), ownerType, ownerID, warehouseID,
			limit, after, consume)
	}
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
				if record.Variant != nil {
					cover = publicVariantResponse(record.MediaID, record.Generation,
						*record.Variant, persistence.OwnerTypeCabin,
						record.CabinID.String(), warehouseID)
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
	principal, ok := server.mediaPrincipal(response, request)
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
	var generation *int
	if rawGeneration := request.URL.Query().Get("generation"); rawGeneration != "" {
		parsed, parseErr := strconv.Atoi(rawGeneration)
		if parseErr != nil || parsed <= 0 {
			server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid media generation")
			return
		}
		generation = &parsed
	}
	var selectedAsset persistence.AssetRecord
	var selectedOriginal *persistence.VariantRecord
	consume := func(asset persistence.AssetRecord, original *persistence.VariantRecord) error {
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
	}
	if principal.isCustomerRental() {
		if !persistence.IsCustomerSubjectBoundOwnerType(ownerType) {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		err = server.repository.ReadOriginalForCustomer(request.Context(), mediaID, ownerType, ownerID,
			warehouseID, principal.subjectID, generation, consume)
	} else if principal.isWorker() {
		workerOwnerID, workerErr := principal.requireWorkerOwner(ownerType, ownerID, warehouseID)
		if workerErr != nil {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		if ownerType == persistence.OwnerTypeTaskBoardEntry {
			err = server.repository.ReadTaskBoardEntryOriginalForWorker(request.Context(), workerOwnerID, warehouseID,
				principal.worker.WorkerID, mediaID, generation, consume)
		} else {
			err = server.repository.ReadDriverShiftOriginalForWorker(request.Context(), workerOwnerID, warehouseID,
				principal.worker.WorkerID, mediaID, generation, consume)
		}
	} else {
		if principal.user == nil || ownerType == persistence.OwnerTypeLogisticsCustomerProfile ||
			principal.user.Require("rwms.read", warehouseID, auth.View) != nil {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		err = server.repository.ReadOriginal(request.Context(), mediaID, ownerType, ownerID, warehouseID, generation, consume)
	}
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
	principal, ok := server.mediaPrincipal(response, request)
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
	var selectedAsset persistence.AssetRecord
	var selectedVariant *persistence.VariantRecord
	consume := func(asset persistence.AssetRecord, record *persistence.VariantRecord) error {
		if asset.Status != media.StatusReady || asset.Generation != generation {
			return errMediaNotReady
		}
		if record == nil || record.Variant != variant || record.ObjectVersionID == "" ||
			!publicVariantMatchesKind(asset.Kind, variant) {
			return errOriginalMissing
		}
		selectedAsset = asset
		copyOfVariant := *record
		selectedVariant = &copyOfVariant
		return nil
	}
	if principal.isCustomerRental() {
		if !persistence.IsCustomerSubjectBoundOwnerType(ownerType) {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		err = server.repository.ReadCurrentVariantForCustomer(request.Context(), mediaID, ownerType, ownerID,
			warehouseID, principal.subjectID, generation, variant, consume)
	} else if principal.isWorker() {
		workerOwnerID, workerErr := principal.requireWorkerOwner(ownerType, ownerID, warehouseID)
		if workerErr != nil {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		if ownerType == persistence.OwnerTypeTaskBoardEntry {
			err = server.repository.ReadTaskBoardEntryVariantForWorker(request.Context(), workerOwnerID, warehouseID,
				principal.worker.WorkerID, mediaID, generation, variant, consume)
		} else {
			err = server.repository.ReadDriverShiftVariantForWorker(request.Context(), workerOwnerID, warehouseID,
				principal.worker.WorkerID, mediaID, generation, variant, consume)
		}
	} else {
		if principal.user == nil || ownerType == persistence.OwnerTypeLogisticsCustomerProfile ||
			principal.user.Require("rwms.read", warehouseID, auth.View) != nil {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		err = server.repository.ReadCurrentVariant(request.Context(), mediaID, ownerType, ownerID,
			warehouseID, generation, variant, consume)
	}
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

type deletionRequest struct {
	ExpectedVersion *int64 `json:"expectedVersion"`
}

func (server *Server) deleteAsset(response http.ResponseWriter, request *http.Request) {
	principal, ok := server.mediaPrincipal(response, request)
	if !ok {
		return
	}
	ownerType, ownerID, warehouseID, ok := server.ownerScope(response, request)
	if !ok {
		return
	}
	var authorizedSubjectID *uuid.UUID
	if principal.isCustomerRental() {
		if !persistence.IsCustomerSubjectBoundOwnerType(ownerType) {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return
		}
		authorizedSubjectID = authorizedSubjectFor(principal)
	} else if principal.isWorker() || principal.user == nil ||
		ownerType == persistence.OwnerTypeLogisticsCustomerProfile ||
		principal.user.Require("rwms.write", warehouseID, auth.Edit) != nil {
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
	asset, replayed, err := server.repository.Delete(request.Context(), persistence.DeleteCommand{
		MediaID: mediaID, OwnerType: ownerType, OwnerID: ownerID, WarehouseID: warehouseID,
		SubjectID: principal.subjectID, AuthorizedSubjectID: authorizedSubjectID, IdempotencyKey: idempotencyKey,
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
	if !replayed {
		server.publishMediaChange(asset, "MEDIA_CHANGED")
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
		if variant.Variant == media.VariantOriginal || variant.ObjectVersionID == "" ||
			!publicVariantMatchesKind(record.Asset.Kind, variant.Variant) {
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
	case media.VariantSmall, media.VariantMedium, media.VariantLarge, media.VariantPlayback:
		return variant, true
	default:
		return "", false
	}
}

func publicVariantMatchesKind(kind media.Kind, variant media.Variant) bool {
	if kind == media.KindVideo {
		return variant == media.VariantPlayback
	}
	return kind == media.KindImage &&
		(variant == media.VariantSmall || variant == media.VariantMedium || variant == media.VariantLarge)
}

func assetResponse(asset persistence.AssetRecord, variants []any) map[string]any {
	if variants == nil {
		variants = []any{}
	}
	return map[string]any{
		"id": asset.ID, "folderId": asset.FolderID, "clientReferenceId": asset.ClientReferenceID,
		"fileName": asset.FileName, "contentType": asset.ContentType,
		"kind": asset.Kind, "status": asset.Status, "version": asset.Version,
		"generation": asset.Generation, "rotationDegrees": asset.Rotation,
		"sortOrder": asset.SortOrder, "sizeBytes": asset.SizeBytes, "createdAt": asset.CreatedAt,
		"variants": variants,
	}
}

func (server *Server) publishMediaChange(asset persistence.AssetRecord, scope string) {
	if server.invalidations == nil || asset.WarehouseID == uuid.Nil {
		return
	}
	ownerType, ownerID := "", ""
	if asset.OwnerType == persistence.OwnerTypeCabin {
		ownerType, ownerID = asset.OwnerType, asset.OwnerID
	}
	server.invalidations.Publish(realtime.Event{
		EventID: uuid.New(), WarehouseID: asset.WarehouseID, Scope: scope,
		MediaID: asset.ID, OwnerType: ownerType, OwnerID: ownerID,
		Generation: asset.Generation, Revision: asset.Version, OccurredAt: time.Now().UTC(),
	})
}

func (server *Server) publishCabinCoverChange(record persistence.CabinCoverChangeRecord) {
	if server.invalidations == nil || record.WarehouseID == uuid.Nil {
		return
	}
	server.invalidations.Publish(realtime.Event{
		EventID: uuid.New(), WarehouseID: record.WarehouseID, Scope: "CABIN_COVER_CHANGED",
		MediaID: record.MediaID, OwnerType: persistence.OwnerTypeCabin, OwnerID: record.CabinID.String(),
		Generation: record.Generation, Revision: record.Version, OccurredAt: record.ChangedAt,
	})
}

// mediaPrincipal accepts a normal USER token for the established public media
// routes and a narrow WORKER token only where handlers explicitly opt into
// task-board work-result access. Service tokens continue to use the internal
// service-only routes below.
func (server *Server) mediaPrincipal(response http.ResponseWriter, request *http.Request) (mediaRequestPrincipal, bool) {
	authorization := request.Header.Get("Authorization")
	user, userErr := server.auth.Validate(request.Context(), authorization)
	if userErr == nil {
		if user.IsCustomerIdentity() && !user.IsCustomerRental() {
			server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
			return mediaRequestPrincipal{}, false
		}
		return mediaRequestPrincipal{
			subjectID: user.SubjectID, principalType: persistence.PrincipalTypeUser,
			actor: persistence.ActorReference{SubjectID: user.SubjectID, PrincipalType: persistence.PrincipalTypeUser},
			user:  &user,
		}, true
	}
	worker, workerErr := server.auth.ValidateWorker(request.Context(), authorization)
	if workerErr == nil {
		return mediaRequestPrincipal{
			subjectID: worker.SubjectID, principalType: persistence.PrincipalTypeWorker,
			actor:  persistence.ActorReference{SubjectID: worker.WorkerID, PrincipalType: persistence.PrincipalTypeWorker},
			worker: &worker,
		}, true
	}
	status := http.StatusUnauthorized
	code := "MEDIA_UNAUTHORIZED"
	if errors.Is(userErr, auth.ErrForbidden) || errors.Is(workerErr, auth.ErrForbidden) {
		status, code = http.StatusForbidden, "MEDIA_FORBIDDEN"
	}
	server.problem(response, request, status, code, "Access is denied")
	return mediaRequestPrincipal{}, false
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

func (server *Server) inventoryPrincipal(response http.ResponseWriter, request *http.Request) bool {
	principal, ok := server.servicePrincipal(response, request)
	if !ok {
		return false
	}
	if principal.RequireExact("inventory-service", "media.inventory") == nil {
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
