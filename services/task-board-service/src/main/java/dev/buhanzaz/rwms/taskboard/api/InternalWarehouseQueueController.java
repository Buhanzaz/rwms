package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WarehouseQueueCapabilities;

import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/task-board/v1/warehouses/{warehouseId}")
@RequiredArgsConstructor
public class InternalWarehouseQueueController {
  private final RegistryService service;
  private final TaskSyncAuthorizer access;

  @GetMapping("/queue-capabilities")
  public WarehouseQueueCapabilities capabilities(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireTaskSync(jwt);
    return service.queueCapabilities(warehouseId);
  }
}
