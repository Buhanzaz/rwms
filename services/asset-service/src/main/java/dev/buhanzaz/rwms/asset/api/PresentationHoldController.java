package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.PresentationHoldService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

/**
 * HTTP adapter for presentation hold.
 * It exposes the contract boundary without owning a persistence model or domain transition.
 */
@RestController
@Validated
@RequestMapping("/api/internal/asset/v1/logistics")
@RequiredArgsConstructor
public class PresentationHoldController {
  private final PresentationHoldService service;
  private final AssetAuthorizer access;

  @GetMapping("/cabin-facets")
  public CabinFacetResponse facets(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(required = false) UUID holdScopeId) {
    access.requireLogisticsAssetAccess(jwt);
    return service.facets(warehouseId, holdScopeId);
  }

  /** Returns warehouse cabin facts without checking availability or creating a hold. */
  @GetMapping("/cabin-catalog")
  public CabinCatalogPage catalog(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam @Size(max = 255) String query,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    access.requireLogisticsAssetAccess(jwt);
    return service.catalog(warehouseId, query, page, size);
  }

  /** Returns only cabins currently bookable by the specified customer inquiry scope. */
  @GetMapping("/customer-cabin-catalog")
  public CabinCatalogPage customerCatalog(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam UUID holdScopeId,
      @RequestParam(required = false) @Size(max = 255) String query,
      @RequestParam(required = false) @Size(max = 255) String cabinType,
      @RequestParam(required = false) @Size(max = 255) String finish,
      @RequestParam(required = false) @Size(max = 255) String dimensions,
      @RequestParam(required = false) @Size(max = 255) String category,
      @RequestParam(required = false) Boolean linoleum,
      @RequestParam(required = false) @Size(max = 20)
          List<@Size(min = 1, max = 255) String> characteristics,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    access.requireLogisticsAssetAccess(jwt);
    return service.customerCatalog(
        warehouseId,
        holdScopeId,
        query,
        cabinType,
        finish,
        dimensions,
        category,
        linoleum,
        characteristics,
        page,
        size);
  }

  /**
   * Binds a logistics-only caller key to one exact cabin-search request and exposes frozen replay
   * only through the response header defined by the internal contract.
   */
  @PostMapping("/cabin-searches")
  public ResponseEntity<CabinSearchResponse> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CabinSearchRequest request) {
    UUID subjectId = access.logisticsSubjectId(jwt);
    var result = service.search(subjectId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/cabin-availability")
  public CabinAvailabilityResponse availability(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CabinAvailabilityRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return service.availability(request);
  }

  @PostMapping("/cabin-snapshots")
  public CabinSnapshotsResponse snapshots(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CabinAvailabilityRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return service.snapshots(request);
  }

  @GetMapping("/presentations/{presentationId}/holds")
  public ReplacePresentationHoldsResponse holds(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID presentationId,
      @RequestParam(required = false) UUID actorSubjectId,
      @RequestParam(required = false) String actorRole) {
    access.requireLogisticsAssetAccess(jwt);
    return service.holds(presentationId, actorSubjectId, actorRole);
  }

  @PutMapping("/presentations/{presentationId}/holds")
  public ResponseEntity<ReplacePresentationHoldsResponse> replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID presentationId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReplacePresentationHoldsRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    var result = service.replace(idempotencyKey, presentationId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/presentations/{presentationId}/holds/release")
  public ResponseEntity<ReplacePresentationHoldsResponse> release(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID presentationId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ActorInput request) {
    access.requireLogisticsAssetAccess(jwt);
    var result = service.release(idempotencyKey, presentationId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/presentations/{presentationId}/holds/convert")
  public ResponseEntity<ConvertPresentationHoldsResponse> convert(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID presentationId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ConvertPresentationHoldsRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    var result = service.convert(idempotencyKey, presentationId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
