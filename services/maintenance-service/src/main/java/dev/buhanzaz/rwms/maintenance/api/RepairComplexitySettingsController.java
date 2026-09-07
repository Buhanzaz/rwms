package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.RepairComplexitySettingsService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP controller for RepairComplexitySettings; it authorizes the request and delegates the business transition. */
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

  @GetMapping
  public RepairComplexitySettingsResponse get(
      @AuthenticationPrincipal Jwt jwt) {
    access.requireGlobalRead(jwt);
    return service.get();
  }

  @PutMapping
  public RepairComplexitySettingsResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ReplaceRepairComplexitySettingsRequest request) {
    access.requireGlobalManage(jwt);
    return service.replace(request);
  }
}
