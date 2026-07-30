package api

import (
	"context"
	"errors"
	"io"
	"net/http"
	"sort"

	"dev.buhanzaz.rwms/media-service/internal/assetimport"
	"github.com/google/uuid"
)

// assetImportService is intentionally separate from the general media
// repository interface so established public routes remain unable to call the
// private remote-import command path by accident.
type assetImportService interface {
	Preflight(context.Context, assetimport.CreateCommand) (assetimport.Job, bool, error)
	Get(context.Context, uuid.UUID) (assetimport.Job, error)
	Activate(context.Context, assetimport.ActivateCommand) (assetimport.Job, bool, error)
	ReplacePreflightSources(context.Context, assetimport.ReplaceSourcesCommand) (assetimport.Job, bool, error)
	Retry(context.Context, assetimport.RetryCommand) (assetimport.Job, bool, error)
}

type assetImportPreflightRequest struct {
	AssetImportID string                     `json:"assetImportId"`
	WarehouseID   string                     `json:"warehouseId"`
	Sources       []assetImportSourceRequest `json:"sources"`
}

type assetImportSourceRequest struct {
	SourceRowID string `json:"sourceRowId"`
	PublicURL   string `json:"publicUrl"`
}

type assetImportActivateRequest struct {
	Bindings []assetImportBindingRequest `json:"bindings"`
}

type assetImportReplaceSourcesRequest struct {
	Sources []assetImportSourceRequest `json:"sources"`
}

type assetImportBindingRequest struct {
	SourceRowID string `json:"sourceRowId"`
	CabinID     string `json:"cabinId"`
}

