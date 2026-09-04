package dev.buhanzaz.rwms.logistics.vehicle.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanSnapshot;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignment;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentMode;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentSnapshot;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentStatus;
import dev.buhanzaz.rwms.logistics.vehicle.repository.VehicleOperationalAssignmentRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns durable vehicle reservations derived from confirmed transfer intent. Every mutation runs
 * under stable per-vehicle transaction advisory locks, including the empty-history case where a
 * pessimistic row lock alone cannot serialize competing reservations.
 */
@Service
@RequiredArgsConstructor
public class VehicleOperationalAssignmentService {
  private static final String LOCK_PREFIX = "vehicle-operational-assignment:";

  private final VehicleOperationalAssignmentRepository assignments;
  private final LogisticsTransactionLock transactionLock;

  /**
   * Creates the exact trip-only and reposition assignment set in the transfer-confirmation
   * transaction. An exact replay preserves the existing immutable rows. Pending commitments keep
   * one operational source because trip-only travel never changes vehicle basing, and a pending
   * reposition remains the final speculative commitment until it actually arrives.
   */
  @Transactional
  public List<VehicleOperationalAssignmentSnapshot> createForConfirmedTransfer(
      LogisticsDocument document,
      TransferPlanSnapshot transferPlan,
      Duration resourceArrivalBuffer) {
    List<PlanSpec> specs = plan(document, transferPlan, resourceArrivalBuffer);
    if (specs.isEmpty()) return List.of();
    acquireVehicleLocks(specs.stream().map(PlanSpec::vehicleId).toList());
    List<VehicleOperationalAssignment> existing =
        assignments.findAllForUpdateByTransferId(document.getId());
    if (!existing.isEmpty()) {
      requireExactPlan(existing, specs);
      return snapshots(existing);
    }
    OffsetDateTime createdAt = now();
    for (PlanSpec spec : specs) {
      requireAdmission(document.getId(), spec, createdAt);
    }
    List<VehicleOperationalAssignment> created =
        specs.stream()
            .map(
                spec ->
                    VehicleOperationalAssignment.planned(
                        document.getId(),
                        spec.vehicleId(),
                        spec.sourceWarehouseId(),
                        spec.destinationWarehouseId(),
                        spec.mode(),
                        transferPlan.plannedDepartureAt(),
                        spec.effectiveFrom(),
                        spec.effectiveUntil(),
                        createdAt))
            .toList();
    return snapshots(assignments.saveAllAndFlush(created));
  }

  /**
   * Advances every vehicle reserved by the transfer from PLANNED to IN_TRANSIT atomically. The
   * vehicle lock rechecks actual busy state and preserves the ordering of sequential future trip
   * reservations, so an early command cannot start a later trip ahead of an unfinished one.
   */
  @Transactional
  public void beginTransit(UUID transferId) {
    List<VehicleOperationalAssignment> locked = lockTransferAssignments(transferId);
    if (locked.isEmpty()) return;
    OffsetDateTime transitionedAt = now();
    for (VehicleOperationalAssignment assignment : locked) {
      if (assignment.getStatus() == VehicleOperationalAssignmentStatus.PLANNED) {
        requireNoStartedCommitment(
            assignment, assignments.findAllForUpdateByVehicleId(assignment.getVehicleId()));
      }
      assignment.beginTransit(transitionedAt);
    }
    assignments.saveAllAndFlush(locked);
  }

  /**
   * Completes trip-only travel or atomically replaces the active placement before activating a
   * temporary/permanent successor.
   */
  @Transactional
  public void arrive(UUID transferId) {
    List<VehicleOperationalAssignment> locked = lockTransferAssignments(transferId);
    if (locked.isEmpty()) return;
    OffsetDateTime transitionedAt = now();
    for (VehicleOperationalAssignment successor : locked) {
      if (successor.isArrivalApplied()) continue;
      if (successor.getMode() != VehicleOperationalAssignmentMode.TRIP_ONLY) {
        VehicleOperationalAssignment predecessor =
            activePredecessor(
                transferId,
                successor,
                assignments.findAllForUpdateByVehicleId(successor.getVehicleId()));
        if (predecessor != null) {
          predecessor.completePlacement(predecessorEnd(predecessor, successor), transitionedAt);
          assignments.saveAndFlush(predecessor);
        }
      }
      successor.arrive(transitionedAt);
    }
    assignments.saveAllAndFlush(locked);
  }

  /** Cancels every still-planned reservation while retaining immutable assignment history. */
  @Transactional
  public void cancelBeforeStart(UUID transferId) {
    List<VehicleOperationalAssignment> locked = lockTransferAssignments(transferId);
    if (locked.isEmpty()) return;
    OffsetDateTime transitionedAt = now();
    locked.forEach(value -> value.cancelBeforeStart(transitionedAt));
    assignments.saveAllAndFlush(locked);
  }

