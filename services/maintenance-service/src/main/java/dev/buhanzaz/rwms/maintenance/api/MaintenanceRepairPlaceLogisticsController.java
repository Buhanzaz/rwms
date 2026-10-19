package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.RepairPlaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
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

/** HTTP controller for MaintenanceRepairPlaceLogistics; it authorizes the request and delegates the business transition. */
@RestController
@Validated
@RequestMapping("/api/internal/maintenance/v1/logistics")
public class MaintenanceRepairPlaceLogisticsController {
  private final RepairPlaceService repairPlaces;
  private final MaintenanceApplicationService maintenance;
  private final MaintenanceAuthorizer access;

  public MaintenanceRepairPlaceLogisticsController(
      RepairPlaceService repairPlaces,
      MaintenanceApplicationService maintenance,
      MaintenanceAuthorizer access) {
    this.repairPlaces = repairPlaces;
    this.maintenance = maintenance;
    this.access = access;
  }

  @GetMapping("/repair-places/{warehouseId}")
  public LogisticsRepairPlaceProjectionResponse projection(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireLogisticsService(jwt);
    return repairPlaces.logisticsProjection(warehouseId);
  }

  @GetMapping("/repairs/capital")
  public MaintenanceApiModels.PageResponse<LogisticsCapitalRepairResponse> capital(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    access.requireLogisticsService(jwt);
    List<LogisticsCapitalRepairResponse> values =
        maintenance.activeCapitalRepairs(warehouseId).stream()
            .map(MaintenanceRepairPlaceLogisticsController::logisticsCapital)
            .toList();
    return page(values, page, size);
  }

  @GetMapping("/repairs/capital/{repairId}")
  public LogisticsCapitalRepairResponse capitalRepair(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID repairId) {
    access.requireLogisticsService(jwt);
    return logisticsCapital(maintenance.activeCapitalRepair(repairId));
  }

  @PostMapping("/repair-places/{warehouseId}/allocations/{repairId}/reserve")
  public ResponseEntity<LogisticsRepairPlaceAllocationResponse> reserve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID repairId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RepairPlaceTransitionRequest request) {
    access.requireLogisticsService(jwt);
    return result(
        repairPlaces.reserve(
            warehouseId, repairId, request.expectedVersion(), idempotencyKey));
  }

  @PostMapping("/repair-places/{warehouseId}/allocations/{repairId}/occupy")
  public ResponseEntity<LogisticsRepairPlaceAllocationResponse> occupy(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID repairId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RepairPlaceTransitionRequest request) {
    access.requireLogisticsService(jwt);
    RepairPlaceService.TransitionResult transition =
        repairPlaces.occupy(
            warehouseId, repairId, request.expectedVersion(), idempotencyKey);
    // A replay reaches this branch too: that is deliberate, so an interrupted callback cannot
    // leave the cabin occupying a repair place without its ordinary repair task being activated.
    maintenance.activateQueuedRepairAfterDelivery(warehouseId, repairId);
    return result(transition);
  }

  @PostMapping("/repair-places/{warehouseId}/allocations/{repairId}/ready-to-release")
  public ResponseEntity<LogisticsRepairPlaceAllocationResponse> readyToRelease(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID repairId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RepairPlaceTransitionRequest request) {
    access.requireLogisticsService(jwt);
    return result(
        repairPlaces.readyToRelease(
            warehouseId, repairId, request.expectedVersion(), idempotencyKey));
  }

  @PostMapping("/repair-places/{warehouseId}/allocations/{repairId}/release")
  public ResponseEntity<LogisticsRepairPlaceAllocationResponse> release(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID repairId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RepairPlaceTransitionRequest request) {
    access.requireLogisticsService(jwt);
    return result(
        repairPlaces.release(
            warehouseId, repairId, request.expectedVersion(), idempotencyKey));
  }

  private static ResponseEntity<LogisticsRepairPlaceAllocationResponse> result(
      RepairPlaceService.TransitionResult result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header("Idempotency-Replayed", "true");
    }
    return response.body(result.response());
  }

  private static <T> MaintenanceApiModels.PageResponse<T> page(
      List<T> values, int page, int size) {
    int from = Math.min(Math.multiplyExact(page, size), values.size());
    int to = Math.min(from + size, values.size());
    return new MaintenanceApiModels.PageResponse<>(
        values.subList(from, to), page, size, values.size());
  }

  private static LogisticsCapitalRepairResponse logisticsCapital(
      MaintenanceApiModels.RepairResponse repair) {
    return new LogisticsCapitalRepairResponse(
        repair.id(),
        repair.rentalItemId(),
        repair.warehouseId(),
        repair.priority(),
        repair.complexity(),
        repair.version());
  }
}
