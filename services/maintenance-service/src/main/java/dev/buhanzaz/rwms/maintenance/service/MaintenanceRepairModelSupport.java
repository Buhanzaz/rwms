package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexityColors;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairTaskEvidenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/**
 * Loads, locks and maps repair aggregates while preserving repair-stream version fences.
 *
 * <p>Responses present even historical source-ordered stages in the mandatory ordinary-repair
 * phase order without mutating their append-only event history.
 */
@Service
final class MaintenanceRepairModelSupport {
  private static final String AFTER_RENT_STATUS = "AFTER_RENT";
  private static final String RENTED_STATUS = "RENTED";
  private final MaintenanceRepairRepository repairs;
  private final InventoryRepairSourceRepository inventorySources;
  private final RepairStageRepository repairStages;
  private final RepairTaskEvidenceRepository taskEvidence;
  private final RentalItemFactProjectionRepository rentalItemFacts;
  private final MaintenanceEventStore events;
  private final RepairComplexitySettingsService repairComplexitySettings;
  private final RepairComplexityColorsService repairComplexityColors;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceMediaSupport mediaSupport;
  private final InventoryRepairSourceReadProjection authoritativeInventorySources;

  MaintenanceRepairModelSupport(
      MaintenanceRepairRepository repairs,
      InventoryRepairSourceRepository inventorySources,
      RepairStageRepository repairStages,
      RepairTaskEvidenceRepository taskEvidence,
      RentalItemFactProjectionRepository rentalItemFacts,
      MaintenanceEventStore events,
      RepairComplexitySettingsService repairComplexitySettings,
      RepairComplexityColorsService repairComplexityColors,
      MaintenanceCommandSupport commandSupport,
      MaintenanceMediaSupport mediaSupport,
      InventoryRepairSourceReadProjection authoritativeInventorySources) {
    this.repairs = repairs;
    this.inventorySources = inventorySources;
    this.repairStages = repairStages;
    this.taskEvidence = taskEvidence;
    this.rentalItemFacts = rentalItemFacts;
    this.events = events;
    this.repairComplexitySettings = repairComplexitySettings;
    this.repairComplexityColors = repairComplexityColors;
    this.commandSupport = commandSupport;
    this.mediaSupport = mediaSupport;
    this.authoritativeInventorySources = authoritativeInventorySources;
  }

  protected MaintenanceRepair requireRepair(UUID id) {
    return repairs.findById(id).orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
  }