  /**
   * Verifies that every assignment required by the confirmed intent exists and remains PLANNED.
   * Transfers without a vehicle intent correctly require no rows.
   */
  @Transactional(readOnly = true)
  public boolean confirmationReady(
      LogisticsDocument document,
      TransferPlanSnapshot transferPlan,
      Duration resourceArrivalBuffer) {
    List<PlanSpec> expected = plan(document, transferPlan, resourceArrivalBuffer);
    List<VehicleOperationalAssignment> current =
        assignments.findAllByTransferIdOrderById(document.getId());
    return exactPlan(current, expected)
        && current.stream()
            .allMatch(
                value -> value.getStatus() == VehicleOperationalAssignmentStatus.PLANNED);
  }

  /** Returns whether arrival applied the terminal status required by every assignment mode. */
  @Transactional(readOnly = true)
  public boolean arrivalApplied(UUID transferId) {
    return assignments.findAllByTransferIdOrderById(transferId).stream()
        .allMatch(VehicleOperationalAssignment::isArrivalApplied);
  }

  /** Returns whether every vehicle reservation belonging to the transfer is cancelled. */
  @Transactional(readOnly = true)
  public boolean cancellationApplied(UUID transferId) {
    return assignments.findAllByTransferIdOrderById(transferId).stream()
        .allMatch(value -> value.getStatus() == VehicleOperationalAssignmentStatus.CANCELLED);
  }

  /**
   * Returns live chain closure for every vehicle whose history ever touched the warehouse. The
   * interval start is required for a valid half-open planning request, while the live ancestry
   * payload includes every reservation or placement beginning before its end.
   */
  @Transactional(readOnly = true)
  public List<VehicleOperationalAssignmentSnapshot> findPlanningWindow(
      UUID warehouseId, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
    Objects.requireNonNull(warehouseId, "warehouseId");
    Objects.requireNonNull(windowStart, "windowStart");
    Objects.requireNonNull(windowEnd, "windowEnd");
    if (!windowStart.isBefore(windowEnd)) {
      throw new IllegalArgumentException("Vehicle assignment windowStart must be before windowEnd");
    }
    return assignments
        .findLiveVehicleChainBefore(
            warehouseId,
            windowEnd,
            VehicleOperationalAssignmentStatus.PLANNED,
            VehicleOperationalAssignmentStatus.IN_TRANSIT,
            VehicleOperationalAssignmentStatus.ACTIVE)
        .stream()
        .map(VehicleOperationalAssignment::snapshot)
        .toList();
  }

  private List<VehicleOperationalAssignment> lockTransferAssignments(UUID transferId) {
    Objects.requireNonNull(transferId, "transferId");
    List<UUID> vehicleIds = assignments.findVehicleIdsByTransferId(transferId);
    if (vehicleIds.isEmpty()) return List.of();
    acquireVehicleLocks(vehicleIds);
    return assignments.findAllForUpdateByTransferId(transferId);
  }

  private void acquireVehicleLocks(List<UUID> vehicleIds) {
    transactionLock.acquireAll(
        vehicleIds.stream()
            .map(value -> LOCK_PREFIX + Objects.requireNonNull(value, "vehicleId"))
            .toList());
  }

  private void requireAdmission(UUID transferId, PlanSpec spec, OffsetDateTime currentTime) {
    List<VehicleOperationalAssignment> history =
        assignments.findAllForUpdateByVehicleId(spec.vehicleId());
    requireDepartureSource(transferId, spec, history);
    OffsetDateTime candidateEnd = plannedBusyUntil(spec.mode(), spec.effectiveFrom(), currentTime);
    boolean conflict =
        history.stream()
            .filter(value -> !value.getTransferId().equals(transferId))
            .filter(VehicleOperationalAssignmentService::isTravelCommitment)
            .anyMatch(
                value ->
                    intervalsOverlap(
                        spec.travelStartsAt(),
                        candidateEnd,
                        value.getTravelStartsAt(),
                        existingBusyUntil(value, currentTime)));
    if (conflict) throw overlap();
  }

