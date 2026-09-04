package dev.buhanzaz.rwms.logistics.driver.api;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.CreateDriverTaskRequest;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.DriverTaskResponse;
import dev.buhanzaz.rwms.logistics.driver.service.DriverQueueScheduler;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskProcessor;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskService;
import dev.buhanzaz.rwms.logistics.driver.service.FutureDriverTaskClaimService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary for authenticated driver-task transitions, including their version and idempotency
 * fencing.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/v1/driver-tasks")
public class DriverTaskController {
  private final DriverTaskService service;
  private final DriverTaskProcessor processor;
  private final DriverQueueScheduler scheduler;
  private final LogisticsAuthorizer access;
  private final LogisticsWarehouseLifecycle warehouseLifecycle;
  private final FutureDriverTaskClaimService futureClaims;

  @PostMapping
  public ResponseEntity<DriverTaskResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateDriverTaskRequest request) {
    access.requireEdit(jwt, request.warehouseId());
    UUID subjectId = access.subjectId(jwt);
    var admission =
        warehouseLifecycle.prepareDriverTask(
            subjectId, idempotencyKey, DriverTaskService.admissionRequirements(request));
    DriverTaskService.CreateResult result =
        service.create(subjectId, idempotencyKey, request, admission);
    processor.processUntilIdle(result.response().id());
    if (result.activateNow()) scheduler.promoteRequested(result.response().id());
    DriverTaskResponse response = service.get(result.response().id());
    return response(
        response, result.replayed() ? HttpStatus.OK : HttpStatus.CREATED, result.replayed());
  }

  @GetMapping
  public List<DriverTaskResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "false") boolean expiredOnly) {
    access.requireRead(jwt, warehouseId);
    return expiredOnly ? service.expiredTrips(warehouseId) : service.list(warehouseId);
  }

  @GetMapping("/{taskId}")
  public DriverTaskResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
    var task = service.required(taskId);
    try {
      access.requireRead(jwt, task.getWarehouseId());
    } catch (AccessDeniedException exception) {
      boolean assignedDriver =
          access.isExactAssignedDriver(
              jwt, task.getDriverAudienceMode().name(), task.getPlannedDriverWorkerId());
      boolean sharedFuturePreview =
          access.isWarehouseDriver(jwt, task.getWarehouseId()) && futureClaims.isPreviewable(task);
      if (!assignedDriver && !sharedFuturePreview) {
        throw new LogisticsNotFoundException();
      }
    }
    return service.get(taskId);
  }

  /** Reserves a shared future task for the authenticated DriverApp worker without starting it. */
  @PostMapping("/{taskId}/claim")
  public ResponseEntity<DriverTaskResponse> claim(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
    var task = service.required(taskId);
    UUID workerId = access.requireWarehouseDriver(jwt, task.getWarehouseId());
    DriverTaskResponse response = futureClaims.claim(taskId, workerId);
    return ResponseEntity.ok()
        .eTag('"' + Long.toString(response.version()) + '"')
        .body(response);
  }

  @PostMapping("/{taskId}/promote")
  public ResponseEntity<DriverTaskResponse> promote(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
    DriverTaskResponse current = service.get(taskId);
    access.requireEdit(jwt, current.warehouseId());
    scheduler.promoteRequested(taskId);
    return ResponseEntity.accepted()
        .eTag('"' + Long.toString(service.get(taskId).version()) + '"')
        .body(service.get(taskId));
  }

  private static ResponseEntity<DriverTaskResponse> response(
      DriverTaskResponse body, HttpStatus status, boolean replayed) {
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(status)
            .header(HttpHeaders.ETAG, '"' + Long.toString(body.version()) + '"');
    if (replayed) builder.header("Idempotency-Replayed", "true");
    return builder.body(body);
  }
}
