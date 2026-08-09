package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.RepairCapacitySettingsService;
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

/** HTTP controller for MaintenanceSettings; it authorizes the request and delegates the business transition. */
@RestController
@RequestMapping("/api/maintenance/v1/settings/repair-capacity")
public class MaintenanceSettingsController {
  private final RepairCapacitySettingsService service;
  private final MaintenanceAuthorizer access;

  public MaintenanceSettingsController(
      RepairCapacitySettingsService service, MaintenanceAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/{warehouseId}")
  public RepairCapacitySettingsResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireManage(jwt, warehouseId);
    return service.get(warehouseId);
  }

  @PutMapping("/{warehouseId}")
  public RepairCapacitySettingsResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody ReplaceRepairCapacitySettingsRequest request) {
    access.requireManage(jwt, warehouseId);
    return service.replace(warehouseId, request);
  }
}
