package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplanCommitResponse;
import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplanPrepareRequest;
import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplanPrepareResponse;
import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplanReleaseResponse;
import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.PlanningReplanHoldService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private logistics-only PREPARE/COMMIT/RELEASE boundary for published-plan rescheduling. */
@RestController
@RequestMapping("/api/internal/task-board/v1/logistics/planning-replan-holds")
@RequiredArgsConstructor
public class InternalPlanningReplanHoldController {
  private final PlanningReplanHoldService service;
  private final TaskSyncAuthorizer access;

  /** Creates an execution hold after every exact source-plan fence passes. */
  @PostMapping("/{sourcePlanId}")
  public PlanningReplanPrepareResponse prepare(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID sourcePlanId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody PlanningReplanPrepareRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.prepare(sourcePlanId, idempotencyKey, request);
  }

  /** Atomically tombstones the removed task and advances the remaining source-plan revision. */
  @PostMapping("/{holdId}/commit")
  public PlanningReplanCommitResponse commit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID holdId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
    access.requireLogisticsTaskAccess(jwt);
    return service.commit(holdId, idempotencyKey);
  }

  /** Releases a hold only while the logistics owner mutation is still uncommitted. */
  @PostMapping("/{holdId}/release")
  public PlanningReplanReleaseResponse release(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID holdId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
    access.requireLogisticsTaskAccess(jwt);
    return service.release(holdId, idempotencyKey);
  }
}
