package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.TaskRequirementService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Manager-visible recovery checklist for the exact warehouse-owned task. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}/task-board/tasks/{taskId}/requirements")
public class TaskRequirementController {
  private final TaskRequirementService requirements;
  private final WarehouseAccessAuthorizer access;

  @GetMapping
  public TaskRequirementApiModels.TaskRequirements get(@AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId, @PathVariable UUID taskId) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.VIEW, false);
    return requirements.get(warehouseId, taskId);
  }
}
