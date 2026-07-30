package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.PresentationHoldService;
import jakarta.validation.Valid;
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

  @PostMapping("/cabin-searches")
  public CabinSearchResponse search(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CabinSearchRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return service.search(request);
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
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID presentationId) {
    access.requireLogisticsAssetAccess(jwt);
    return service.holds(presentationId);
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
