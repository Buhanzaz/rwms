package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.*;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.WorkerQueuePlanService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Public warehouse-scoped view of derived work queues and driver-queue configuration.
 *
 * <p>The endpoint exposes local queue identities used by assignments and task history. It does not
 * let a warehouse mutate the shared {@code GENERAL} catalog; local commands are limited to the
 * dedicated logistics-driver queue and each ordinary queue's WorkerApp publication plan.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}")
@Validated
public class WorkQueueController {
  private final RegistryService service;
  private final WorkerQueuePlanService workerPlans;
  private final WarehouseAccessAuthorizer access;

  /** Lists the selected warehouse's stable physical queue projections. */
  @GetMapping("/work-queues")
  public List<WorkQueueDto> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    read(jwt, warehouseId);
    return service.listQueues(warehouseId);
  }

  /** Returns active visible process capabilities for routing clients. */
  @GetMapping("/queue-capabilities")
  public WarehouseQueueCapabilities capabilities(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    read(jwt, warehouseId);
    return service.queueCapabilities(warehouseId);
  }

  /** Creates or replaces the warehouse-specific {@code LOGISTICS_DRIVER} queue configuration. */
  @PutMapping("/driver-queue")
  public WorkQueueDto updateDriverQueue(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody DriverQueueRequest request) {
    write(jwt, warehouseId);
    return service.updateDriverQueue(warehouseId, request);
  }

  /** Changes this warehouse's WorkerApp visibility and waiting-real plan for an ordinary queue. */
  @PutMapping("/work-queues/{queueId}/worker-plan")
  public WorkQueueDto updateWorkerPlan(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID queueId,
      @Valid @RequestBody WorkerQueuePlanRequest request) {
    write(jwt, warehouseId);
    return workerPlans.update(warehouseId, queueId, request);
  }

  private void read(Jwt jwt, UUID id) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, id, AccessLevel.VIEW, false);
  }

  private void write(Jwt jwt, UUID id) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, id, AccessLevel.MANAGE, false);
  }
}
