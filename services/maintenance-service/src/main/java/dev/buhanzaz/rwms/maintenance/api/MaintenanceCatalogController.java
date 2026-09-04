package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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

/** HTTP controller for MaintenanceCatalog; it authorizes the request and delegates the business transition. */
@RestController
@Validated
@RequestMapping("/api/maintenance/v1/catalog")
@RequiredArgsConstructor
public class MaintenanceCatalogController {
  private final MaintenanceApplicationService service;
  private final MaintenanceAuthorizer access;

  @GetMapping("/versions")
  public ResponseEntity<PageResponse<CatalogVersionResponse>> versions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) CatalogVersionState lifecycle,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    access.requireGlobalRead(jwt);
    List<CatalogVersionResponse> values = service.catalogVersions().stream()
        .filter(value -> lifecycle == null || value.lifecycle() == lifecycle)
        .toList();
    PageResponse<CatalogVersionResponse> response = page(values, page, size);
    return ConditionalGet.response(
        "catalog-versions:" + page + ':' + size + ':' + lifecycle,
        response,
        ifNoneMatch);
  }

  @PutMapping("/versions/{id}")
  public CatalogVersionResponse replaceCatalog(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ChangeCatalogRequest request) {
    access.requireGlobalManage(jwt);
    return service.changeCatalog(id, request);
  }

  @GetMapping("/versions/{id}/nodes")
  public List<CatalogNodeResponse> nodes(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id) {
    access.requireGlobalRead(jwt);
    return service.catalogNodes(id);
  }

  @PutMapping("/versions/{id}/nodes")
  public CatalogVersionResponse replaceNodes(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ReplaceCatalogNodesRequest request) {
    access.requireGlobalManage(jwt);
    return service.replaceCatalogNodes(id, request);
  }

  @GetMapping("/versions/{id}/links")
  public List<CatalogLinkResponse> links(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id) {
    access.requireGlobalRead(jwt);
    return service.catalogLinks(id);
  }

  @PutMapping("/versions/{id}/links")
  public CatalogVersionResponse replaceLinks(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ReplaceCatalogLinksRequest request) {
    access.requireGlobalManage(jwt);
    return service.replaceCatalogLinks(id, request);
  }

  @PostMapping("/versions")
  public ResponseEntity<CatalogVersionResponse> createCatalog(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
    access.requireGlobalManage(jwt);
    MaintenanceApplicationService.CreateResult<CatalogVersionResponse> result =
        service.createGlobalCatalog(access.subjectId(jwt), idempotencyKey);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/versions/{id}/fork")
  public ResponseEntity<CatalogVersionResponse> fork(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody VersionCommand request) {
    access.requireGlobalManage(jwt);
    MaintenanceApplicationService.CreateResult<CatalogVersionResponse> result =
        service.forkCatalog(access.subjectId(jwt), idempotencyKey, id, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/versions/{id}/activate")
  public ResponseEntity<CatalogVersionResponse> activate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody VersionCommand request) {
    access.requireGlobalManage(jwt);
    MaintenanceApplicationService.CreateResult<CatalogVersionResponse> result =
        service.activateCatalog(access.subjectId(jwt), idempotencyKey, id, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  private static <T> PageResponse<T> page(List<T> values, int page, int size) {
    int from = Math.min(Math.multiplyExact(page, size), values.size());
    int to = Math.min(from + size, values.size());
    return new PageResponse<>(values.subList(from, to), page, size, values.size());
  }
}
