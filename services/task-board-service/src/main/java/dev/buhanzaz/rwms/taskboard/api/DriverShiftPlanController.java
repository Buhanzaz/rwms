package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.DriverShiftPlanAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.DriverShiftService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private exact-scope logistics projection boundary for registered Driver Shift plans. */
@RestController
@RequestMapping("/api/internal/task-board/v1/driver-shift-plans")
public class DriverShiftPlanController {
  private final DriverShiftService service;
  private final DriverShiftPlanAuthorizer access;

  public DriverShiftPlanController(DriverShiftService service, DriverShiftPlanAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  /** Applies an exact replay or a strictly newer pre-freeze plan snapshot. */
  @PutMapping("/{sourceShiftId}")
  public ResponseEntity<DriverShiftPlanResponse> put(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID sourceShiftId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody PutDriverShiftPlanRequest request) {
    access.requirePlanner(jwt);
    DriverShiftPlanResponse result = service.putPlan(sourceShiftId, key, request);
    return ResponseEntity.status(
            result.result() == PlanApplyResult.CREATED ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result);
  }
}
