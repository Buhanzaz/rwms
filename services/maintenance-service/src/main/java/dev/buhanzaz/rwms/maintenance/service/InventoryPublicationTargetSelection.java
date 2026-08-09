package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationStrategy;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationTargetKind;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationApplyRequest;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationFindingInput;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationPrestartReplacement;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves and fences the existing maintenance target that an immutable inventory publication may
 * replace, merge with, or use as a successor predecessor.
 */
@Component
final class InventoryPublicationTargetSelection {
  private final MaintenanceEstimateRepository estimates;
  private final EstimateLineRepository estimateLines;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceReconciliationStore reconciliations;

  InventoryPublicationTargetSelection(
      MaintenanceEstimateRepository estimates,
      EstimateLineRepository estimateLines,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceReconciliationStore reconciliations) {
    this.estimates = estimates;
    this.estimateLines = estimateLines;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.reconciliations = reconciliations;
  }

  InventoryPublicationStrategyResolution applyStrategy(
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      List<MaintenanceEstimate> lockedEstimates,
      List<MaintenanceRepair> lockedRepairs,
      UUID warehouseId) {
    if (request.strategy() == InventoryPublicationStrategy.CREATE) {
      if (request.selectedTargetKind() != null || request.selectedTargetId() != null) {
        throw InventoryPublicationPlanValidation.invalid(
            "CREATE inventory publication cannot select an existing target");
      }
      boolean active = lockedEstimates.stream()
              .filter(value -> warehouseId.equals(value.getWarehouseId()))
              .anyMatch(InventoryPublicationTargetSelection::active)
          || lockedRepairs.stream()
              .filter(value -> warehouseId.equals(value.getWarehouseId()))
              .anyMatch(InventoryPublicationTargetSelection::active);
      if (active) {
        throw InventoryPublicationPlanValidation.conflict(
            "CREATE inventory publication requires resolution of the active maintenance target");
      }
      return InventoryPublicationStrategyResolution.none();
    }
    if (request.selectedTargetKind() == null || request.selectedTargetId() == null) {
      throw InventoryPublicationPlanValidation.invalid(
          "REPLACE or MERGE inventory publication requires one selected target");
    }
    requireNoOtherActiveTarget(
        request.selectedTargetKind(),
        request.selectedTargetId(),
        lockedEstimates,
        lockedRepairs,
        warehouseId);
    return switch (request.selectedTargetKind()) {
      case ESTIMATE -> InventoryPublicationStrategyResolution.superseded(
          supersedeEstimate(request.selectedTargetId(), finding.assetId(), warehouseId));
      case REPAIR -> resolveRepairStrategy(
          request.strategy(), request.selectedTargetId(), finding.assetId(), warehouseId);
    };
  }

  void requireNoOtherActiveTarget(
      InventoryPublicationTargetKind selectedKind,
      UUID selectedId,
      List<MaintenanceEstimate> lockedEstimates,
      List<MaintenanceRepair> lockedRepairs,
      UUID warehouseId) {
    boolean otherEstimate = lockedEstimates.stream()
        .filter(value -> warehouseId.equals(value.getWarehouseId()))
        .filter(InventoryPublicationTargetSelection::active)
        .anyMatch(value -> selectedKind != InventoryPublicationTargetKind.ESTIMATE
            || !selectedId.equals(value.getId()));
    boolean otherRepair = lockedRepairs.stream()
        .filter(value -> warehouseId.equals(value.getWarehouseId()))
        .filter(InventoryPublicationTargetSelection::active)
        .anyMatch(value -> selectedKind != InventoryPublicationTargetKind.REPAIR
            || !selectedId.equals(value.getId()));
    if (otherEstimate || otherRepair) {
      throw InventoryPublicationPlanValidation.conflict(
          "REPLACE or MERGE requires the selected target to be the only active maintenance target");
    }
  }

