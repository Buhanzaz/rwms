package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetService;
import jakarta.validation.Valid;
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

/**
 * Stage 8's least-privilege private asset surface. It is not a generic
 * service-to-service API and accepts only logistics-owned references.
 */
@RestController
@Validated
@RequestMapping("/api/internal/asset/v1/logistics")
@RequiredArgsConstructor
public class LogisticsAssetController {
  private final AssetService service;
  private final AssetAuthorizer access;

  @GetMapping("/rental-items/{id}/snapshot")
  public LogisticsRentalItemSnapshot snapshot(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireLogisticsAssetAccess(jwt);
    return service.logisticsSnapshot(id);
  }

  @GetMapping("/equipment-availability")
  public List<EquipmentWarehouseResponse> equipmentAvailability(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId) {
    access.requireLogisticsAssetAccess(jwt);
    return service.logisticsEquipmentAvailability(warehouseId);
  }

  @PostMapping("/operation-leases")
  public ResponseEntity<LogisticsOperationLeaseResponse> acquireLease(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AcquireLogisticsOperationLeaseRequest request) {
    AssetService.CreateResult<LogisticsOperationLeaseResponse> result =
        service.acquireLogisticsLease(access.logisticsSubjectId(jwt), idempotencyKey, request);
    return created(result);
  }

  @PostMapping("/return-equipment-receipts")
  public ResponseEntity<LogisticsReturnEquipmentReceiptResponse> receiveReturnEquipment(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody LogisticsReturnEquipmentReceiptRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return created(
        service.receiveLogisticsReturnEquipment(
            access.logisticsSubjectId(jwt), idempotencyKey, request));
  }

  @PutMapping("/operation-leases/{id}/renew")
  public ResponseEntity<LogisticsOperationLeaseResponse> renewLease(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody LogisticsLeaseCommandRequest request) {
    return idempotentOk(service.renewLogisticsLease(
        access.logisticsSubjectId(jwt), idempotencyKey, id, request));
  }

  @PutMapping("/operation-leases/{id}/release")
  public ResponseEntity<LogisticsOperationLeaseResponse> releaseLease(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody LogisticsLeaseCommandRequest request) {
    return idempotentOk(service.releaseLogisticsLease(
        access.logisticsSubjectId(jwt), idempotencyKey, id, request));
  }

  @PutMapping("/rental-items/{id}/effects")
  public ResponseEntity<LogisticsRentalItemSnapshot> applyCanonicalEffect(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody LogisticsFencedEffectRequest request) {
    return idempotentOk(service.applyLogisticsEffect(
        access.logisticsSubjectId(jwt), idempotencyKey, id, request));
  }

  @PostMapping("/equipment-holds")
  public ResponseEntity<LogisticsEquipmentHoldResponse> acquireHold(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AcquireLogisticsEquipmentHoldRequest request) {
    return created(service.acquireLogisticsHold(
        access.logisticsSubjectId(jwt), idempotencyKey, request));
  }

  @PutMapping("/equipment-holds/{id}/renew")
  public ResponseEntity<LogisticsEquipmentHoldResponse> renewHold(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody LogisticsEquipmentHoldCommandRequest request) {
    return idempotentOk(service.renewLogisticsHold(
        access.logisticsSubjectId(jwt), idempotencyKey, id, request));
  }

  @PutMapping("/equipment-holds/{id}/commit")
  public ResponseEntity<LogisticsEquipmentHoldResponse> commitHold(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody LogisticsEquipmentHoldCommandRequest request) {
    return idempotentOk(service.commitLogisticsHold(
        access.logisticsSubjectId(jwt), idempotencyKey, id, request));
  }

  @PutMapping("/equipment-holds/{id}/release")
  public ResponseEntity<LogisticsEquipmentHoldResponse> releaseHold(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody LogisticsEquipmentHoldCommandRequest request) {
    return idempotentOk(service.releaseLogisticsHold(
        access.logisticsSubjectId(jwt), idempotencyKey, id, request));
  }

  /**
   * Reserves one exact stock or cabin balance for a planned worker movement.
   * This is deliberately separate from the older shipment-only hold surface.
   */
  @PostMapping("/equipment-movement-reservations")
  public ResponseEntity<LogisticsEquipmentMovementReservationResponse> acquireMovementReservation(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AcquireLogisticsEquipmentMovementReservationRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return created(service.acquireLogisticsEquipmentMovementReservation(
        access.logisticsSubjectId(jwt), idempotencyKey, request));
  }

  @PutMapping("/equipment-movement-reservations/{id}/release")
  public ResponseEntity<LogisticsEquipmentMovementReservationResponse> releaseMovementReservation(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody LogisticsEquipmentMovementReservationCommandRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return idempotentOk(service.releaseLogisticsEquipmentMovementReservation(
        access.logisticsSubjectId(jwt), idempotencyKey, id, request));
  }

  /**
   * Applies the worker-completed task as one asset-owned transaction. A
   * failed line rolls back every balance, ledger and reservation transition.
   */
  @PostMapping("/equipment-movement-reservations/execute")
  public ResponseEntity<LogisticsEquipmentMovementExecutionResponse> executeMovementReservations(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ExecuteLogisticsEquipmentMovementReservationsRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    return created(service.executeLogisticsEquipmentMovementReservations(
        access.logisticsSubjectId(jwt), idempotencyKey, request));
  }

  private static <T> ResponseEntity<T> created(AssetService.CreateResult<T> result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  private static <T> ResponseEntity<T> idempotentOk(AssetService.CreateResult<T> result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
