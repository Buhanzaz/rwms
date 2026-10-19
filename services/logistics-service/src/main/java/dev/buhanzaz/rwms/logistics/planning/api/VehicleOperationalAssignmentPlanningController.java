package dev.buhanzaz.rwms.logistics.planning.api;

import dev.buhanzaz.rwms.logistics.planning.api.VehicleOperationalAssignmentPlanningApiModels.PlanningVehicleOperationalAssignment;
import dev.buhanzaz.rwms.logistics.planning.mapper.VehicleOperationalAssignmentPlanningMapper;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import dev.buhanzaz.rwms.logistics.vehicle.service.VehicleOperationalAssignmentService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only private boundary for planner access to logistics-owned live vehicle chain facts. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/internal/logistics/v1/planning")
public class VehicleOperationalAssignmentPlanningController {
  private final LogisticsAuthorizer access;
  private final VehicleOperationalAssignmentService assignments;
  private final VehicleOperationalAssignmentPlanningMapper mapper;

  /**
   * Returns live ancestry closure for vehicles whose history ever touched the requested warehouse.
   */
  @GetMapping("/vehicle-assignments")
  public List<PlanningVehicleOperationalAssignment> vehicleAssignments(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam OffsetDateTime windowStart,
      @RequestParam OffsetDateTime windowEnd) {
    access.requirePlanningIntegration(jwt);
    return assignments.findPlanningWindow(warehouseId, windowStart, windowEnd).stream()
        .map(mapper::toResponse)
        .toList();
  }
}
