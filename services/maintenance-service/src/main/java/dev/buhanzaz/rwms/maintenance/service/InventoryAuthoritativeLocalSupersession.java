package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcome;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeTarget;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Applies local historical supersession after every remote task, movement and lease effect has
 * reached replay-safe terminal truth.
 */
@Component
final class InventoryAuthoritativeLocalSupersession {
  private final MaintenanceEstimateRepository estimates;
  private final EstimateLineRepository estimateLines;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceReconciliationStore reconciliations;
  private final RepairPlaceService repairPlaces;

  InventoryAuthoritativeLocalSupersession(
      MaintenanceEstimateRepository estimates,
      EstimateLineRepository estimateLines,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceReconciliationStore reconciliations,
      RepairPlaceService repairPlaces) {
    this.estimates = estimates;
    this.estimateLines = estimateLines;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.reconciliations = reconciliations;
    this.repairPlaces = repairPlaces;
  }

  /**
   * Supersedes every captured predecessor, transfers at most one occupied place to a work target,
   * and proves that no other active local target remains.
   */
  AuthoritativeSupersessionResult apply(
      InventoryAuthoritativeOutcome outcome,
      List<InventoryAuthoritativeOutcomeTarget> targetRows) {
    if (outcome == null || targetRows == null) {
      throw new IllegalArgumentException("Authoritative local supersession input is required");
    }
    if (targetRows.stream().anyMatch(value -> !value.remoteSettled())) {
      throw new IllegalStateException("Authoritative remote effects are not settled");
    }
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(outcome.getAssetId());
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(outcome.getAssetId());
    Map<UUID, MaintenanceEstimate> estimateById = new LinkedHashMap<>();
    lockedEstimates.forEach(value -> estimateById.put(value.getId(), value));
    Map<UUID, MaintenanceRepair> repairById = new LinkedHashMap<>();
    lockedRepairs.forEach(value -> repairById.put(value.getId(), value));

    boolean allocationReassigned = false;
    for (InventoryAuthoritativeOutcomeTarget target : targetRows) {
      if (target.isLocalSuperseded() || !"REPAIR".equals(target.getTargetKind())) continue;
      if ("WORK".equals(outcome.getOutcomeKind())
          && !allocationReassigned
          && target.getRepairPlaceAllocationId() != null) {
        repairPlaces.reassignOccupiedForInventoryReplacement(
            outcome.getWarehouseId(),
            target.getTargetId(),
            outcome.getTargetRepairId(),
            target.getRepairPlaceAllocationId(),
            target.getRepairPlaceAllocationVersion());
        allocationReassigned = true;
      } else {
        repairPlaces.releaseForAuthoritativeInventory(
            outcome.getWarehouseId(),
            target.getTargetId(),
            target.getRepairPlaceAllocationId(),
            target.getRepairPlaceAllocationVersion());
      }
    }

    List<UUID> supersededEstimateIds = new ArrayList<>();
    List<UUID> supersededRepairIds = new ArrayList<>();
    for (InventoryAuthoritativeOutcomeTarget target : targetRows) {
      if (target.isLocalSuperseded()) {
        collect(target, supersededEstimateIds, supersededRepairIds);
        continue;
      }
      if ("ESTIMATE".equals(target.getTargetKind())) {
        MaintenanceEstimate estimate = estimateById.get(target.getTargetId());
        if (estimate == null) {
          throw InventoryPublicationPlanValidation.conflict(
              "Authoritative inventory estimate predecessor is missing");
        }
        supersedeEstimate(estimate);
      } else {
        MaintenanceRepair repair = repairById.get(target.getTargetId());
        if (repair == null) {
          throw InventoryPublicationPlanValidation.conflict(
              "Authoritative inventory repair predecessor is missing");
        }
        supersedeRepair(repair, target.getLeaseId() != null);
      }
      target.markLocalSuperseded();
      collect(target, supersededEstimateIds, supersededRepairIds);
    }

    boolean activeEstimate = lockedEstimates.stream()
        .anyMatch(InventoryPublicationTargetSelection::active);
    boolean activeRepair = lockedRepairs.stream()
        .filter(value -> !value.getId().equals(outcome.getTargetRepairId()))
        .anyMatch(InventoryPublicationTargetSelection::active);
    if (activeEstimate || activeRepair) {
      throw InventoryPublicationPlanValidation.conflict(
          "Authoritative inventory left an uncaptured active maintenance target");
    }
    return result(targetRows, supersededEstimateIds, supersededRepairIds);
  }

