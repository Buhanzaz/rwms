package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelLogisticsEquipmentMovementTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.LogisticsTaskSnapshot;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterLogisticsEquipmentMovementTaskRequest;

import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Typed private logistics surface for furniture moves reserved until their task deadline.
 *
 * <p>Logistics supplies immutable movement facts. Task-board derives its title, route and queue,
 * so neither client can accidentally take ownership of the other service's business data.
 */
@RestController
@RequestMapping("/api/internal/task-board/v1/logistics/equipment-movement-tasks")
@RequiredArgsConstructor
public class LogisticsEquipmentMovementTaskController {
  private final TaskBoardService service;
  private final TaskSyncAuthorizer access;

  /** Registers a typed logistics equipment-movement task. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LogisticsTaskSnapshot register(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RegisterLogisticsEquipmentMovementTaskRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.registerLogisticsEquipmentMovementTask(request);
  }

  /** Returns only the matching logistics-owned equipment-movement task snapshot. */
  @GetMapping("/{externalTaskId}")
  public LogisticsTaskSnapshot get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID externalTaskId) {
    access.requireLogisticsTaskAccess(jwt);
    return service.logisticsEquipmentMovementTask(externalTaskId);
  }

  /** Cancels the logistics task under the observed task version. */
  @PostMapping("/{externalTaskId}/cancel")
  public LogisticsTaskSnapshot cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody CancelLogisticsEquipmentMovementTaskRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.cancelLogisticsEquipmentMovementTask(externalTaskId, request);
  }
}
