package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.EstimateCreationWindowSettingsService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public management boundary for the warehouse estimate creation window. */
@RestController
@RequestMapping("/api/maintenance/v1/settings/estimate-creation-window")
public class EstimateCreationWindowSettingsController {
  private final EstimateCreationWindowSettingsService service;
  private final MaintenanceAuthorizer access;

  public EstimateCreationWindowSettingsController(
      EstimateCreationWindowSettingsService service, MaintenanceAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/{warehouseId}")
  public EstimateCreationWindowSettingsResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireManage(jwt, warehouseId);
    return service.get(warehouseId);
  }

  @PutMapping("/{warehouseId}")
  public EstimateCreationWindowSettingsResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody ReplaceEstimateCreationWindowSettingsRequest request) {
    access.requireManage(jwt, warehouseId);
    return service.replace(warehouseId, request);
  }
}