  protected List<RepairWorkerEvidenceResponse> repairWorkerEvidence(UUID repairId) {
    Map<UUID, Integer> stageIndexes =
        repairStages.findAllByRepairIdOrderByStageNo(repairId).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    RepairStage::getId, RepairStage::getStageNo));
    return taskEvidence.findAllByRepairIdOrderByRecordedAtAscEvidenceIdAsc(repairId).stream()
        .map(
            item ->
                new RepairWorkerEvidenceResponse(
                    item.getEvidenceId(),
                    item.getRepairId(),
                    item.getRepairStageId(),
                    stageIndexes.getOrDefault(item.getRepairStageId(), item.getRouteIndex()),
                    item.getEntryId(),
                    item.getTaskId(),
                    item.getRouteIndex(),
                    item.getWorkerId(),
                    item.getWorkerGroupId(),
                    item.getMediaId(),
                    item.getMediaGeneration(),
                    item.getCapturedAt(),
                    item.getRecordedAt(),
                    TaskEvidenceState.valueOf(item.getEvidenceState())))
        .toList();
  }

  protected RentalItemFactProjection requireRentalItemFact(
      UUID rentalItemId, UUID warehouseId) {
    RentalItemFactProjection fact = rentalItemFacts.findById(rentalItemId).orElseThrow(() ->
        new MaintenanceValidationException(
            "MAINTENANCE_DEPENDENCY_UNAVAILABLE",
            "Current rental-item ownership/version fact is not available"));
    return requireRentalItemWarehouse(fact, warehouseId);
  }

  /**
   * Locks the maintenance-owned rental-item fact used to serialize direct-repair creation.
   * Callers must already run in the final local write transaction.
   */
  protected RentalItemFactProjection requireRentalItemFactForUpdate(
      UUID rentalItemId, UUID warehouseId) {
    RentalItemFactProjection fact = rentalItemFacts.findByIdForUpdate(rentalItemId).orElseThrow(() ->
        new MaintenanceValidationException(
            "MAINTENANCE_DEPENDENCY_UNAVAILABLE",
            "Current rental-item ownership/version fact is not available"));
    return requireRentalItemWarehouse(fact, warehouseId);
  }

  private static RentalItemFactProjection requireRentalItemWarehouse(
      RentalItemFactProjection fact, UUID warehouseId) {
    if (!warehouseId.equals(fact.getWarehouseId())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Rental item does not belong to the command warehouse");
    }
    return fact;
  }

  protected static void requireEstimateSourceStatus(RentalItemFactProjection rentalItem) {
    if (!AFTER_RENT_STATUS.equals(rentalItem.getAssetStatus())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Estimate source rental item must have AFTER_RENT status");
    }
  }

  protected static void requireDirectRepairSourceStatus(RentalItemFactProjection rentalItem) {
    if (RENTED_STATUS.equals(rentalItem.getAssetStatus())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Direct repair source rental item must not have RENTED status");
    }
  }

  protected RepairResponse repairResponse(MaintenanceRepair value) {
    Map<UUID, List<TaskEvidenceResponse>> evidenceByStage =
        taskEvidence.findAllByRepairIdOrderByRecordedAtAscEvidenceIdAsc(value.getId()).stream()
            .map(
                evidence ->
                    Map.entry(
                        evidence.getRepairStageId(),
                        new TaskEvidenceResponse(
                            evidence.getEvidenceId(),
                            evidence.getEntryId(),
                            evidence.getWorkerId(),
                            evidence.getWorkerGroupId(),
                            evidence.getMediaId(),
                            evidence.getMediaGeneration(),
                            evidence.getCapturedAt(),
                            evidence.getRecordedAt(),
                            TaskEvidenceState.valueOf(evidence.getEvidenceState()))))
            .collect(
                java.util.stream.Collectors.groupingBy(
                    Map.Entry::getKey,
                    LinkedHashMap::new,
                    java.util.stream.Collectors.mapping(
                        Map.Entry::getValue, java.util.stream.Collectors.toList())));
    List<RepairStage> orderedStages =
        RepairPhaseSequence.canonicalStages(
            repairStages.findAllByRepairIdOrderByStageNo(value.getId()));
    List<RepairStageResponse> stages = IntStream.range(0, orderedStages.size())
        .mapToObj(index -> {
          RepairStage stage = orderedStages.get(index);
          return new RepairStageResponse(
            stage.getId(), stage.getStageKind(), index, stage.getState(),
            new RoutingSnapshot(stage.getRoutingQueueId(), stage.getRoutingQueueName(),
                stage.getRoutingQueueType()),
            commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class),
            commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class),
            stage.getPrimaryLineId(),
            stage.getGroupComment(),
            List.copyOf(evidenceByStage.getOrDefault(stage.getId(), List.of())),
            stage.getTaskDeadline(), new TaskSyncSnapshot(
                value.getExternalTaskId(), stage.getExternalQueueEntryId(),
                stage.getTaskBoardVersion(),
                GenerationState.valueOf(stage.getTaskGenerationState()),
                new DeliverySnapshot(
                    DeliveryState.valueOf(stage.getDeliveryState()),
                    stage.getDeliveryAttempts(), stage.getDeliveryUpdatedAt())),
            stage.getCompletedAt());
        })
        .toList();
    InventorySourceReference inventorySource =
        authoritativeInventorySources
            .findAuthoritativeSource(value.getId())
            .or(
                () ->
                    inventorySources
                        .findByRepairId(value.getId())
                        .map(
                            source ->
                                new InventorySourceReference(
                                    source.getInventoryId(),
                                    source.getFindingId(),
                                    source.getSourceRevision(),
                                    source.getPlanFingerprint(),
                                    source.getSourceFingerprint())))
            .orElse(null);
    List<MediaReferenceInput> aggregateMedia = mediaSupport.repairMedia(value);
    RepairComplexitySnapshot complexity = repairComplexity(value, stages);
    return new RepairResponse(
        value.getId(), commandSupport.rootId(value), value.getSourceRepairId(), value.getEstimateId(),
        value.getWarehouseId(), value.getRentalItemId(), value.getOrigin(), value.getKind(),
        value.getExecutionState(), value.getAcceptanceState(), value.getReclassificationState(),
        value.getVersion(), value.getDispatchDate(),
        value.getPriority(), value.getSourceParty(),
        new RepairPlanResponse(value.getId(), value.getVersion(), stages),
        inventorySource,
        value.getLeaseId() == null ? null : new LeaseSnapshot(
            value.getLeaseId(), value.getFencingToken(), value.getLeaseExpiresAt(),
            leaseReconciliationState(value.getLeaseReconciliationState())),
        aggregateMedia, mediaSupport.effectiveCoverMediaId(value.getCoverMediaId(), aggregateMedia),
        complexity,
        value.isMovementToRepair(),
        value.getLogisticsPlanningMode(),
        value.getLogisticsScheduledDate(),
        value.getCreatedAt(), value.getUpdatedAt(),
        commandSupport.actor(value.getActorRef()), value.isForceCapitalRepair());
  }

  /** Classifies a repair response from its plan plus its persisted explicit capital override. */
  protected RepairComplexitySnapshot repairComplexity(
      MaintenanceRepair repair, List<RepairStageResponse> stages) {
    return repairComplexityForLines(
        repair.getWarehouseId(),
        stages.stream().flatMap(stage -> stage.workLines().stream()).toList(),
        repair.isForceCapitalRepair());
  }

  protected RepairComplexitySnapshot repairComplexity(
      UUID warehouseId, List<RepairStageResponse> stages) {
    return repairComplexityForLines(
        warehouseId,
        stages.stream().flatMap(stage -> stage.workLines().stream()).toList());
  }

  protected RepairComplexitySnapshot repairComplexityFromStoredStages(
      UUID warehouseId, UUID repairId) {
    return repairComplexityFromStoredStages(warehouseId, requireRepair(repairId));
  }

  protected RepairComplexitySnapshot repairComplexityFromStoredStages(
      MaintenanceRepair repair) {
    return repairComplexityFromStoredStages(repair.getWarehouseId(), repair);
  }

  /**
   * Rebuilds complexity from immutable stage evidence while retaining the repair's explicit
   * capital override; {@code warehouseId} may differ during a prepared warehouse transfer.
   */
  protected RepairComplexitySnapshot repairComplexityFromStoredStages(
      UUID warehouseId, MaintenanceRepair repair) {
    List<EstimateLineResponse> lines =
        repairStages.findAllByRepairIdOrderByStageNo(repair.getId()).stream()
            .flatMap(
                stage ->
                    commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class).stream())
            .toList();
    return repairComplexityForLines(
        warehouseId, lines, repair.isForceCapitalRepair());
  }

  protected RepairComplexitySnapshot repairComplexityForLines(
      UUID warehouseId, List<EstimateLineResponse> lines) {
    return repairComplexityForLines(warehouseId, lines, false);
  }

  /** Classifies the current plan from the explicit override or capital-forcing WORK snapshots. */
  protected RepairComplexitySnapshot repairComplexityForLines(
      UUID warehouseId, List<EstimateLineResponse> lines, boolean forceCapitalRepair) {
    BigDecimal plannedMinutes = BigDecimal.ZERO;
    boolean forcedCapital = forceCapitalRepair;
    for (EstimateLineResponse line : lines) {
      plannedMinutes =
          plannedMinutes.add(
              new BigDecimal(line.quantity())
                  .multiply(BigDecimal.valueOf(line.normativeMinutes())));
      if (line.lineType() == EstimateLineType.WORK
          && line.catalogSnapshot() != null
          && line.catalogSnapshot().forcesCapitalRepair()) {
        forcedCapital = true;
      }
    }
    RepairComplexity type =
        repairComplexitySettings
            .requireSettings(warehouseId)
            .classify(plannedMinutes, forcedCapital);
    RepairComplexityColors colors = repairComplexityColors.requireColors();
    String canonicalMinutes =
        plannedMinutes.signum() == 0
            ? "0"
            : plannedMinutes.stripTrailingZeros().toPlainString();
    return new RepairComplexitySnapshot(
        type,
        type.displayName(),
        colors.color(type),
        canonicalMinutes,
        forcedCapital);
  }

  protected MaintenanceRepair requireSingleActiveRepair(
      List<MaintenanceRepair> values, boolean preparedOnly) {
    List<MaintenanceRepair> active =
        values.stream()
            .filter(
                repair ->
                    repair.getAcceptanceState() != RepairAcceptanceState.ACCEPTED
                        && repair.getAcceptanceState()
                            != RepairAcceptanceState.WRITTEN_OFF
                        && repair.getExecutionState()
                            != RepairExecutionState.CANCELLED)
            .filter(
                repair ->
                    !preparedOnly
                        || "DEPARTURE_PREPARED".equals(
                            repair.getTransferState()))
            .toList();
    Set<UUID> roots =
        active.stream()
            .map(MaintenanceCommandSupport::rootId)
            .collect(java.util.stream.Collectors.toSet());
    if (roots.size() > 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Rental item has more than one active repair chain");
    }
    return active.stream()
        .max(
            Comparator.comparing(MaintenanceRepair::getCreatedAt)
                .thenComparing(repair -> repair.getId().toString()))
        .orElse(null);
  }

  protected List<MaintenanceRepair> lockRepairChain(MaintenanceRepair active) {
    List<MaintenanceRepair> chain = repairs.findRepairChain(commandSupport.rootId(active));
    if (chain.isEmpty()) {
      throw new MaintenanceNotFoundException("Active repair chain not found");
    }
    return repairs.findAllByIdForUpdate(
        chain.stream().map(MaintenanceRepair::getId).toList());
  }

  protected static MaintenanceRepair requireRepairInChain(
      List<MaintenanceRepair> chain, UUID repairId) {
    return chain.stream()
        .filter(repair -> repair.getId().equals(repairId))
        .findFirst()
        .orElseThrow(
            () ->
                new MaintenanceConflictException(
                    "MAINTENANCE_STATE_CONFLICT",
                    "Active repair changed during transfer"));
  }

  protected static void requireTransferSource(
      List<MaintenanceRepair> chain,
      UUID rentalItemId,
      UUID sourceWarehouseId) {
    if (chain.stream()
        .anyMatch(
            repair ->
                !rentalItemId.equals(repair.getRentalItemId())
                    || !sourceWarehouseId.equals(
                        repair.getWarehouseId()))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Active repair does not belong to the transfer source warehouse");
    }
  }

  protected static void requirePreparedTransfer(
      List<MaintenanceRepair> chain,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    if (chain.isEmpty()
        || chain.stream()
            .anyMatch(
                repair ->
                    !"DEPARTURE_PREPARED".equals(
                            repair.getTransferState())
                        || !transferId.equals(
                            repair.getTransferDocumentId())
                        || !lineId.equals(repair.getTransferLineId())
                        || !targetWarehouseId.equals(
                            repair.getTransferTargetWarehouseId())
                        || !rentalItemId.equals(
                            repair.getRentalItemId())
                        || !sourceWarehouseId.equals(
                            repair.getWarehouseId()))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair chain is not prepared for this warehouse transfer");
    }
  }

  protected void lockRepairStreams(List<MaintenanceRepair> chain) {
    Map<MaintenanceEventStore.StreamRef, Long> versions =
        events.lockStreams(
            chain.stream()
                .map(
                    repair ->
                        new MaintenanceEventStore.StreamRef(
                            MaintenanceAggregateType.REPAIR,
                            repair.getId()))
                .toList());
    for (MaintenanceRepair repair : chain) {
      Long streamVersion =
          versions.get(
              new MaintenanceEventStore.StreamRef(
                  MaintenanceAggregateType.REPAIR, repair.getId()));
      commandSupport.assertVersion(streamVersion, repair.getVersion());
    }
  }

  protected static MaintenanceRepair repairLifecycleOwner(
      List<MaintenanceRepair> chain, MaintenanceRepair active) {
    UUID rootRepairId = MaintenanceCommandSupport.rootId(active);
    return chain.stream()
        .filter(repair -> repair.getId().equals(rootRepairId))
        .findFirst()
        .orElseThrow(
            () ->
                new MaintenanceConflictException(
                    "MAINTENANCE_STATE_CONFLICT",
                    "Repair lifecycle owner is missing"));
  }

  protected boolean hasUnfinishedStages(UUID repairId) {
    return repairStages.findAllByRepairIdOrderByStageNo(repairId).stream()
        .anyMatch(
            stage ->
                stage.getState() != RepairStageState.DONE
                    && stage.getState() != RepairStageState.CANCELLED);
  }

  protected DeliverySnapshot delivery(MaintenanceRepair value) {
    return new DeliverySnapshot(
        DeliveryState.valueOf(value.getDeliveryState()),
        value.getDeliveryAttempts(), value.getDeliveryUpdatedAt());
  }

  protected static LeaseReconciliationState leaseReconciliationState(String value) {
    return "NOT_REQUIRED".equals(value)
        ? LeaseReconciliationState.NOT_ACQUIRED : LeaseReconciliationState.valueOf(value);
  }
}
