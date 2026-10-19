package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.EstimateCreationWindowSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public management boundary for the global estimate creation window. */
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

  @GetMapping
  public EstimateCreationWindowSettingsResponse get(
      @AuthenticationPrincipal Jwt jwt) {
    access.requireGlobalRead(jwt);
    return service.get();
  }

  @PutMapping
  public EstimateCreationWindowSettingsResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ReplaceEstimateCreationWindowSettingsRequest request) {
    access.requireGlobalManage(jwt);
    return service.replace(request);
  }
}
