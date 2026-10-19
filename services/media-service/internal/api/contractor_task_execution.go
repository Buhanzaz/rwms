package api

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/google/uuid"
)

const contractorTaskEvidenceOperation = "CONTRACTOR_TASK_EVIDENCE_V1"

// contractorTaskExecutionScope is the exact task-board audience delegated by
// logistics-service for one contractor task evidence request.
type contractorTaskExecutionScope struct {
	entryID     uuid.UUID
	workerID    uuid.UUID
	warehouseID uuid.UUID
}

// contractorTaskEvidenceReceipt exposes only the opaque media identity and
// processing state needed by the trusted logistics-service caller.
type contractorTaskEvidenceReceipt struct {
	MediaID    uuid.UUID    `json:"mediaId"`
	Generation int          `json:"generation"`
	Status     media.Status `json:"status"`
}

// uploadContractorTaskEvidence streams one exact task-board evidence image on
// behalf of the worker selected by logistics-service. The persisted actor and
// every owner-proof recheck remain bound to that worker, not to the calling
// service credential.
func (server *Server) uploadContractorTaskEvidence(response http.ResponseWriter, request *http.Request) {
	if !server.logisticsPrincipal(response, request) {
		return
	}
	scope, ok := server.contractorTaskExecutionScope(response, request)
	if !ok {
		return
	}
	evidenceID, err := canonicalPathUUID(request.PathValue("evidenceId"))
	if err != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid contractor evidence request")
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	if idempotencyKey != evidenceID {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must equal the evidence ID")
		return
	}
	contentType := normalizeContentType(request.Header.Get("Content-Type"))
	if contentType != "image/jpeg" && contentType != "image/webp" {
		server.problem(response, request, http.StatusUnsupportedMediaType, "MEDIA_UNSUPPORTED_TYPE", "Unsupported media type")
		return
	}
	if _, allowed := server.config.AllowedMIMETypes[contentType]; !allowed {
		server.problem(response, request, http.StatusUnsupportedMediaType, "MEDIA_UNSUPPORTED_TYPE", "Unsupported media type")
		return
	}
	contentLength := request.ContentLength
	checksumSHA256 := strings.TrimSpace(request.Header.Get("X-Content-SHA256"))
	if contentLength <= 0 || contentLength > server.config.MaxUploadBytes ||
		!checksumPattern.MatchString(checksumSHA256) || request.Header.Get("Content-Encoding") != "" {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid contractor evidence metadata")
		return
	}

	mediaID := contractorTaskEvidenceMediaID(scope.entryID, scope.workerID, evidenceID)
	fileName := "contractor-task-evidence" + extensionForContentType(contentType)
	objectKey := media.IngressObjectKey(mediaID.String(), extensionForContentType(contentType))
	requestSHA256 := requestFingerprint(map[string]any{
		"operation": contractorTaskEvidenceOperation,
		"entryId":   scope.entryID, "workerId": scope.workerID, "evidenceId": evidenceID,
		"warehouseId": scope.warehouseID, "contentType": contentType,
		"contentLength": contentLength, "checksumSha256": checksumSHA256,
	})
	workerID := scope.workerID
	asset, createReplayed, err := server.repository.CreateUpload(request.Context(), persistence.CreateUploadCommand{
		MediaID: mediaID, FolderID: mediaID, UploadSessionID: uuid.New(),
		SubjectID: scope.workerID, PrincipalType: persistence.PrincipalTypeWorker,
		Actor:    persistence.ActorReference{SubjectID: scope.workerID, PrincipalType: persistence.PrincipalTypeWorker},
		WorkerID: &workerID, IdempotencyKey: evidenceID, RequestSHA256: requestSHA256,
		OwnerType: persistence.OwnerTypeTaskBoardEntry, OwnerID: scope.entryID.String(),
		WarehouseID: scope.warehouseID, ClientReferenceID: &evidenceID,
		Kind: media.KindImage, FileName: fileName, ContentType: contentType,
		ContentLength: contentLength, ChecksumSHA256: checksumSHA256,
		UploadMode: persistence.UploadModeSource, SortOrder: 0, SourceObjectKey: objectKey,
		UploadExpiresAt: time.Now().UTC().Add(server.config.UploadExpiry),
		CorrelationID:   correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !createReplayed {
		server.publishMediaChange(asset, "MEDIA_CHANGED")
	}

	release, err := server.repository.AcquireUploadSessionContentLock(request.Context(), asset.UploadSessionID)
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	defer func() {
		if releaseErr := release(); releaseErr != nil {
			server.logger.Error("release contractor evidence upload lock",
				"correlationId", correlationID(request.Context()), "error", safeError(releaseErr))
		}
	}()

	asset, err = server.repository.UploadSessionForPrincipal(
		request.Context(), asset.UploadSessionID, scope.workerID, persistence.PrincipalTypeWorker)
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !contractorTaskEvidenceAssetMatches(asset, scope, evidenceID, mediaID, fileName,
		contentType, contentLength, checksumSHA256, objectKey) {
		server.problem(response, request, http.StatusConflict, "MEDIA_CONFLICT", "Contractor evidence conflicts with current state")
		return
	}
	if asset.UploadCompletedAt != nil {
		server.writeContractorTaskEvidenceReceipt(response, http.StatusOK, asset)
		return
	}
	if !time.Now().Before(asset.UploadExpiresAt) {
		server.problem(response, request, http.StatusConflict, "MEDIA_UPLOAD_EXPIRED", "Upload session has expired")
		return
	}

	hash := sha256.New()
	bounded := &boundedUploadReader{source: request.Body, remaining: contentLength}
	metadata, err := server.store.PutIngressVersion(request.Context(), objectKey,
		io.TeeReader(bounded, hash), contentLength, contentType, checksumSHA256)
	if err != nil {
		if bounded.underflow || errors.Is(err, errUploadBodyLength) {
			server.problem(response, request, http.StatusConflict, "MEDIA_OBJECT_MISMATCH", "Uploaded object does not match the authorized request")
			return
		}
		server.logger.Error("stream contractor evidence object",
			"correlationId", correlationID(request.Context()), "error", safeError(err))
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
	if bounded.remaining != 0 || trailingBytes != 0 || checksum != checksumSHA256 ||
		metadata.SizeBytes != contentLength || normalizeContentType(metadata.ContentType) != contentType ||
		metadata.VersionID == "" || normalizeETag(metadata.ETag) == "" {
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
		server.logger.Error("verify contractor evidence object",
			"correlationId", correlationID(request.Context()), "error", safeError(err))
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_STORAGE_UNAVAILABLE", "Storage is unavailable")
		return
	}
	asset, finalizeReplayed, err := server.repository.FinalizeUpload(request.Context(), persistence.FinalizeCommand{
		SessionID: asset.UploadSessionID, SubjectID: scope.workerID,
		PrincipalType: persistence.PrincipalTypeWorker,
		Actor:         persistence.ActorReference{SubjectID: scope.workerID, PrincipalType: persistence.PrincipalTypeWorker},
		WorkerID:      &workerID, IdempotencyKey: evidenceID,
		RequestSHA256:   finalizeFingerprint(asset.UploadSessionID, finalizeRequest),
		ObjectVersionID: finalizeRequest.ObjectVersionID, ETag: finalizeRequest.ETag,
		ChecksumSHA256: finalizeRequest.ChecksumSHA256,
		ContentType:    verified.ContentType, SizeBytes: verified.SizeBytes,
		CorrelationID: correlationID(request.Context()),
	})
	if err != nil {
		server.repositoryProblem(response, request, err)
		return
	}
	if !finalizeReplayed {
		server.publishMediaChange(asset, "MEDIA_CHANGED")
	}
	status := http.StatusCreated
	if finalizeReplayed {
		status = http.StatusOK
	}
	server.writeContractorTaskEvidenceReceipt(response, status, asset)
}

// getContractorTaskExecutionVariantContent streams one exact derivative only
// while the current task-board proof still names the requested worker as a
// reader of the requested entry.
func (server *Server) getContractorTaskExecutionVariantContent(response http.ResponseWriter, request *http.Request) {
	if !server.logisticsPrincipal(response, request) {
		return
	}
	scope, ok := server.contractorTaskExecutionScope(response, request)
	if !ok {
		return
	}
	mediaID, mediaErr := canonicalPathUUID(request.PathValue("mediaId"))
	generationValue, generationErr := strconv.ParseInt(request.PathValue("generation"), 10, 32)
	variant := media.Variant(request.PathValue("variant"))
	if mediaErr != nil || generationErr != nil || generationValue <= 0 || generationValue > maxStoredGeneration ||
		(variant != media.VariantSmall && variant != media.VariantMedium && variant != media.VariantLarge) {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid contractor task media request")
		return
	}
	var selected *persistence.VariantRecord
	err := server.repository.ReadTaskBoardEntryVariantForWorker(request.Context(), scope.entryID,
		scope.warehouseID, scope.workerID, mediaID, int(generationValue), variant,
		func(asset persistence.AssetRecord, record *persistence.VariantRecord) error {
			if asset.ID != mediaID || asset.OwnerType != persistence.OwnerTypeTaskBoardEntry ||
				asset.OwnerID != scope.entryID.String() || asset.WarehouseID != scope.warehouseID ||
				asset.Kind != media.KindImage || record == nil || record.Variant != variant ||
				record.ObjectKey == "" || record.ObjectVersionID == "" || record.SizeBytes <= 0 ||
				normalizeContentType(record.ContentType) != "image/webp" {
				return persistence.ErrNotFound
			}
			copyOfRecord := *record
			selected = &copyOfRecord
			return nil
		})
	if err != nil {
		switch {
		case errors.Is(err, persistence.ErrNotFound), errors.Is(err, persistence.ErrOwnerProofMissing):
			server.problem(response, request, http.StatusNotFound, "MEDIA_NOT_FOUND", "Media resource was not found")
		default:
			server.repositoryProblem(response, request, err)
		}
		return
	}
	if selected == nil {
		server.problem(response, request, http.StatusNotFound, "MEDIA_NOT_FOUND", "Media resource was not found")
		return
	}
	server.streamVariant(response, request, "contractor-task-image.webp", *selected)
}

func (server *Server) contractorTaskExecutionScope(
	response http.ResponseWriter,
	request *http.Request,
) (contractorTaskExecutionScope, bool) {
	entryID, entryErr := canonicalPathUUID(request.PathValue("entryId"))
	workerID, workerErr := canonicalPathUUID(request.PathValue("workerId"))
	query := request.URL.Query()
	warehouseValues, warehousePresent := query["warehouseId"]
	if entryErr != nil || workerErr != nil || !warehousePresent || len(warehouseValues) != 1 || len(query) != 1 {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid contractor task scope")
		return contractorTaskExecutionScope{}, false
	}
	warehouseID, warehouseErr := canonicalPathUUID(warehouseValues[0])
	if warehouseErr != nil {
		server.problem(response, request, http.StatusBadRequest, "MEDIA_INVALID_REQUEST", "Invalid contractor task scope")
		return contractorTaskExecutionScope{}, false
	}
	return contractorTaskExecutionScope{entryID: entryID, workerID: workerID, warehouseID: warehouseID}, true
}

func (server *Server) writeContractorTaskEvidenceReceipt(
	response http.ResponseWriter,
	status int,
	asset persistence.AssetRecord,
) {
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, status, contractorTaskEvidenceReceipt{
		MediaID: asset.ID, Generation: asset.Generation, Status: asset.Status,
	})
}

func contractorTaskEvidenceMediaID(entryID, workerID, evidenceID uuid.UUID) uuid.UUID {
	name := contractorTaskEvidenceOperation + ":" + entryID.String() + ":" + workerID.String() + ":" + evidenceID.String()
	return uuid.NewSHA1(uuid.NameSpaceOID, []byte(name))
}

func contractorTaskEvidenceAssetMatches(
	asset persistence.AssetRecord,
	scope contractorTaskExecutionScope,
	evidenceID, mediaID uuid.UUID,
	fileName, contentType string,
	contentLength int64,
	checksumSHA256, objectKey string,
) bool {
	return asset.ID == mediaID && asset.OwnerType == persistence.OwnerTypeTaskBoardEntry &&
		asset.OwnerID == scope.entryID.String() && asset.WarehouseID == scope.warehouseID &&
		asset.Kind == media.KindImage && asset.FileName == fileName && asset.ContentType == contentType &&
		asset.ExpectedLength == contentLength && asset.ExpectedChecksum == checksumSHA256 &&
		asset.UploadMode == persistence.UploadModeSource && asset.SourceObjectKey == objectKey && asset.ClientReferenceID != nil &&
		*asset.ClientReferenceID == evidenceID && asset.CreatedBy != nil &&
		asset.CreatedBy.PrincipalType == persistence.PrincipalTypeWorker &&
		asset.CreatedBy.SubjectID == scope.workerID
}

func canonicalPathUUID(value string) (uuid.UUID, error) {
	parsed, err := uuid.Parse(value)
	if err != nil || parsed == uuid.Nil || parsed.String() != value {
		return uuid.Nil, errors.New("invalid canonical UUID")
	}
	return parsed, nil
}
