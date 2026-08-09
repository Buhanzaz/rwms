package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingPreflightRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingPreflightResponse;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CatalogRoutingPreflightRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CatalogRoutingPreflightResponse;

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

/**
 * Private read-only maintenance routing preflight API.
 *
 * <p>The preflight resolves exact UUID-based definitions and warehouse bindings before
 * maintenance commits its own command. It deliberately creates, reserves and mutates nothing, so
 * a rejected prerequisite cannot partially change the task board.
 */
@RestController
@RequestMapping("/api/internal/task-board/v1/maintenance")
@RequiredArgsConstructor
public class MaintenanceRoutingPreflightController {
  private final MaintenanceRoutingPreflightService service;
  private final TaskSyncAuthorizer access;

  /** Reports whether a warehouse can resolve all requested maintenance queue definitions. */
  @PostMapping("/routing-preflight")
  public MaintenanceRoutingPreflightResponse preflight(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody MaintenanceRoutingPreflightRequest request) {
    access.requireTaskSync(jwt);
    return service.preflight(request);
  }

  /** Checks the global catalog alone before a maintenance routing command is prepared. */
  @PostMapping("/catalog-routing-preflight")
  public CatalogRoutingPreflightResponse catalogPreflight(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CatalogRoutingPreflightRequest request) {
    access.requireTaskSync(jwt);
    return service.catalogPreflight(request);
  }
}
