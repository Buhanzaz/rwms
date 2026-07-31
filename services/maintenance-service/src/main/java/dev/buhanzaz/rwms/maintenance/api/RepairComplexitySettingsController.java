package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.RepairComplexitySettingsService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/maintenance/v1/settings/repair-complexity")
public class RepairComplexitySettingsController {
  private final RepairComplexitySettingsService service;
  private final MaintenanceAuthorizer access;

  public RepairComplexitySettingsController(
      RepairComplexitySettingsService service, MaintenanceAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/{warehouseId}")
  public RepairComplexitySettingsResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireManage(jwt, warehouseId);
    return service.get(warehouseId);
  }

  @PutMapping("/{warehouseId}")
  public RepairComplexitySettingsResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody ReplaceRepairComplexitySettingsRequest request) {
    access.requireManage(jwt, warehouseId);
    return service.replace(warehouseId, request);
  }

  @PostMapping("/{warehouseId}/task-board-import")
  public RepairComplexitySettingsResponse importOnce(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody ImportRepairComplexitySettingsRequest request) {
    access.requireManage(jwt, warehouseId);
    return service.importOnce(warehouseId, request);
  }
}
