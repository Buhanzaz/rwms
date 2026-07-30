package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.RepairComplexityThresholdsResponse;

import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.KpiSettingsService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/task-board/v1/warehouses/{warehouseId}")
public class InternalKpiSettingsController {
  private final KpiSettingsService service;
  private final TaskSyncAuthorizer access;

  @GetMapping("/repair-complexity")
  public RepairComplexityThresholdsResponse repairComplexity(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireTaskSync(jwt);
    return service.repairComplexity(warehouseId);
  }
}
