package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.DriverTaskAudienceDto;
import dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.PutDriverShiftPlanRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Contracts for the all-or-nothing logistics planner revision replacement boundary. */
public final class PlanningReplacementApiModels {
  private PlanningReplacementApiModels() {}

  /** Outcome of an atomic replacement or its byte-identical receipt replay. */
  public enum PlanningReplacementOutcome {
    APPLIED,
    REPLAYED
  }

  /** One exact existing driver task and its desired date-preserving queue placement. */
  public record PlanningReplacementTaskRequest(
      @NotNull UUID externalTaskId,
      @NotNull UUID sourceTaskId,
      @NotNull UUID taskWarehouseId,
      @NotNull LocalDate scheduledDate,
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull @Min(0) Long expectedEntryVersion,
      @NotNull @Min(0) Integer targetQueuePosition,
      @NotNull @Valid DriverTaskAudienceDto driverAudience) {}

  /** One exact existing, unfrozen shift identity and its complete replacement snapshot. */
  public record PlanningReplacementShiftRequest(
      @NotNull UUID sourceShiftId, @NotNull @Valid PutDriverShiftPlanRequest plan) {}

  /** Complete current membership and strictly newer desired revision for one warehouse day. */
  public record PlanningReplacementRequest(
      @NotNull UUID warehouseId,
      @NotNull LocalDate date,
      @NotNull @Min(1) Long expectedSourcePlanVersion,
      @NotNull @Min(2) Long replacementPlanVersion,
      @NotEmpty @Size(max = 500)
          List<@NotNull @Valid PlanningReplacementTaskRequest> assignments,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid PlanningReplacementShiftRequest> driverShiftPlans) {
    public PlanningReplacementRequest {
      assignments = assignments == null ? List.of() : List.copyOf(assignments);
      driverShiftPlans = driverShiftPlans == null ? List.of() : List.copyOf(driverShiftPlans);
    }
  }

  /** Authoritative task and route-entry fences after the atomic replacement. */
  public record PlanningReplacementTaskResult(
      @NotNull UUID externalTaskId,
      @Min(0) long taskVersion,
      @NotNull UUID entryId,
      @Min(0) long entryVersion,
      @Min(0) int queuePosition) {}

  /** Authoritative task-board aggregate and source revisions for one replaced shift. */
  public record PlanningReplacementShiftResult(
      @NotNull UUID sourceShiftId,
      @Min(0) long shiftPlanVersion,
      @Min(1) long sourcePlanVersion) {}

  /** Complete replay-safe owner result for one source-plan revision. */
  public record PlanningReplacementResponse(
      @NotNull PlanningReplacementOutcome outcome,
      @NotNull UUID sourcePlanId,
      @Min(1) long sourcePlanVersion,
      @NotNull UUID warehouseId,
      @NotNull LocalDate date,
      @NotNull List<PlanningReplacementTaskResult> assignments,
      @NotNull List<PlanningReplacementShiftResult> driverShiftPlans) {
    public PlanningReplacementResponse {
      assignments = List.copyOf(assignments);
      driverShiftPlans = List.copyOf(driverShiftPlans);
    }
  }

  /** Exact old-day task that must be tombstoned only after the owner commitment changed. */
  public record PlanningRemovedTaskRequest(
      @NotNull UUID externalTaskId,
      @NotNull UUID sourceTaskId,
      @NotNull UUID taskWarehouseId,
      @NotNull LocalDate scheduledDate,
      @NotNull @Min(0) Long expectedTaskVersion,
      @NotNull @Min(0) Long expectedEntryVersion) {}

  /**
   * Complete desired remainder plus one explicit removed task for a customer-approved cross-date
   * reschedule. An empty remainder and empty shift list are valid for the last plan assignment.
   */
  public record PlanningReplanPrepareRequest(
      @NotNull UUID warehouseId,
      @NotNull LocalDate date,
      @NotNull @Min(1) Long expectedSourcePlanVersion,
      @NotNull @Min(2) Long replacementPlanVersion,
      @NotNull @Valid PlanningRemovedTaskRequest removedAssignment,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid PlanningReplacementTaskRequest> remainingAssignments,
      @NotNull @Size(max = 500)
          List<@NotNull @Valid PlanningReplacementShiftRequest> driverShiftPlans) {
    public PlanningReplanPrepareRequest {
      remainingAssignments =
          remainingAssignments == null ? List.of() : List.copyOf(remainingAssignments);
      driverShiftPlans = driverShiftPlans == null ? List.of() : List.copyOf(driverShiftPlans);
    }
  }

  /** Result of creating or exactly replaying a task-board execution hold. */
  public record PlanningReplanPrepareResponse(
      @NotNull String outcome,
      @NotNull UUID holdId,
      @NotNull UUID sourcePlanId,
      @Min(1) long sourcePlanVersion,
      @NotNull UUID removedExternalTaskId) {}

  /** Authoritative tombstone fence for the removed old-day task. */
  public record PlanningRemovedTaskResult(
      @NotNull UUID externalTaskId, @Min(0) long taskVersion, @NotNull String status) {}

  /** Complete commit/replay result after the hold becomes a durable plan revision. */
  public record PlanningReplanCommitResponse(
      @NotNull String outcome,
      @NotNull UUID holdId,
      @NotNull UUID sourcePlanId,
      @Min(1) long sourcePlanVersion,
      @NotNull PlanningRemovedTaskResult removedAssignment,
      @NotNull List<PlanningReplacementTaskResult> remainingAssignments,
      @NotNull List<PlanningReplacementShiftResult> driverShiftPlans) {
    public PlanningReplanCommitResponse {
      remainingAssignments = List.copyOf(remainingAssignments);
      driverShiftPlans = List.copyOf(driverShiftPlans);
    }
  }

  /** Release/replay result used only when logistics proves its owner mutation never committed. */
  public record PlanningReplanReleaseResponse(
      @NotNull String outcome, @NotNull UUID holdId, @NotNull UUID sourcePlanId) {}
}
