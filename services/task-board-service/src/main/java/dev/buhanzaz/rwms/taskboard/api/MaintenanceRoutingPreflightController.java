package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingPreflightRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingPreflightResponse;

import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.MaintenanceRoutingPreflightService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/task-board/v1/maintenance/routing-preflight")
@RequiredArgsConstructor
public class MaintenanceRoutingPreflightController {
  private final MaintenanceRoutingPreflightService service;
  private final TaskSyncAuthorizer access;

  @PostMapping
  public MaintenanceRoutingPreflightResponse preflight(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody MaintenanceRoutingPreflightRequest request) {
    access.requireTaskSync(jwt);
    return service.preflight(request);
  }
}
