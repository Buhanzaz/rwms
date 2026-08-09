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
 * HTTP adapter for asset equipment.
 * It exposes the contract boundary without owning a persistence model or domain transition.
 */
@RestController
@Validated
@RequestMapping("/api/asset/v1/equipment")
@RequiredArgsConstructor
public class AssetEquipmentController {
  private final AssetService service;
  private final AssetAuthorizer access;

  @GetMapping
  public List<EquipmentWarehouseResponse> list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return service.equipmentAtWarehouse(warehouseId);
  }

  @GetMapping("/catalog")
  public List<EquipmentResponse> catalog(@AuthenticationPrincipal Jwt jwt) {
    access.requireGlobalCatalogRead(jwt);
    return service.listEquipment();
  }

  @GetMapping("/catalog/{id}")
  public EquipmentResponse catalogItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireGlobalCatalogRead(jwt);
    return service.equipment(id);
  }

  @PostMapping("/catalog")
  public ResponseEntity<EquipmentResponse> createCatalogItem(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateEquipmentRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    AssetService.CreateResult<EquipmentResponse> result = service.createEquipment(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PutMapping("/catalog/{id}")
  public EquipmentResponse updateCatalogItem(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateEquipmentRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    return service.updateEquipment(id, request);
  }

  @GetMapping("/{equipmentId}/totals")
  public EquipmentTotalsResponse totals(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID equipmentId, @RequestParam UUID warehouseId) {
    access.requireRead(jwt, warehouseId);
    return service.equipmentTotals(equipmentId, warehouseId);
  }

  @PostMapping("/transfers")
  public ResponseEntity<MovementResponse> transfer(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody TransferEquipmentRequest request) {
    access.requireEquipmentMovement(jwt, request.sourceWarehouseId());
    access.requireEquipmentMovement(jwt, request.targetWarehouseId());
    AssetService.CreateResult<MovementResponse> result = service.transfer(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