  private static void requireDepartureSource(
      UUID transferId,
      PlanSpec successor,
      List<VehicleOperationalAssignment> history) {
    List<VehicleOperationalAssignment> active =
        history.stream()
            .filter(value -> !value.getTransferId().equals(transferId))
            .filter(value -> value.getStatus() == VehicleOperationalAssignmentStatus.ACTIVE)
            .toList();
    if (active.isEmpty()) {
      List<UUID> pendingSources =
          history.stream()
              .filter(value -> !value.getTransferId().equals(transferId))
              .filter(VehicleOperationalAssignmentService::isTravelCommitment)
              .map(VehicleOperationalAssignment::getSourceWarehouseId)
              .distinct()
              .toList();
      if (pendingSources.size() > 1
          || !pendingSources.isEmpty()
              && !pendingSources.getFirst().equals(successor.sourceWarehouseId())) {
        throw overlap();
      }
      return;
    }
    if (active.size() != 1) throw overlap();
    requireDepartureSource(active.getFirst(), successor);
  }

  private static void requireDepartureSource(
      VehicleOperationalAssignment predecessor, PlanSpec successor) {
    UUID requiredSource;
    if (predecessor.containsPlacementAt(successor.travelStartsAt())) {
      requiredSource = predecessor.getDestinationWarehouseId();
    } else if (predecessor.getMode() == VehicleOperationalAssignmentMode.TEMPORARY
        && predecessor.getEffectiveUntil() != null
        && !successor.travelStartsAt().isBefore(predecessor.getEffectiveUntil())) {
      requiredSource = predecessor.getSourceWarehouseId();
    } else {
      throw overlap();
    }
    if (!requiredSource.equals(successor.sourceWarehouseId())) throw overlap();
  }

  private static VehicleOperationalAssignment activePredecessor(
      UUID transferId,
      VehicleOperationalAssignment successor,
      List<VehicleOperationalAssignment> history) {
    List<VehicleOperationalAssignment> active =
        history.stream()
            .filter(value -> !value.getTransferId().equals(transferId))
            .filter(value -> value.getStatus() == VehicleOperationalAssignmentStatus.ACTIVE)
            .toList();
    if (active.isEmpty()) return null;
    if (active.size() != 1) throw overlap();
    VehicleOperationalAssignment predecessor = active.getFirst();
    requireDepartureSource(
        predecessor,
        new PlanSpec(
            successor.getVehicleId(),
            successor.getSourceWarehouseId(),
            successor.getDestinationWarehouseId(),
            successor.getMode(),
            successor.getTravelStartsAt(),
            successor.getEffectiveFrom(),
            successor.getEffectiveUntil()));
    return predecessor;
  }

  private static OffsetDateTime predecessorEnd(
      VehicleOperationalAssignment predecessor, VehicleOperationalAssignment successor) {
    if (predecessor.getMode() == VehicleOperationalAssignmentMode.TEMPORARY
        && predecessor.getEffectiveUntil() != null
        && predecessor.getEffectiveUntil().isBefore(successor.getEffectiveFrom())) {
      return predecessor.getEffectiveUntil();
    }
    return successor.getEffectiveFrom();
  }

  private static void requireNoStartedCommitment(
      VehicleOperationalAssignment current, List<VehicleOperationalAssignment> history) {
    boolean conflict =
        history.stream()
            .filter(value -> !value.getTransferId().equals(current.getTransferId()))
            .anyMatch(
                value ->
                    value.getStatus() == VehicleOperationalAssignmentStatus.IN_TRANSIT
                        || value.getStatus() == VehicleOperationalAssignmentStatus.PLANNED
                            && value.getTravelStartsAt().isBefore(current.getTravelStartsAt()));
    if (conflict) throw overlap();
  }

  private static boolean isTravelCommitment(VehicleOperationalAssignment assignment) {
    return assignment.getStatus() == VehicleOperationalAssignmentStatus.PLANNED
        || assignment.getStatus() == VehicleOperationalAssignmentStatus.IN_TRANSIT;
  }

  private static OffsetDateTime existingBusyUntil(
      VehicleOperationalAssignment assignment, OffsetDateTime currentTime) {
    if (assignment.getStatus() == VehicleOperationalAssignmentStatus.IN_TRANSIT) return null;
    return plannedBusyUntil(assignment.getMode(), assignment.getEffectiveFrom(), currentTime);
  }

  private static OffsetDateTime plannedBusyUntil(
      VehicleOperationalAssignmentMode mode,
      OffsetDateTime effectiveFrom,
      OffsetDateTime currentTime) {
    if (mode != VehicleOperationalAssignmentMode.TRIP_ONLY
        || !currentTime.isBefore(effectiveFrom)) {
      return null;
    }
    return effectiveFrom;
  }

  private static boolean intervalsOverlap(
      OffsetDateTime firstStart,
      OffsetDateTime firstEnd,
      OffsetDateTime secondStart,
      OffsetDateTime secondEnd) {
    return (secondEnd == null || firstStart.isBefore(secondEnd))
        && (firstEnd == null || secondStart.isBefore(firstEnd));
  }