  private void supersedeEstimate(MaintenanceEstimate estimate) {
    if (estimate.getInventorySupersededAt() != null) return;
    if (estimate.getState() != EstimateState.DRAFT) {
      throw InventoryPublicationPlanValidation.conflict(
          "Authoritative inventory estimate predecessor became terminal");
    }
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.ESTIMATE, estimate.getId());
    if (expectedVersion != estimate.getVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Authoritative inventory estimate event stream changed");
    }
    estimate.supersedeForInventoryPublication();
    MaintenanceEstimate saved = estimates.saveAndFlush(estimate);
    Map<String, Object> state = projectionSnapshots.estimate(saved);
    events.append(
        MaintenanceAggregateType.ESTIMATE,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.ESTIMATE_DRAFT_CHANGED,
        state,
        eventFacts.estimatePayload(
            MaintenanceEventType.ESTIMATE_DRAFT_CHANGED,
            saved,
            Math.toIntExact(estimateLines.countByEstimateIdAndEstimateRevision(
                saved.getId(), saved.getRevision()))),
        state);
  }

  private void supersedeRepair(MaintenanceRepair repair, boolean releasedLease) {
    if (repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw InventoryPublicationPlanValidation.conflict(
          "Terminal accepted or written-off repair cannot be superseded by inventory");
    }
    if (repair.getExecutionState() == RepairExecutionState.CANCELLED) return;
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    if (expectedVersion != repair.getVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Authoritative inventory repair event stream changed");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    stages.forEach(RepairStage::supersedeForAuthoritativeInventory);
    repair.supersedeForAuthoritativeInventory();
    if (releasedLease) repair.releaseLease();
    repairStages.saveAllAndFlush(stages);
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    reconciliations.cancelPendingForRepair(saved.getId());
    Map<String, Object> state = projectionSnapshots.repair(saved);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        state,
        eventFacts.repairPayload(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved, stages),
        state);
  }

  private static void collect(
      InventoryAuthoritativeOutcomeTarget target,
      List<UUID> estimateIds,
      List<UUID> repairIds) {
    if ("ESTIMATE".equals(target.getTargetKind())) estimateIds.add(target.getTargetId());
    else repairIds.add(target.getTargetId());
  }

  private static AuthoritativeSupersessionResult result(
      List<InventoryAuthoritativeOutcomeTarget> targets,
      List<UUID> estimateIds,
      List<UUID> repairIds) {
    List<UUID> externalTasks = targets.stream()
        .filter(value -> "CANCELLED".equals(value.getTaskOutcome()))
        .map(InventoryAuthoritativeOutcomeTarget::getTaskExternalId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
    List<UUID> driverTasks = targets.stream()
        .filter(value -> "CANCELLED".equals(value.getDriverOutcome()))
        .map(InventoryAuthoritativeOutcomeTarget::getDriverTaskId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
    List<UUID> leases = targets.stream()
        .filter(InventoryAuthoritativeOutcomeTarget::isLeaseReleased)
        .map(InventoryAuthoritativeOutcomeTarget::getLeaseId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
    return new AuthoritativeSupersessionResult(
        estimateIds.stream().distinct().sorted().toList(),
        repairIds.stream().distinct().sorted().toList(),
        externalTasks,
        driverTasks,
        leases);
  }
}

/** Sorted audit identities produced by final local supersession. */
record AuthoritativeSupersessionResult(
    List<UUID> supersededEstimateIds,
    List<UUID> supersededRepairIds,
    List<UUID> cancelledExternalTaskIds,
    List<UUID> cancelledDriverTaskIds,
    List<UUID> releasedLeaseIds) {}
