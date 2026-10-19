package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.LogisticsDriverDirectoryService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private logistics-service directory for assignable warehouse drivers.
 *
 * <p>The controller authenticates the exact service credential and delegates all qualification
 * semantics to the cohesive task-board directory service.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/task-board/v1/logistics/warehouses")
public class InternalLogisticsDriverDirectoryController {
  private final LogisticsDriverDirectoryService directory;
  private final TaskSyncAuthorizer access;

  /** Returns qualified resources operationally available for the warehouse and instant. */
  @GetMapping("/{warehouseId}/drivers")
  public List<LogisticsDriverIdentityResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestParam(required = false) OffsetDateTime at,
      @RequestParam(defaultValue = "false") boolean includeIncoming) {
    access.requireLogisticsTaskAccess(jwt);
    return directory.list(warehouseId, at, includeIncoming);
  }
}
