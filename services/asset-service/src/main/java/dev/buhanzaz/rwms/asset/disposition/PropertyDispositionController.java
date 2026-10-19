package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.*;

import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The maintenance-service may only read and execute a previously approved property disposition.
 * Human approval, reason and evidence remain maintenance-owned decision facts.
 */
@RestController
@Validated
@RequestMapping("/api/internal/asset/v1/maintenance")
public class PropertyDispositionController {
  private final PropertyDispositionService service;
  private final AssetAuthorizer access;

  public PropertyDispositionController(PropertyDispositionService service, AssetAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/property-assets/{assetKind}/{assetId}/snapshot")
  public MaintenancePropertyAssetSnapshot snapshot(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable PropertyAssetKind assetKind,
      @PathVariable UUID assetId,
      @RequestParam UUID warehouseId) {
    access.requireMaintenanceAssetAccess(jwt);
    return service.snapshot(assetKind, assetId, warehouseId);
  }

  @PostMapping("/property-dispositions/{decisionId}/prepare")
  public ResponseEntity<MaintenancePropertyDispositionFence> prepare(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID decisionId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody PrepareMaintenancePropertyDispositionRequest request) {
    PropertyDispositionApiModels.CommandResult<MaintenancePropertyDispositionFence> result =
        service.prepare(access.maintenanceSubjectId(jwt), decisionId, idempotencyKey, request);
    return commandResponse(result);
  }

  @PostMapping("/property-dispositions/{decisionId}/apply")
  public ResponseEntity<MaintenancePropertyDispositionEffect> apply(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID decisionId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ApplyMaintenancePropertyDispositionRequest request) {
    PropertyDispositionApiModels.CommandResult<MaintenancePropertyDispositionEffect> result =
        service.apply(access.maintenanceSubjectId(jwt), decisionId, idempotencyKey, request);
    return commandResponse(result);
  }

  private static <T> ResponseEntity<T> commandResponse(PropertyDispositionApiModels.CommandResult<T> result) {
    if (result.replayed()) {
      return ResponseEntity.ok().header("Idempotency-Replayed", "true").body(result.response());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
  }
}
