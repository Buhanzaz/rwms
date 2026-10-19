package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.BoardTaskRegistrationDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelledTaskDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.PreStartUpdateTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.PreStartCancellationResult;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterExternalTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RelocateExternalTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.SelectedCompletionEvidenceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.SetTaskLaneRequest;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private source-owned task synchronization API for maintenance and logistics.
 *
 * <p>The authenticated client identity owns the external task namespace. It is therefore not
 * possible to read, update, cancel or relocate another source's task by supplying its UUID.
 */
@RestController
@RequestMapping("/api/internal/task-board/v1/tasks")
@RequiredArgsConstructor
public class InternalTaskSyncController {
  private final TaskBoardService service;
  private final TaskSyncAuthorizer access;

  /** Registers source-owned work and returns its task-board route registration. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BoardTaskRegistrationDto register(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegisterExternalTaskRequest request) {
    return service.registerExternalTask(access.requireTaskSync(jwt), request);
  }

  /** Resolves a registration only when it belongs to the authenticated source service. */
  @GetMapping("/{externalTaskId}")
  public BoardTaskRegistrationDto get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID externalTaskId) {
    return service.externalTask(access.requireTaskSync(jwt), externalTaskId);
  }

  /** Replaces source data only while the external task has not started. */
  @PutMapping("/{externalTaskId}")
  public BoardTaskRegistrationDto updateBeforeStart(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody PreStartUpdateTaskRequest request) {
    return service.updateExternalTaskBeforeStart(
        access.requireTaskSync(jwt), externalTaskId, request);
  }

  /** Cancels source-owned work under the source task-version fence. */
  @PostMapping("/{externalTaskId}/cancel")
  public CancelledTaskDto cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody CancelTaskRequest request) {
    return service.cancelExternalTask(access.requireTaskSync(jwt), externalTaskId, request);
  }

  /** Cancels only pre-start work and reports a typed no-op or started outcome otherwise. */
  @PostMapping("/{externalTaskId}/cancel-if-pre-start")
  public PreStartCancellationResult cancelIfPreStart(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody CancelTaskRequest request) {
    return service.cancelExternalTaskIfPreStart(
        access.requireTaskSync(jwt), externalTaskId, request);
  }

  /** Moves source-owned work between warehouses after lifecycle admission at both ends. */
  @PostMapping("/{externalTaskId}/relocate")
  public BoardTaskRegistrationDto relocate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody RelocateExternalTaskRequest request) {
    return service.relocateExternalTask(
        access.requireTaskSync(jwt), externalTaskId, request);
  }

  /** Changes the lane of a source-owned driver task without changing task ownership. */
  @PostMapping("/{externalTaskId}/lane")
  public BoardTaskRegistrationDto setLane(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody SetTaskLaneRequest request) {
    return service.setExternalTaskLane(
        access.requireTaskSync(jwt), externalTaskId, request);
  }

  /** Returns selected completion evidence for a source-owned external task. */
  @GetMapping("/{externalTaskId}/completion-evidence")
  public SelectedCompletionEvidenceDto completionEvidence(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID externalTaskId) {
    return service.selectedCompletionEvidence(
        access.requireTaskSync(jwt), externalTaskId);
  }
}
