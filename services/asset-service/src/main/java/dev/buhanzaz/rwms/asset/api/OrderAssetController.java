package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.OrderAssetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/internal/asset/v1/logistics/orders")
@RequiredArgsConstructor
public class OrderAssetController {
  private final OrderAssetService service;
  private final AssetAuthorizer access;

  @GetMapping("/{orderId}/unit-candidates")
  public OrderUnitCandidatePage candidates(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(defaultValue = "") @Size(max = 128) String search) {
    access.requireLogisticsAssetAccess(jwt);
    return service.candidates(orderId, warehouseId, page, size, search);
  }

  @GetMapping("/{orderId}/units")
  public List<OrderUnitReservationView> units(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
    access.requireLogisticsAssetAccess(jwt);
    return service.units(orderId);
  }

  @PostMapping("/{orderId}/units")
  public ResponseEntity<OrderUnitReservationView> reserve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReserveOrderUnitRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    var result = service.reserve(idempotencyKey, orderId, request);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/{orderId}/units/{rentalItemId}/release")
  public ResponseEntity<OrderUnitReservationView> release(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @PathVariable UUID rentalItemId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody OrderActorRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    var result = service.release(idempotencyKey, orderId, rentalItemId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/{orderId}/units/release-all")
  public ResponseEntity<List<OrderUnitReservationView>> releaseAll(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody OrderActorRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    var result = service.releaseAll(idempotencyKey, orderId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PutMapping("/{orderId}/units/{rentalItemId}/equipment/{equipmentId}")
  public ResponseEntity<OrderEquipmentAdjustment> adjustEquipment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @PathVariable UUID rentalItemId,
      @PathVariable UUID equipmentId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AdjustOrderEquipmentRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    var result =
        service.adjustEquipment(idempotencyKey, orderId, rentalItemId, equipmentId, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
