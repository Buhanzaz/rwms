package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/maintenance/v1/catalog")
@RequiredArgsConstructor
public class MaintenanceCatalogController {
  private final MaintenanceApplicationService service;
  private final MaintenanceAuthorizer access;

  @GetMapping("/versions")
  public PageResponse<CatalogVersionResponse> versions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) CatalogVersionState lifecycle) {
    access.requireRead(jwt, warehouseId);
    List<CatalogVersionResponse> values = service.catalogVersions(warehouseId).stream()
        .filter(value -> lifecycle == null || value.lifecycle() == lifecycle)
        .toList();
    return page(values, page, size);
  }

  @GetMapping("/versions/{id}/nodes")
  public List<CatalogNodeResponse> nodes(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return service.catalogNodes(id);
  }

  @PutMapping("/versions/{id}/nodes")
  public CatalogVersionResponse replaceNodes(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody ReplaceCatalogNodesRequest request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return service.replaceCatalogNodes(id, request);
  }

  @GetMapping("/versions/{id}/links")
  public List<CatalogLinkResponse> links(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return service.catalogLinks(id);
  }

  @PutMapping("/versions/{id}/links")
  public CatalogVersionResponse replaceLinks(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody ReplaceCatalogLinksRequest request) {
    access.requireEdit(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    return service.replaceCatalogLinks(id, request);
  }

  @PostMapping("/imports")
  public ResponseEntity<CatalogVersionResponse> importCatalog(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ImportCatalogRequest request) {
    access.requireManage(jwt, request.warehouseId());
    MaintenanceApplicationService.CreateResult<CatalogVersionResponse> result =
        service.importCatalog(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/versions/{id}/activate")
  public ResponseEntity<CatalogVersionResponse> activate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody VersionCommand request) {
    access.requireManage(jwt, warehouseId);
    requireWarehouse(id, warehouseId);
    MaintenanceApplicationService.CreateResult<CatalogVersionResponse> result =
        service.activateCatalog(access.subjectId(jwt), idempotencyKey, id, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  private void requireWarehouse(UUID id, UUID warehouseId) {
    service.catalogVersion(id, warehouseId);
  }

  private static <T> PageResponse<T> page(List<T> values, int page, int size) {
    int from = Math.min(Math.multiplyExact(page, size), values.size());
    int to = Math.min(from + size, values.size());
    return new PageResponse<>(values.subList(from, to), page, size, values.size());
  }
}
