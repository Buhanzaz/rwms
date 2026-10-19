package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskActionRequest;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskActionResult;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorEvidenceReservation;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorEvidenceReservationRequest;
import static dev.buhanzaz.rwms.taskboard.api.ContractorTaskExecutionApiModels.ContractorTaskExecutionSnapshot;

import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.ContractorTaskExecutionService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private logistics-service adapter for one exact contractor's task-board execution route.
 *
 * <p>The service credential is fixed to logistics-service with exactly task-board.logistics.
 * Worker and external task identities are then proven against task-board state by the application
 * service; neither path parameter is accepted as impersonation authority by itself.
 */
@RestController
@RequestMapping("/api/internal/task-board/v1/logistics/contractor-execution")
@RequiredArgsConstructor
public class ContractorTaskExecutionController {
  private final ContractorTaskExecutionService service;
  private final TaskSyncAuthorizer access;

  /** Returns only the logistics task assigned to this exact active contractor. */
  @GetMapping("/workers/{workerId}/tasks/{externalTaskId}")
  public ContractorTaskExecutionSnapshot snapshot(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID workerId,
      @PathVariable UUID externalTaskId) {
    access.requireLogisticsTaskAccess(jwt);
    return service.snapshot(workerId, externalTaskId);
  }

  /** Applies or exactly replays START or COMPLETE for one route entry. */
  @PostMapping("/workers/{workerId}/tasks/{externalTaskId}/entries/{entryId}/actions")
  public ContractorTaskActionResult apply(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID workerId,
      @PathVariable UUID externalTaskId,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ContractorTaskActionRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.apply(workerId, externalTaskId, entryId, idempotencyKey, request);
  }

  /** Reserves one replay-safe evidence identity without exposing media bearer paths. */
  @PostMapping(
      "/workers/{workerId}/tasks/{externalTaskId}/entries/{entryId}/evidence-reservations")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractorEvidenceReservation reserveEvidence(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID workerId,
      @PathVariable UUID externalTaskId,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ContractorEvidenceReservationRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.reserveEvidence(
        workerId, externalTaskId, entryId, idempotencyKey, request);
  }
}