func (server *Server) preflightAssetImport(response http.ResponseWriter, request *http.Request) {
	if !server.assetImportPrincipal(response, request) {
		return
	}
	service, ok := server.assetImportService(response, request)
	if !ok {
		return
	}
	idempotencyKey, ok := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if !ok {
		return
	}
	var body assetImportPreflightRequest
	if !server.decode(response, request, &body) {
		return
	}
	assetImportID, assetImportErr := uuid.Parse(body.AssetImportID)
	warehouseID, warehouseErr := uuid.Parse(body.WarehouseID)
	if assetImportErr != nil || warehouseErr != nil || assetImportID == uuid.Nil || warehouseID == uuid.Nil ||
		len(body.Sources) < 1 || len(body.Sources) > assetimport.MaxSourcesPerJob {
		server.assetImportProblem(response, request, assetimport.ErrConflict)
		return
	}
	sources := make([]assetimport.Source, 0, len(body.Sources))
	seen := make(map[uuid.UUID]struct{}, len(body.Sources))
	for _, source := range body.Sources {
		sourceRowID, rowErr := uuid.Parse(source.SourceRowID)
		publicKey, keyErr := assetimport.ParsePublicURL(source.PublicURL)
		if rowErr != nil || sourceRowID == uuid.Nil || keyErr != nil {
			server.assetImportProblem(response, request, assetimport.ErrConflict)
			return
		}
		if _, duplicate := seen[sourceRowID]; duplicate {
			server.assetImportProblem(response, request, assetimport.ErrConflict)
			return
		}
		seen[sourceRowID] = struct{}{}
		sources = append(sources, assetimport.Source{SourceRowID: sourceRowID, PublicKey: publicKey})
	}
	job, _, err := service.Preflight(request.Context(), assetimport.CreateCommand{
		JobID: uuid.New(), AssetImportID: assetImportID, WarehouseID: warehouseID, IdempotencyKey: idempotencyKey,
		RequestSHA256: assetimport.CanonicalPreflightSHA(assetImportID, warehouseID, sources), Sources: sources,
	})
	if err != nil {
		server.assetImportProblem(response, request, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusAccepted, safeAssetImportJob(job))
}

func (server *Server) getAssetImport(response http.ResponseWriter, request *http.Request) {
	if !server.assetImportPrincipal(response, request) {
		return
	}
	service, ok := server.assetImportService(response, request)
	if !ok {
		return
	}
	jobID, err := uuid.Parse(request.PathValue("jobId"))
	if err != nil || jobID == uuid.Nil {
		server.assetImportProblem(response, request, assetimport.ErrNotFound)
		return
	}
	job, err := service.Get(request.Context(), jobID)
	if err != nil {
		server.assetImportProblem(response, request, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusOK, safeAssetImportJob(job))
}

func (server *Server) activateAssetImport(response http.ResponseWriter, request *http.Request) {
	if !server.assetImportPrincipal(response, request) {
		return
	}
	service, ok := server.assetImportService(response, request)
	if !ok {
		return
	}
	jobID, err := uuid.Parse(request.PathValue("jobId"))
	idempotencyKey, headerOK := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if err != nil || jobID == uuid.Nil || !headerOK {
		if !headerOK {
			return
		}
		server.assetImportProblem(response, request, assetimport.ErrConflict)
		return
	}
	var body assetImportActivateRequest
	if !server.decode(response, request, &body) {
		return
	}
	if len(body.Bindings) < 1 || len(body.Bindings) > assetimport.MaxSourcesPerJob {
		server.assetImportProblem(response, request, assetimport.ErrConflict)
		return
	}
	bindings := make([]assetimport.ActivationBinding, 0, len(body.Bindings))
	seen := make(map[uuid.UUID]struct{}, len(body.Bindings))
	for _, binding := range body.Bindings {
		sourceRowID, sourceErr := uuid.Parse(binding.SourceRowID)
		cabinID, cabinErr := uuid.Parse(binding.CabinID)
		if sourceErr != nil || cabinErr != nil || sourceRowID == uuid.Nil || cabinID == uuid.Nil {
			server.assetImportProblem(response, request, assetimport.ErrConflict)
			return
		}
		if _, duplicate := seen[sourceRowID]; duplicate {
			server.assetImportProblem(response, request, assetimport.ErrConflict)
			return
		}
		seen[sourceRowID] = struct{}{}
		bindings = append(bindings, assetimport.ActivationBinding{SourceRowID: sourceRowID, CabinID: cabinID})
	}
	job, _, err := service.Activate(request.Context(), assetimport.ActivateCommand{
		JobID: jobID, IdempotencyKey: idempotencyKey,
		RequestSHA256: assetimport.CanonicalActivationSHA(jobID, bindings), Bindings: bindings,
	})
	if err != nil {
		server.assetImportProblem(response, request, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusAccepted, safeAssetImportJob(job))
}

func (server *Server) retryAssetImport(response http.ResponseWriter, request *http.Request) {
	if !server.assetImportPrincipal(response, request) {
		return
	}
	service, ok := server.assetImportService(response, request)
	if !ok {
		return
	}
	jobID, err := uuid.Parse(request.PathValue("jobId"))
	idempotencyKey, headerOK := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if err != nil || jobID == uuid.Nil || !headerOK {
		if !headerOK {
			return
		}
		server.assetImportProblem(response, request, assetimport.ErrConflict)
		return
	}
	// Retry has no body. Reject bytes rather than silently accepting an
	// accidental URL-bearing payload that might be logged by an upstream proxy.
	request.Body = http.MaxBytesReader(response, request.Body, 1)
	if _, bodyErr := request.Body.Read(make([]byte, 1)); bodyErr != io.EOF {
		server.assetImportProblem(response, request, assetimport.ErrConflict)
		return
	}
	job, _, err := service.Retry(request.Context(), assetimport.RetryCommand{
		JobID: jobID, IdempotencyKey: idempotencyKey, RequestSHA256: assetimport.CanonicalRetrySHA(jobID, idempotencyKey),
	})
	if err != nil {
		server.assetImportProblem(response, request, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusAccepted, safeAssetImportJob(job))
}

func (server *Server) replaceAssetImportSources(response http.ResponseWriter, request *http.Request) {
	if !server.assetImportPrincipal(response, request) {
		return
	}
	service, ok := server.assetImportService(response, request)
	if !ok {
		return
	}
	jobID, err := uuid.Parse(request.PathValue("jobId"))
	idempotencyKey, headerOK := requireUUIDHeader(response, request, "Idempotency-Key", server)
	if err != nil || jobID == uuid.Nil || !headerOK {
		if !headerOK {
			return
		}
		server.assetImportProblem(response, request, assetimport.ErrConflict)
		return
	}
	var body assetImportReplaceSourcesRequest
	if !server.decode(response, request, &body) {
		return
	}
	if len(body.Sources) < 1 || len(body.Sources) > assetimport.MaxSourcesPerJob {
		server.assetImportProblem(response, request, assetimport.ErrConflict)
		return
	}
	replacements := make([]assetimport.SourceReplacement, 0, len(body.Sources))
	seen := make(map[uuid.UUID]struct{}, len(body.Sources))
	for _, source := range body.Sources {
		sourceRowID, rowErr := uuid.Parse(source.SourceRowID)
		publicKey, keyErr := assetimport.ParsePublicURL(source.PublicURL)
		if rowErr != nil || sourceRowID == uuid.Nil || keyErr != nil {
			server.assetImportProblem(response, request, assetimport.ErrConflict)
			return
		}
		if _, duplicate := seen[sourceRowID]; duplicate {
			server.assetImportProblem(response, request, assetimport.ErrConflict)
			return
		}
		seen[sourceRowID] = struct{}{}
		replacements = append(replacements, assetimport.SourceReplacement{SourceRowID: sourceRowID, PublicKey: publicKey})
	}
	job, _, err := service.ReplacePreflightSources(request.Context(), assetimport.ReplaceSourcesCommand{
		JobID: jobID, IdempotencyKey: idempotencyKey,
		RequestSHA256: assetimport.CanonicalReplaceSourcesSHA(jobID, replacements), Replacements: replacements,
	})
	if err != nil {
		server.assetImportProblem(response, request, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeJSON(response, http.StatusAccepted, safeAssetImportJob(job))
}

func (server *Server) assetImportPrincipal(response http.ResponseWriter, request *http.Request) bool {
	principal, ok := server.servicePrincipal(response, request)
	if !ok {
		return false
	}
	if principal.RequireExact(assetimport.AssetImportService, assetimport.AssetImportScope) == nil {
		return true
	}
	server.problem(response, request, http.StatusForbidden, "MEDIA_FORBIDDEN", "Access is denied")
	return false
}

func (server *Server) assetImportService(response http.ResponseWriter, request *http.Request) (assetImportService, bool) {
	if server.assetImports != nil {
		return server.assetImports, true
	}
	server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_DEPENDENCY_UNAVAILABLE", "A required dependency is unavailable")
	return nil, false
}

func (server *Server) assetImportProblem(response http.ResponseWriter, request *http.Request, err error) {
	switch {
	case errors.Is(err, assetimport.ErrNotFound):
		server.problem(response, request, http.StatusNotFound, "MEDIA_ASSET_IMPORT_NOT_FOUND", "Asset import job was not found")
	case errors.Is(err, assetimport.ErrOwnerProofMissing):
		server.problem(response, request, http.StatusConflict, "MEDIA_ASSET_IMPORT_OWNER_UNAVAILABLE", "Cabin owner authorization is unavailable")
	case errors.Is(err, assetimport.ErrConflict), errors.Is(err, assetimport.ErrIdempotencyMismatch):
		server.problem(response, request, http.StatusConflict, "MEDIA_ASSET_IMPORT_CONFLICT", "Asset import command conflicts with current state")
	default:
		// External error text may contain a public key or a signed temporary
		// Yandex URL. Do not pass it to the logger or problem detail.
		server.problem(response, request, http.StatusServiceUnavailable, "MEDIA_DEPENDENCY_UNAVAILABLE", "A required dependency is unavailable")
	}
}

func safeAssetImportJob(job assetimport.Job) map[string]any {
	type sourceResult struct {
		SourceRowID  string      `json:"sourceRowId"`
		Prepared     int         `json:"prepared"`
		Downloading  int         `json:"downloading"`
		Imported     int         `json:"imported"`
		Skipped      int         `json:"skipped"`
		Failed       int         `json:"failed"`
		MediaIDs     []uuid.UUID `json:"mediaIds"`
		WarningCodes []string    `json:"warningCodes"`
	}
	results := make(map[uuid.UUID]*sourceResult, len(job.Sources))
	for _, source := range job.Sources {
		results[source.SourceRowID] = &sourceResult{SourceRowID: source.SourceRowID.String(), MediaIDs: []uuid.UUID{}, WarningCodes: []string{}}
	}
	for _, entry := range job.Entries {
		result, exists := results[entry.SourceRowID]
		if !exists {
			continue
		}
		switch entry.Status {
		case assetimport.EntryPrepared:
			result.Prepared++
		case assetimport.EntryDownloading:
			result.Downloading++
		case assetimport.EntryImported:
			result.Imported++
			if entry.MediaID != nil {
				result.MediaIDs = append(result.MediaIDs, *entry.MediaID)
			}
		case assetimport.EntrySkipped:
			result.Skipped++
			if entry.WarningCode != "" {
				result.WarningCodes = append(result.WarningCodes, entry.WarningCode)
			}
		case assetimport.EntryFailed:
			result.Failed++
			if entry.WarningCode != "" {
				result.WarningCodes = append(result.WarningCodes, entry.WarningCode)
			}
		}
	}
	ordered := make([]sourceResult, 0, len(results))
	for _, result := range results {
		sort.Slice(result.MediaIDs, func(left, right int) bool { return result.MediaIDs[left].String() < result.MediaIDs[right].String() })
		result.WarningCodes = sortedDistinctStrings(result.WarningCodes)
		ordered = append(ordered, *result)
	}
	sort.Slice(ordered, func(left, right int) bool { return ordered[left].SourceRowID < ordered[right].SourceRowID })
	result := map[string]any{
		"jobId": job.ID, "assetImportId": job.AssetImportID, "warehouseId": job.WarehouseID,
		"status": job.Status, "preflightAttempts": job.PreflightAttempts,
		"activationAttempts": job.ActivationAttempts, "results": ordered,
	}
	if job.FailureCode != "" {
		result["failureCode"] = job.FailureCode
	}
	return result
}

func sortedDistinctStrings(values []string) []string {
	if len(values) == 0 {
		return []string{}
	}
	sort.Strings(values)
	result := values[:0]
	for _, value := range values {
		if len(result) == 0 || result[len(result)-1] != value {
			result = append(result, value)
		}
	}
	return result
}
