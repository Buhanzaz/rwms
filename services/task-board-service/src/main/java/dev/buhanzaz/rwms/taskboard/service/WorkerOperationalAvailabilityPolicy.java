package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAvailabilityKind;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignment;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentMode;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentStatus;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Derives one worker's operational warehouse from immutable home and assignment history. */
final class WorkerOperationalAvailabilityPolicy {
  private static final Comparator<WorkerOperationalAssignment> LATEST_FIRST =
      Comparator.comparing(WorkerOperationalAssignment::getEffectiveFrom)
          .thenComparing(WorkerOperationalAssignment::getCreatedAt)
          .reversed();

  /**
   * Resolves guaranteed availability at one instant. Planned arrivals are intentionally returned
   * as unavailable and can only be projected separately as {@code INCOMING}.
   */
  OperationalPlacement resolve(
      Worker worker, List<WorkerOperationalAssignment> history, OffsetDateTime at) {
    if (history.stream()
        .anyMatch(
            assignment ->
                assignment.getStatus() == WorkerOperationalAssignmentStatus.IN_TRANSIT)) {
      return OperationalPlacement.unavailable();
    }

    WorkerOperationalAssignment active =
        history.stream()
            .filter(
                assignment ->
                    assignment.getStatus() == WorkerOperationalAssignmentStatus.ACTIVE)
            .max(Comparator.comparing(WorkerOperationalAssignment::getUpdatedAt))
            .orElse(null);
    if (active != null) {
      if (at.isBefore(active.getEffectiveFrom())) {
        return OperationalPlacement.unavailable();
      }
      if (active.getEffectiveUntil() == null || at.isBefore(active.getEffectiveUntil())) {
        return new OperationalPlacement(
            active.getDestinationWarehouseId(),
            LogisticsDriverAvailabilityKind.ACTIVE_ASSIGNMENT,
            active.getEffectiveFrom(),
            active.getEffectiveUntil());
      }
      return sourcePlacement(worker, active);
    }

    boolean committedElsewhere =
        history.stream()
            .filter(
                assignment ->
                    assignment.getStatus() == WorkerOperationalAssignmentStatus.PLANNED)
            .anyMatch(assignment -> travelCovers(assignment, at));
    if (committedElsewhere) {
      return OperationalPlacement.unavailable();
    }

    WorkerOperationalAssignment permanent =
        history.stream()
            .filter(
                assignment ->
                    assignment.getMode() == WorkerOperationalAssignmentMode.PERMANENT)
            .filter(
                assignment ->
                    assignment.getStatus() == WorkerOperationalAssignmentStatus.COMPLETED)
            .filter(assignment -> !at.isBefore(assignment.getEffectiveFrom()))
            .sorted(LATEST_FIRST)
            .findFirst()
            .orElse(null);
    if (permanent != null) {
      return new OperationalPlacement(
          permanent.getDestinationWarehouseId(),
          LogisticsDriverAvailabilityKind.ACTIVE_ASSIGNMENT,
          permanent.getEffectiveFrom(),
          null);
    }

    return new OperationalPlacement(
        worker.getWarehouseId(), LogisticsDriverAvailabilityKind.HOME, null, null);
  }

  /** Returns whether the planned arrival may be advertised for the supplied planning instant. */
  boolean incomingCovers(WorkerOperationalAssignment assignment, OffsetDateTime at) {
    return assignment.getMode() != WorkerOperationalAssignmentMode.TRIP_ONLY
        && assignment.getStatus() == WorkerOperationalAssignmentStatus.PLANNED
        && arrivalCovers(assignment, at);
  }

  private OperationalPlacement sourcePlacement(
      Worker worker, WorkerOperationalAssignment assignment) {
    return new OperationalPlacement(
        assignment.getSourceWarehouseId(),
        assignment.getSourceWarehouseId().equals(worker.getWarehouseId())
            ? LogisticsDriverAvailabilityKind.HOME
            : LogisticsDriverAvailabilityKind.ACTIVE_ASSIGNMENT,
        null,
        null);
  }

  private boolean travelCovers(WorkerOperationalAssignment assignment, OffsetDateTime at) {
    return !at.isBefore(assignment.getTravelStartsAt())
        && (assignment.getEffectiveUntil() == null
            || at.isBefore(assignment.getEffectiveUntil()));
  }

  private boolean arrivalCovers(WorkerOperationalAssignment assignment, OffsetDateTime at) {
    return !at.isBefore(assignment.getEffectiveFrom())
        && (assignment.getEffectiveUntil() == null
            || at.isBefore(assignment.getEffectiveUntil()));
  }

  /** Guaranteed operational placement or an unavailable marker for one instant. */
  record OperationalPlacement(
      UUID warehouseId,
      LogisticsDriverAvailabilityKind kind,
      OffsetDateTime availableFrom,
      OffsetDateTime availableUntil) {
    /** Returns an explicit marker that cannot be mistaken for home availability. */
    static OperationalPlacement unavailable() {
      return new OperationalPlacement(null, null, null, null);
    }

    /** Returns whether this placement represents usable availability. */
    boolean available() {
      return warehouseId != null;
    }
  }
}
