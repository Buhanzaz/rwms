package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.RepairComplexityColorsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Global estimate-setting palette with no warehouse-specific copy or selector. */
@RestController
@RequestMapping("/api/maintenance/v1/settings/repair-complexity-colors")
@RequiredArgsConstructor
public class RepairComplexityColorsController {
  private final RepairComplexityColorsService service;
  private final MaintenanceAuthorizer access;

  @GetMapping
  public RepairComplexityColorsResponse get(@AuthenticationPrincipal Jwt jwt) {
    access.requireGlobalRead(jwt);
    return service.get();
  }

  @PutMapping
  public RepairComplexityColorsResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ReplaceRepairComplexityColorsRequest request) {
    access.requireGlobalManage(jwt);
    return service.replace(request);
  }
}
