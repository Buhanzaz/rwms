package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceTaskRequirementsResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private source-owned requirements projection for maintenance tasks. */
@RestController
@RequestMapping("/api/internal/maintenance/v1/repairs")
@RequiredArgsConstructor
public class MaintenanceTaskRequirementsController {
  private final MaintenanceAuthorizer access;
  private final MaintenanceTaskRequirementsResolver requirements;

  @GetMapping("/{repairId}/task-requirements")
  public MaintenanceApiModels.RepairTaskRequirementsResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID repairId, @RequestParam UUID warehouseId) {
    access.requireTaskBoardService(jwt);
    return requirements.resolve(repairId, warehouseId);
  }
}