  InventoryPublicationPrestartCandidate prestartCandidate(MaintenanceRepair repair) {
    return prestartCandidateFor(repair);
  }

  static InventoryPublicationPrestartCandidate prestartCandidateFor(MaintenanceRepair repair) {
    if (repair.getExecutionState() == RepairExecutionState.QUEUED
        && repair.getAcceptanceState() == RepairAcceptanceState.NOT_READY
        && repair.getReclassificationState() == RepairReclassificationState.STABLE) {
      return new InventoryPublicationPrestartCandidate("ORDINARY", "DELIVER_TO_REPAIR");
    }
    if (repair.getExecutionState() == RepairExecutionState.COMPLETED
        && repair.getAcceptanceState() == RepairAcceptanceState.PENDING
        && repair.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL) {
      return new InventoryPublicationPrestartCandidate("EXTERNAL_CAPITAL", "CAPITAL_TO_PRODUCTION");
    }
    return null;
  }

  void requirePrestartCandidate(
      MaintenanceRepair repair, InventoryPublicationPrestartReplacement intent) {
    InventoryPublicationPrestartCandidate current = prestartCandidate(repair);
    if (current == null
        || !current.mode().equals(intent.getPredecessorMode())
        || !current.driverKind().equals(intent.getDriverKind())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Pre-start predecessor repair changed before replacement could finish");
    }
  }

  InventoryPublicationPrestartReplacement.LeaseIdentity prestartLease(MaintenanceRepair repair) {
    if (repair.getLeaseId() == null) {
      if (repair.getLeaseVersion() != null
          || repair.getFencingToken() != null
          || repair.getLeaseExpiresAt() != null
          || !("NOT_REQUIRED".equals(repair.getLeaseReconciliationState())
              || "RELEASED".equals(repair.getLeaseReconciliationState()))) {
        throw InventoryPublicationPlanValidation.conflict(
            "Pre-start predecessor has an incomplete operation lease");
      }
      return null;
    }
    if (repair.getLeaseVersion() == null
        || repair.getFencingToken() == null
        || repair.getLeaseExpiresAt() == null
        || !"ACTIVE".equals(repair.getLeaseReconciliationState())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Pre-start predecessor operation lease requires reconciliation");
    }
    MaintenanceRepair owner = repair.getRootRepairId() == null
        ? repair
        : repairs
            .findAllByIdForUpdate(List.of(repair.getRootRepairId()))
            .stream()
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair lease owner not found"));
    String ownerType = owner.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE";
    UUID ownerId = owner.getEstimateId() == null ? owner.getId() : owner.getEstimateId();
    return new InventoryPublicationPrestartReplacement.LeaseIdentity(
        repair.getLeaseId(),
        repair.getLeaseVersion(),
        repair.getFencingToken(),
        ownerType,
        ownerId);
  }

