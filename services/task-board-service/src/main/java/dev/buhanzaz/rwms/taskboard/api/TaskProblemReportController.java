package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.api.ProblemReportApiModels.TaskProblemReportPage;
import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.TaskProblemReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Warehouse-manager feed and personal read markers for worker problem reports. */
@RestController
@Validated
@RequestMapping("/api/warehouses/{warehouseId}/task-problem-reports")
public class TaskProblemReportController {
  private final TaskProblemReportService reports;
  private final WarehouseAccessAuthorizer access;

  public TaskProblemReportController(
      TaskProblemReportService reports, WarehouseAccessAuthorizer access) {
    this.reports = reports;
    this.access = access;
  }

  /** Returns a bounded newest-first page and the authenticated manager's unread count. */
  @GetMapping
  public TaskProblemReportPage page(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit) {
    requireRead(jwt, warehouseId);
    return reports.page(warehouseId, managerId(jwt), cursor, limit);
  }

  /** Creates the caller-local read receipt; repeating the request leaves the same receipt intact. */
  @PutMapping("/{reportId}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markRead(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID reportId) {
    requireRead(jwt, warehouseId);
    reports.markRead(warehouseId, managerId(jwt), reportId);
  }

  private void requireRead(Jwt jwt, UUID warehouseId) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.VIEW, false);
  }

  @org.springframework.web.bind.annotation.PostMapping("/{reportId}/apply-to-all")
  public TaskRequirementApiModels.AppliedToAll applyToAll(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId,
      @PathVariable UUID reportId,
      @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String idempotencyKey,
      @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
          TaskRequirementApiModels.ApplyToAllRequest request) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.EDIT, false);
    return reports.applyToAll(warehouseId, managerId(jwt), reportId, idempotencyKey, request.operationId());
  }

  private UUID managerId(Jwt jwt) {
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException exception) {
      throw new AccessDeniedException("USER subject must be a UUID");
    }
  }
}
