package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Least-privilege Stage 6 lease/fence surface; equipment holds are intentionally absent. */
@RestController
@Validated
@RequestMapping("/api/internal/asset/v1/maintenance")
@RequiredArgsConstructor
public class MaintenanceAssetController {
  private final AssetService service;
  private final AssetAuthorizer access;

  @PostMapping("/operation-leases")
  public ResponseEntity<OperationLeaseResponse> acquireLease(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AcquireMaintenanceOperationLeaseRequest request) {
    UUID subjectId = access.maintenanceSubjectId(jwt);
    AssetService.CreateResult<OperationLeaseResponse> result =
        service.acquireMaintenanceLease(subjectId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PutMapping("/operation-leases/{id}/renew")
  public ResponseEntity<OperationLeaseResponse> renewLease(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RenewMaintenanceOperationLeaseRequest request) {
    return idempotentOk(service.renewMaintenanceLease(
        access.maintenanceSubjectId(jwt), idempotencyKey, id, request));
  }

  @PutMapping("/operation-leases/{id}/release")
  public ResponseEntity<OperationLeaseResponse> releaseLease(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReleaseMaintenanceOperationLeaseRequest request) {
    return idempotentOk(service.releaseMaintenanceLease(
        access.maintenanceSubjectId(jwt), idempotencyKey, id, request));
  }

  @PutMapping("/rental-items/{id}/fenced-status")
  public ResponseEntity<RentalItemResponse> fencedStatus(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody MaintenanceFencedStatusRequest request) {
    return idempotentOk(service.maintenanceFencedStatus(
        access.maintenanceSubjectId(jwt), idempotencyKey, id, request));
  }

  private static <T> ResponseEntity<T> idempotentOk(AssetService.CreateResult<T> result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