  InventoryPublicationTerminalProof terminalProof(MaintenanceRepair repair) {
    if (repair.getExecutionState() != RepairExecutionState.COMPLETED
        || repair.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL) {
      return null;
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    RepairStage terminal = stages.stream()
        .filter(stage -> stage.getState() == RepairStageState.DONE)
        .filter(stage -> stage.getCompletedEventId() != null)
        .max(Comparator.comparing(
                RepairStage::getCompletedAt,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(RepairStage::getId))
        .orElseThrow(() -> InventoryPublicationPlanValidation.conflict(
            "Completed repair has no task-board completion fact for successor ordering"));
    if (stages.isEmpty() || stages.stream().anyMatch(stage -> stage.getState() != RepairStageState.DONE)) {
      throw InventoryPublicationPlanValidation.conflict(
          "Completed repair has incomplete task-board stage truth for successor ordering");
    }
    return new InventoryPublicationTerminalProof(terminal.getCompletedEventId(), terminal.getCompletedAt());
  }

  InventoryPublicationTerminalProof acceptanceProof(MaintenanceRepair repair) {
    if (repair.getReclassificationState() != RepairReclassificationState.EXTERNAL_CAPITAL
        || repair.getAcceptanceState() != RepairAcceptanceState.ACCEPTED) {
      return null;
    }
    return events
        .latestFact(
            MaintenanceAggregateType.REPAIR, repair.getId(), MaintenanceEventType.REPAIR_ACCEPTED)
        .map(value -> new InventoryPublicationTerminalProof(value.eventId(), value.occurredAt()))
        .orElseThrow(
            () ->
                InventoryPublicationPlanValidation.conflict(
                    "Accepted external-capital predecessor has no persisted acceptance event "
                        + "for inventory successor ordering"));
  }

  MaintenanceRepair finalizePrestartPredecessor(
      MaintenanceRepair predecessor, InventoryPublicationPrestartReplacement intent) {
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, predecessor.getId());
    if (expectedVersion != predecessor.getVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Pre-start predecessor event stream does not match its current version");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(predecessor.getId());
    if ("ORDINARY".equals(intent.getPredecessorMode())) {
      stages.forEach(RepairStage::supersedeQueuedForInventoryPublication);
      predecessor.supersedeQueuedForInventoryPublication();
    } else {
      stages.forEach(RepairStage::supersedeExternalCapitalForInventoryPublication);
      predecessor.supersedeExternalCapitalForInventoryPublication();
    }
    if (intent.getLeaseId() != null) {
      predecessor.releaseLease();
    }
    repairStages.saveAllAndFlush(stages);
    MaintenanceRepair savedPredecessor = repairs.saveAndFlush(predecessor);
    reconciliations.cancelPendingForRepair(savedPredecessor.getId());
    Map<String, Object> state = projectionSnapshots.repair(savedPredecessor);
    events.append(
        MaintenanceAggregateType.REPAIR,
        savedPredecessor.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        state,
        eventFacts.repairPayload(MaintenanceEventType.REPAIR_PLAN_CHANGED, savedPredecessor, stages),
        state);
    return savedPredecessor;
  }

  private InventoryPublicationSupersededTarget supersedeEstimate(
      UUID estimateId, UUID assetId, UUID warehouseId) {
    MaintenanceEstimate estimate = estimates.findByIdForUpdate(estimateId).orElseThrow(
        () -> new MaintenanceNotFoundException("Selected estimate not found"));
    if (!assetId.equals(estimate.getRentalItemId()) || !warehouseId.equals(estimate.getWarehouseId())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Selected estimate does not belong to this inventory asset and warehouse");
    }
    if (estimate.getState() != EstimateState.DRAFT || estimate.getInventorySupersededAt() != null) {
      throw InventoryPublicationPlanValidation.conflict(
          "Selected estimate is no longer an active unstarted draft");
    }
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.ESTIMATE, estimateId);
    if (expectedVersion != estimate.getVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Selected estimate event stream does not match its current version");
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
    return new InventoryPublicationSupersededTarget(
        InventoryPublicationTargetKind.ESTIMATE, saved.getId());
  }

  private InventoryPublicationStrategyResolution resolveRepairStrategy(
      InventoryPublicationStrategy strategy, UUID repairId, UUID assetId, UUID warehouseId) {
    MaintenanceRepair repair = repairs.findAllByIdForUpdate(List.of(repairId)).stream()
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Selected repair not found"));
    if (!assetId.equals(repair.getRentalItemId()) || !warehouseId.equals(repair.getWarehouseId())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Selected repair does not belong to this inventory asset and warehouse");
    }
    if (repair.getExecutionState() == RepairExecutionState.DRAFT) {
      return InventoryPublicationStrategyResolution.superseded(supersedeDraftRepair(repair));
    }
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      throw queuedReplacementConflict(repair);
    }
    if (repair.getExecutionState() != RepairExecutionState.IN_PROGRESS
        && repair.getExecutionState() != RepairExecutionState.COMPLETED) {
      throw InventoryPublicationPlanValidation.conflict(
          "Selected repair is not an active maintenance predecessor");
    }
    if (repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw InventoryPublicationPlanValidation.conflict(
          "Selected repair already has a terminal acceptance decision");
    }
    if (strategy != InventoryPublicationStrategy.MERGE) {
      throw InventoryPublicationPlanValidation.conflict(
          "REPLACE cannot alter started repair work; use MERGE to create a successor after it");
    }
    return InventoryPublicationStrategyResolution.predecessor(repair, terminalProof(repair));
  }

  private MaintenanceConflictException queuedReplacementConflict(MaintenanceRepair repair) {
    if (repair.isMovementToRepair()) {
      return InventoryPublicationPlanValidation.conflict(
          "Queued repair has an inbound delivery whose pending/completed movement is not "
              + "cancellable or queryable through the maintenance logistics contract");
    }
    if (repair.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL) {
      return InventoryPublicationPlanValidation.conflict(
          "Queued external-capital handoff cannot be replaced before an acceptance fact");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    boolean taskBoardEffect = repair.getTaskBoardVersion() != null
        || stages.stream().anyMatch(stage -> stage.getExternalQueueEntryId() != null);
    if (taskBoardEffect) {
      return InventoryPublicationPlanValidation.conflict(
          "Queued repair has task-board state; the current maintenance contract cannot "
              + "atomically prove a pre-start cancellation");
    }
    return InventoryPublicationPlanValidation.conflict(
        "Queued repair owns a pending asset/lease lifecycle; no approved maintenance "
            + "pre-start compensation is available");
  }

  private InventoryPublicationSupersededTarget supersedeDraftRepair(MaintenanceRepair repair) {
    UUID repairId = repair.getId();
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repairId);
    if (expectedVersion != repair.getVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Selected repair event stream does not match its current version");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repairId);
    stages.forEach(RepairStage::supersedeForInventoryPublication);
    repair.supersedeForInventoryPublication();
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
    return new InventoryPublicationSupersededTarget(
        InventoryPublicationTargetKind.REPAIR, saved.getId());
  }

  static boolean active(MaintenanceEstimate estimate) {
    return estimate.getState() == EstimateState.DRAFT && estimate.getInventorySupersededAt() == null;
  }

  static boolean active(MaintenanceRepair repair) {
    return repair.getExecutionState() != RepairExecutionState.CANCELLED
        && repair.getAcceptanceState() != RepairAcceptanceState.ACCEPTED
        && repair.getAcceptanceState() != RepairAcceptanceState.WRITTEN_OFF;
  }
}

/** Existing target superseded atomically before a new publication target is persisted. */
record InventoryPublicationSupersededTarget(InventoryPublicationTargetKind kind, UUID id) {}

/** Local durable terminal event that may release a successor relation. */
record InventoryPublicationTerminalProof(UUID eventId, java.time.OffsetDateTime occurredAt) {}

/** Strategy decision against the locked estimate/repair set for one asset. */
record InventoryPublicationStrategyResolution(
    InventoryPublicationSupersededTarget superseded,
    MaintenanceRepair predecessor,
    InventoryPublicationTerminalProof terminalProof) {
  static InventoryPublicationStrategyResolution none() {
    return new InventoryPublicationStrategyResolution(null, null, null);
  }

  static InventoryPublicationStrategyResolution superseded(InventoryPublicationSupersededTarget value) {
    return new InventoryPublicationStrategyResolution(value, null, null);
  }

  static InventoryPublicationStrategyResolution predecessor(
      MaintenanceRepair value, InventoryPublicationTerminalProof terminalProof) {
    return new InventoryPublicationStrategyResolution(null, value, terminalProof);
  }
}

/** Pre-start mode and remote driver contract selected from a queued predecessor. */
record InventoryPublicationPrestartCandidate(String mode, String driverKind) {}
