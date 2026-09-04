package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplacementRequest;
import dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.PlanningReplacementResponse;
import dev.buhanzaz.rwms.taskboard.security.TaskSyncAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.PlanningReplacementService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private exact-identity owner command for atomic logistics plan replacement. */
@RestController
@RequestMapping("/api/internal/task-board/v1/logistics/planning-assignments")
@RequiredArgsConstructor
public class InternalPlanningReplacementController {
  private final PlanningReplacementService service;
  private final TaskSyncAuthorizer access;

  /** Replaces one complete pre-start plan revision or returns its exact receipt replay. */
  @PutMapping("/{sourcePlanId}")
  public PlanningReplacementResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID sourcePlanId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody PlanningReplacementRequest request) {
    access.requireLogisticsTaskAccess(jwt);
    return service.replace(sourcePlanId, idempotencyKey, request);
  }
}
