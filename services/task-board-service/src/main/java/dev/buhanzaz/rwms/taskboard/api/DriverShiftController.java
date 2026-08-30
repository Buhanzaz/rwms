package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.DriverShiftService;
import dev.buhanzaz.rwms.taskboard.service.MobileTaskSurface;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
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
import org.springframework.web.bind.annotation.RestController;

/** Public Driver Up state-machine boundary driven exclusively by validated JWT identities. */
@RestController
@Validated
@RequestMapping("/api/driver/v1")
public class DriverShiftController {
  private final DriverShiftService service;
  private final WarehouseAccessAuthorizer access;

  public DriverShiftController(DriverShiftService service, WarehouseAccessAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /**
   * Returns the complete startup/navigation aggregate for the current warehouse-local work date.
   */
  @GetMapping("/shift/today")
  public TodayShiftResponse today(@AuthenticationPrincipal Jwt jwt) {
    Principal principal = principal(jwt, false);
    return service.today(principal.driverId(), principal.warehouseId());
  }

  /** Acknowledges the once-only daily briefing. */
  @PostMapping("/shifts/{shiftId}/briefing/seen")
  public TodayShiftResponse briefing(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ShiftTransitionRequest request) {
    Principal p = principal(jwt, true);
    return service.markBriefing(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  /** Stores the current test self-confirmation medical audit fact. */
  @PostMapping("/shifts/{shiftId}/medical-check")
  public TodayShiftResponse medical(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ConfirmMedicalCheckRequest request) {
    Principal p = principal(jwt, true);
    return service.confirmMedical(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  /** Writes one independently fenced inspection item result. */
  @PutMapping("/shifts/{shiftId}/vehicle-inspection/items/{itemId}")
  public TodayShiftResponse inspectionItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @PathVariable UUID itemId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody UpdateInspectionItemRequest request) {
    Principal p = principal(jwt, true);
    return service.updateInspectionItem(
        p.driverId(), p.warehouseId(), shiftId, itemId, key, request);
  }

  /** Completes the inspection after all mandatory result and defect gates. */
  @PostMapping("/shifts/{shiftId}/vehicle-inspection/complete")
  public TodayShiftResponse completeInspection(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ShiftTransitionRequest request) {
    Principal p = principal(jwt, true);
    return service.completeInspection(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  /** Starts the shift and unlocks the existing Driver Up task screen. */
  @PostMapping("/shifts/{shiftId}/start")
  public TodayShiftResponse start(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ShiftTransitionRequest request) {
    Principal p = principal(jwt, true);
    return service.start(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  /** Opens the explicit closing flow only after the backend last-task gate. */
  @PostMapping("/shifts/{shiftId}/closing/start")
  public TodayShiftResponse startClosing(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ShiftTransitionRequest request) {
    Principal p = principal(jwt, true);
    return service.startClosing(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  /** Records the warehouse return evidence. */
  @PostMapping("/shifts/{shiftId}/return-to-warehouse")
  public TodayShiftResponse warehouseReturn(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ReturnToWarehouseRequest request) {
    Principal p = principal(jwt, true);
    return service.confirmReturn(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  /** Stores odometer, fuel and end-vehicle condition under closing guards. */
  @PostMapping("/shifts/{shiftId}/closing-report")
  public TodayShiftResponse closingReport(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody SubmitClosingReportRequest request) {
    Principal p = principal(jwt, true);
    return service.submitClosingReport(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  /** Reserves a durable Driver Shift photo before upload bytes are sent. */
  @PostMapping("/shifts/{shiftId}/photos/reservations")
  public TodayShiftResponse photo(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ReserveShiftPhotoRequest request) {
    Principal p = principal(jwt, true);
    return service.reservePhoto(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  /** Irreversibly closes a fully reported shift using server time. */
  @PostMapping("/shifts/{shiftId}/close")
  public TodayShiftResponse close(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID shiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ShiftTransitionRequest request) {
    Principal p = principal(jwt, true);
    return service.close(p.driverId(), p.warehouseId(), shiftId, key, request);
  }

  private Principal principal(Jwt jwt, boolean write) {
    access.requireMobileTaskScope(jwt, MobileTaskSurface.DRIVER.scope());
    UUID driver = uuid(jwt, "worker_id"), warehouse = uuid(jwt, "warehouse_id");
    access.requireWarehouse(jwt, warehouse, write ? AccessLevel.EDIT : AccessLevel.VIEW, true);
    return new Principal(driver, warehouse);
  }

  private UUID uuid(Jwt jwt, String name) {
    try {
      return UUID.fromString(jwt.getClaimAsString(name));
    } catch (RuntimeException exception) {
      throw new AccessDeniedException("Valid " + name + " claim is required");
    }
  }

  /** Authenticated driver and warehouse identities derived exclusively from JWT claims. */
  private record Principal(UUID driverId, UUID warehouseId) {}
}