  private static LogisticsConflictException overlap() {
    return new LogisticsConflictException(
        "VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP: vehicle is already reserved");
  }

  private static List<PlanSpec> plan(
      LogisticsDocument document,
      TransferPlanSnapshot transferPlan,
      Duration resourceArrivalBuffer) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(transferPlan, "transferPlan");
    Duration buffer = Objects.requireNonNull(resourceArrivalBuffer, "resourceArrivalBuffer");
    if (buffer.isNegative()) {
      throw new IllegalArgumentException("resourceArrivalBuffer cannot be negative");
    }
    OffsetDateTime travelStartsAt =
        Objects.requireNonNull(transferPlan.plannedDepartureAt(), "plannedDepartureAt");
    OffsetDateTime effectiveFrom =
        Objects.requireNonNull(transferPlan.plannedArrivalAt(), "plannedArrivalAt").plus(buffer);
    if (!travelStartsAt.isBefore(effectiveFrom)) {
      throw new IllegalArgumentException("Vehicle assignment travel interval is invalid");
    }
    List<PlanSpec> result = new ArrayList<>(2);
    TransferPlanSnapshot.ResourceIntent reposition = transferPlan.vehicleReposition();
    boolean hasReposition =
        reposition != null && reposition.mode() != TransferResourceRepositionMode.NONE;
    if (transferPlan.tripVehicleId() != null
        && (!hasReposition
            || !transferPlan.tripVehicleId().equals(reposition.resourceId()))) {
      result.add(
          new PlanSpec(
              transferPlan.tripVehicleId(),
              document.getWarehouseId(),
              document.getDestinationWarehouseId(),
              VehicleOperationalAssignmentMode.TRIP_ONLY,
              travelStartsAt,
              effectiveFrom,
              effectiveFrom));
    }
    if (hasReposition) {
      VehicleOperationalAssignmentMode mode = repositionMode(reposition.mode());
      OffsetDateTime effectiveUntil =
          mode == VehicleOperationalAssignmentMode.TEMPORARY ? reposition.until() : null;
      result.add(
          new PlanSpec(
              Objects.requireNonNull(reposition.resourceId(), "repositionedVehicleId"),
              document.getWarehouseId(),
              document.getDestinationWarehouseId(),
              mode,
              travelStartsAt,
              effectiveFrom,
              effectiveUntil));
    }
    return result.stream()
        .sorted(Comparator.comparing(value -> value.vehicleId().toString()))
        .toList();
  }

  private static VehicleOperationalAssignmentMode repositionMode(
      TransferResourceRepositionMode mode) {
    return switch (mode) {
      case TEMPORARY -> VehicleOperationalAssignmentMode.TEMPORARY;
      case PERMANENT -> VehicleOperationalAssignmentMode.PERMANENT;
      case NONE -> throw new IllegalArgumentException("NONE is not a vehicle assignment mode");
    };
  }

  private static void requireExactPlan(
      List<VehicleOperationalAssignment> current, List<PlanSpec> expected) {
    if (!exactPlan(current, expected)) {
      throw new LogisticsConflictException(
          "TRANSFER_VEHICLE_ASSIGNMENTS_IMMUTABLE: persisted assignments differ from plan");
    }
  }

  private static boolean exactPlan(
      List<VehicleOperationalAssignment> current, List<PlanSpec> expected) {
    if (current.size() != expected.size()) return false;
    Map<UUID, VehicleOperationalAssignment> byVehicle = new HashMap<>();
    for (VehicleOperationalAssignment assignment : current) {
      if (byVehicle.put(assignment.getVehicleId(), assignment) != null) return false;
    }
    for (PlanSpec spec : expected) {
      VehicleOperationalAssignment assignment = byVehicle.get(spec.vehicleId());
      if (assignment == null
          || !assignment.matchesPlan(
              spec.vehicleId(),
              spec.sourceWarehouseId(),
              spec.destinationWarehouseId(),
              spec.mode(),
              spec.travelStartsAt(),
              spec.effectiveFrom(),
              spec.effectiveUntil())) {
        return false;
      }
    }
    return true;
  }

  private static List<VehicleOperationalAssignmentSnapshot> snapshots(
      List<VehicleOperationalAssignment> values) {
    return values.stream()
        .sorted(Comparator.comparing(value -> value.getVehicleId().toString()))
        .map(VehicleOperationalAssignment::snapshot)
        .toList();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** Immutable vehicle reservation intent derived from one confirmed transfer plan. */
  private record PlanSpec(
      UUID vehicleId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      VehicleOperationalAssignmentMode mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil) {}
}
