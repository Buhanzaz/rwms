package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.InventoryAssetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Least-privilege inventory-service boundary; generic internal mutation is absent. */
@RestController
@Validated
@RequestMapping("/api/internal/asset/v1/inventory")
@RequiredArgsConstructor
public class InventoryAssetController {
  private final InventoryAssetService service;
  private final AssetAuthorizer access;

  @GetMapping("/assets/{assetId}")
  public InventoryAssetCurrentSnapshot currentAssetSnapshot(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID assetId) {
    access.requireInventoryAssetAccess(jwt);
    return service.currentAssetSnapshot(assetId);
  }

  @PostMapping("/captures")
  public ResponseEntity<InventoryCaptureResponse> createCapture(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody InventoryCaptureRequest request) {
    access.requireInventoryAssetAccess(jwt);
    return ResponseEntity.status(HttpStatus.CREATED).body(service.createCapture(request));
  }

  @GetMapping("/captures/{captureId}/members")
  public InventoryCapturePage capturePage(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID captureId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "200") @Min(1) @Max(500) int size) {
    access.requireInventoryAssetAccess(jwt);
    return service.capturePage(captureId, cursor, size);
  }

  @DeleteMapping("/captures/{captureId}")
  public ResponseEntity<Void> releaseCapture(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID captureId) {
    access.requireInventoryAssetAccess(jwt);
    service.releaseCapture(captureId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/number-resolutions")
  public InventoryNumberResolutionResponse resolveNumber(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody InventoryNumberResolutionRequest request) {
    access.requireInventoryAssetAccess(jwt);
    return service.resolveNumber(request);
  }

  @PostMapping("/validations")
  public InventoryValidationResponse validateAssets(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody InventoryValidationRequest request) {
    access.requireInventoryAssetAccess(jwt);
    return service.validateAssets(request);
  }

  @PostMapping("/furniture-snapshots")
  public InventoryFurnitureSnapshot furnitureSnapshot(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody InventoryFurnitureSnapshotRequest request) {
    access.requireInventoryAssetAccess(jwt);
    return service.furnitureSnapshot(request);
  }

  @PutMapping("/furniture-reconciliations/{inventoryId}")
  public ResponseEntity<Void> reconcileFurniture(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody InventoryFurnitureReconciliationRequest request) {
    access.requireInventoryAssetAccess(jwt);
    InventoryAssetService.FurnitureReconciliationResult result =
        service.reconcileFurniture(inventoryId, idempotencyKey, request);
    ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.build();
  }

  /** Applies one final-plan found-cabin outcome using inventory-service-only credentials. */
  @PutMapping("/outcomes/{inventoryId}/findings/{findingId}")
  public ResponseEntity<InventoryOutcomeResponse> applyOutcome(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @PathVariable UUID findingId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody InventoryOutcomeRequest request) {
    InventoryAssetService.OutcomeResult result =
        service.applyOutcome(
            access.inventorySubjectId(jwt),
            inventoryId,
            findingId,
            idempotencyKey,
            request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/source-assets")
  public ResponseEntity<InventorySourceAssetResponse> createSourceAsset(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody InventorySourceAssetRequest request) {
    access.requireInventoryAssetAccess(jwt);
    InventoryAssetService.CreateResult<InventorySourceAssetResponse> result =
        service.createSourceAsset(request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(
        result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
