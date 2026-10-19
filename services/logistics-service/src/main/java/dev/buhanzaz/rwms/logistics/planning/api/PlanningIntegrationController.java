package dev.buhanzaz.rwms.logistics.planning.api;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.WarehouseCapacitySnapshotService;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderPlanningIntegrationService;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentStatusResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningAssignmentsRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningAssignmentsResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningBaseTaskResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacitySnapshotResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDriverResource;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRequestFeedResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleOptionsResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningOrderRescheduleResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentWithdrawalRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningPublishedAssignmentWithdrawalResult;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningProvisionalEtaResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningProvisionalEtaUpdateRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningWarehouseResource;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningWarehouseSupportLinkResource;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReplacePlanningCapacitySnapshotRequest;
import dev.buhanzaz.rwms.logistics.planning.service.PlanningResourceDirectoryService;
import dev.buhanzaz.rwms.logistics.order.service.PlanningAssignmentReplacementService;
import dev.buhanzaz.rwms.logistics.planning.service.PlanningCustomerCommitmentService;
import dev.buhanzaz.rwms.logistics.planning.service.PlanningBaseTaskDirectoryService;
import dev.buhanzaz.rwms.logistics.planning.service.PlanningDriverTaskPreviewService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private HTTP boundary used only by the authenticated standalone logistics planner. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/internal/logistics/v1/planning")
public class PlanningIntegrationController {
  private final LogisticsAuthorizer access;
  private final RentalOrderPlanningIntegrationService planning;
  private final WarehouseCapacitySnapshotService capacitySnapshots;
  private final PlanningResourceDirectoryService resources;
  private final PlanningCustomerCommitmentService customerCommitments;
  private final PlanningBaseTaskDirectoryService baseTasks;
  private final PlanningDriverTaskPreviewService driverTaskPreviews;
  private final PlanningAssignmentReplacementService assignmentReplacements;

  /** Returns active RWMS warehouses with their owner-held address and timezone. */
  @GetMapping("/warehouses")
  public java.util.List<PlanningWarehouseResource> warehouses(
      @AuthenticationPrincipal Jwt jwt) {
    access.requirePlanningIntegration(jwt);
    return resources.warehouses();
  }

  /** Returns calendar-eligible directed support edges for route candidate generation. */
  @GetMapping("/warehouses/{servedWarehouseId}/support-links")
  public java.util.List<PlanningWarehouseSupportLinkResource> supportLinks(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID servedWarehouseId,
      @RequestParam OffsetDateTime at) {
    access.requirePlanningIntegration(jwt);
    return resources.supportLinks(servedWarehouseId, at);
  }

  /** Returns every active direct support edge for planning-group discovery. */
  @GetMapping("/warehouses/{warehouseId}/support-network")
  public java.util.List<PlanningWarehouseSupportLinkResource> supportNetwork(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requirePlanningIntegration(jwt);
    return resources.supportNetwork(warehouseId);
  }

  /** Returns active task-board-qualified drivers for one validated RWMS warehouse. */
  @GetMapping("/drivers")
  public java.util.List<PlanningDriverResource> drivers(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(required = false) OffsetDateTime at,
      @RequestParam(defaultValue = "false") boolean includeIncoming) {
    access.requirePlanningIntegration(jwt);
    return resources.drivers(warehouseId, at, includeIncoming);
  }

  /** Exports one warehouse's unscheduled delivery demand for a bounded date range. */
  @GetMapping("/requests")
  public PlanningRequestFeedResponse requests(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
    access.requirePlanningIntegration(jwt);
    return planning.feed(warehouseId, dateFrom, dateTo);
  }

  /** Returns route-feasible replacements for one still-current customer booking commitment. */
  @GetMapping("/orders/{orderId}/reschedule-options")
  public PlanningOrderRescheduleOptionsResponse rescheduleOptions(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestParam long expectedOrderVersion) {
    access.requirePlanningIntegration(jwt);
    return customerCommitments.options(orderId, expectedOrderVersion);
  }

  /** Applies one dispatcher-recorded customer agreement through the atomic booking lifecycle. */
  @PostMapping("/orders/{orderId}/reschedule")
  public PlanningOrderRescheduleResponse reschedule(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody PlanningOrderRescheduleRequest request) {
    access.requirePlanningIntegration(jwt);
    return customerCommitments.reschedule(orderId, idempotencyKey, request);
  }

  /** Returns existing low-priority shared warehouse work without claiming or duplicating it. */
  @GetMapping("/base-tasks")
  public java.util.List<PlanningBaseTaskResponse> baseTasks(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam OffsetDateTime availableAt,
      @RequestParam(defaultValue = "25") int limit) {
    access.requirePlanningIntegration(jwt);
    return baseTasks.candidates(warehouseId, availableAt, limit);
  }

  /** Refreshes or clears one shared future-task ETA after a newer route-plan revision. */
  @PutMapping("/assignments/{externalTaskId}/provisional-eta")
  public PlanningProvisionalEtaResponse provisionalEta(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID externalTaskId,
      @Valid @RequestBody PlanningProvisionalEtaUpdateRequest request) {
    access.requirePlanningIntegration(jwt);
    return driverTaskPreviews.replace(externalTaskId, request);
  }

  /** Applies route assignments through existing order and shipment invariants. */
  @PostMapping("/assignments")
  public ApplyPlanningAssignmentsResponse assignments(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ApplyPlanningAssignmentsRequest request) {
    access.requirePlanningIntegration(jwt);
    return planning.apply(idempotencyKey, request);
  }

  /** Atomically replaces one complete, still-unstarted revision of the same source plan. */
  @PutMapping("/assignments/{sourcePlanId}")
  public ReplacePlanningAssignmentsResponse replaceAssignments(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID sourcePlanId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReplacePlanningAssignmentsRequest request) {
    access.requirePlanningIntegration(jwt);
    return assignmentReplacements.replace(sourcePlanId, idempotencyKey, request);
  }

  /**
   * Atomically withdraws one owner-cancelled member while preserving the newer published lineage.
   */
  @PostMapping("/assignments/{sourcePlanId}/withdraw-cancelled")
  public PlanningPublishedAssignmentWithdrawalResult withdrawCancelledAssignment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID sourcePlanId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody PlanningPublishedAssignmentWithdrawalRequest request) {
    access.requirePlanningIntegration(jwt);
    return customerCommitments.withdrawCancelled(sourcePlanId, idempotencyKey, request);
  }

  /** Returns current driver ownership for planner-created shipment parts on one date. */
  @GetMapping("/assignments")
  public PlanningAssignmentStatusResponse assignmentStatuses(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    access.requirePlanningIntegration(jwt);
    return planning.assignmentStatuses(warehouseId, date);
  }

  /** Replaces the active anonymous simulator capacity used only by CustomerApp slot searches. */
  @PutMapping("/capacity-snapshots/{warehouseId}")
  public PlanningCapacitySnapshotResponse replaceCapacitySnapshot(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReplacePlanningCapacitySnapshotRequest request) {
    access.requirePlanningIntegration(jwt);
    return capacitySnapshots.replace(warehouseId, idempotencyKey, request);
  }
}
