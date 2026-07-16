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

/**
 * Private, versioned lease/hold/status surface. It is deliberately not routed
 * by the public gateway and requires a service credential with only
 * {@code asset.internal}.
 */
@RestController
@Validated
@RequestMapping("/api/internal/asset/v1")
@RequiredArgsConstructor
public class InternalAssetController {
  private final AssetService service;
  private final AssetAuthorizer access;

  @PostMapping("/equipment-holds")
  public ResponseEntity<EquipmentHoldResponse> acquireHold(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AcquireEquipmentHoldRequest request) {
    access.requireInternalAssetAccess(jwt);
    AssetService.CreateResult<EquipmentHoldResponse> result = service.acquireHold(access.internalSubjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PutMapping("/equipment-holds/{id}/renew")
  public ResponseEntity<EquipmentHoldResponse> renewHold(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RenewEquipmentHoldRequest request) {
    access.requireInternalAssetAccess(jwt);
    AssetService.CreateResult<EquipmentHoldResponse> result =
        service.renewHold(access.internalSubjectId(jwt), idempotencyKey, id, request);
    return idempotentOk(result);
  }

  @PutMapping("/equipment-holds/{id}/release")
  public ResponseEntity<EquipmentHoldResponse> releaseHold(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReleaseEquipmentHoldRequest request) {
    access.requireInternalAssetAccess(jwt);
    AssetService.CreateResult<EquipmentHoldResponse> result =
        service.releaseHold(access.internalSubjectId(jwt), idempotencyKey, id, request);
    return idempotentOk(result);
  }

  @PutMapping("/equipment-holds/{id}/commit")
  public ResponseEntity<EquipmentHoldResponse> commitHold(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CommitEquipmentHoldRequest request) {
    access.requireInternalAssetAccess(jwt);
    AssetService.CreateResult<EquipmentHoldResponse> result =
        service.commitHold(access.internalSubjectId(jwt), idempotencyKey, id, request);
    return idempotentOk(result);
  }

  @PostMapping("/operation-leases")
  public ResponseEntity<OperationLeaseResponse> acquireLease(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AcquireOperationLeaseRequest request) {
    access.requireInternalAssetAccess(jwt);
    AssetService.CreateResult<OperationLeaseResponse> result = service.acquireLease(access.internalSubjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PutMapping("/operation-leases/{id}/renew")
  public ResponseEntity<OperationLeaseResponse> renewLease(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RenewOperationLeaseRequest request) {
    access.requireInternalAssetAccess(jwt);
    AssetService.CreateResult<OperationLeaseResponse> result =
        service.renewLease(access.internalSubjectId(jwt), idempotencyKey, id, request);
    return idempotentOk(result);
  }

  @PutMapping("/operation-leases/{id}/release")
  public ResponseEntity<OperationLeaseResponse> releaseLease(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReleaseOperationLeaseRequest request) {
    access.requireInternalAssetAccess(jwt);
    AssetService.CreateResult<OperationLeaseResponse> result =
        service.releaseLease(access.internalSubjectId(jwt), idempotencyKey, id, request);
    return idempotentOk(result);
  }

  @PutMapping("/rental-items/{id}/fenced-status")
  public RentalItemResponse fencedStatus(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody FencedStatusRequest request) {
    access.requireInternalAssetAccess(jwt);
    return service.fencedStatus(id, request);
  }

  private static <T> ResponseEntity<T> idempotentOk(AssetService.CreateResult<T> result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
