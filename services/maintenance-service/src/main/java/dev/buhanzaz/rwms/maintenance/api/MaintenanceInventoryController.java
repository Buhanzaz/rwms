package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.InventoryMaintenanceService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/internal/maintenance/v1/inventory")
@RequiredArgsConstructor
public class MaintenanceInventoryController {
  private final InventoryMaintenanceService inventory;
  private final MaintenanceApplicationService maintenance;
  private final MaintenanceAuthorizer access;

  @PostMapping("/plans")
  public ResponseEntity<FrozenInventoryPlanResponse> freezePlan(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody FreezeInventoryPlanRequest request) {
    access.requireInventoryService(jwt);
    InventoryMaintenanceService.FreezeResult result = inventory.freeze(request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PostMapping("/repair-snapshots")
  public InventoryRepairSnapshotsResponse repairSnapshots(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody InventoryRepairSnapshotRequest request) {
    access.requireInventoryService(jwt);
    return inventory.repairSnapshots(request);
  }

  @PutMapping("/sources/{inventoryId}/findings/{findingId}")
  public ResponseEntity<InventoryRepairUpsertResponse> upsertRepair(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @PathVariable UUID findingId,
      @Valid @RequestBody UpsertInventoryRepairRequest request) {
    access.requireInventoryService(jwt);
    InventoryMaintenanceService.UpsertResult result = inventory.upsert(
        inventoryId, findingId, request);
    RepairResponse repair = maintenance.repair(result.repairId(), request.warehouseId());
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(new InventoryRepairUpsertResponse(
        repair, result.source(), result.delivery()));
  }
}
