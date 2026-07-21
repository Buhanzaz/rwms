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

/** Typed private logistics surface for furniture moves reserved until their task deadline. */
@RestController
@RequestMapping("/api/internal/task-board/v1/logistics/equipment-movement-tasks")
@RequiredArgsConstructor
public class LogisticsEquipmentMovementTaskController {
  private final TaskBoardService service;
  private final TaskSyncAuthorizer access;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LogisticsTaskSnapshot register(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RegisterLogisticsEquipmentMovementTaskRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.registerLogisticsEquipmentMovementTask(request);
  }

  @GetMapping("/{externalTaskId}")
  public LogisticsTaskSnapshot get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID externalTaskId) {
    access.requireLogisticsTaskAccess(jwt);
    return service.logisticsEquipmentMovementTask(externalTaskId);
  }

  @PostMapping("/{externalTaskId}/cancel")
  public LogisticsTaskSnapshot cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody CancelLogisticsEquipmentMovementTaskRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.cancelLogisticsEquipmentMovementTask(externalTaskId, request);
  }
}
