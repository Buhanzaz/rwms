package dev.buhanzaz.rwms.logistics.maintenance.api;

import dev.buhanzaz.rwms.logistics.maintenance.service.MaintenanceReturnArrivalService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exact service-to-service boundary for maintenance reads of physical return arrivals. */
@RestController
@Validated
@RequestMapping("/api/internal/logistics/v1/maintenance/return-arrivals")
public class MaintenanceReturnArrivalController {
  private final MaintenanceReturnArrivalService service;
  private final LogisticsAuthorizer access;

  public MaintenanceReturnArrivalController(
      MaintenanceReturnArrivalService service, LogisticsAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /** Returns no party data, only the latest warehouse-scoped physical arrival evidence. */
  @GetMapping("/{rentalItemId}")
  public MaintenanceReturnArrivalResponse latest(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID rentalItemId,
      @RequestParam UUID warehouseId) {
    access.requireMaintenanceReturnArrivalRead(jwt);
    return service.latest(warehouseId, rentalItemId);
  }
}
