package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelLogisticsPreparationTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.LogisticsTaskSnapshot;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterLogisticsPreparationTaskRequest;

import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
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
 * Stage 8 task registration is intentionally smaller than maintenance task
 * sync: logistics cannot set a queue, worker, route or free-form task text.
 */
@RestController
@RequestMapping("/api/internal/task-board/v1/logistics/preparation-tasks")
@RequiredArgsConstructor
public class LogisticsTaskController {
  private final TaskBoardService service;
  private final TaskSyncAuthorizer access;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LogisticsTaskSnapshot register(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RegisterLogisticsPreparationTaskRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.registerLogisticsPreparationTask(request);
  }

  @GetMapping("/{externalTaskId}")
  public LogisticsTaskSnapshot get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID externalTaskId) {
    access.requireLogisticsTaskAccess(jwt);
    return service.logisticsPreparationTask(externalTaskId);
  }

  @PostMapping("/{externalTaskId}/cancel")
  public LogisticsTaskSnapshot cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody CancelLogisticsPreparationTaskRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.cancelLogisticsPreparationTask(externalTaskId, request);
  }
}
