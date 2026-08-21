package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.EstimateRevision;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Materializes repair aggregates and persists estimate revisions in the mandatory phase order.
 *
 * <p>It has no public command boundary and delegates line, routing and plan validation to
 * {@link MaintenanceEstimateSupport}; source stage order is canonicalized only after that content
 * has passed validation.</p>
 */
@Service
final class MaintenanceEstimateRevisionSupport {
  private final EstimateLineRepository estimateLines;
  private final EstimatePlanStageRepository estimatePlans;
  private final EstimateRevisionRepository estimateRevisions;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateModelSupport estimateModelSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceMediaSupport mediaSupport;
  private final MaintenanceEstimateSupport estimateSupport;

  MaintenanceEstimateRevisionSupport(
      EstimateLineRepository estimateLines,
      EstimatePlanStageRepository estimatePlans,
      EstimateRevisionRepository estimateRevisions,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateModelSupport estimateModelSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceMediaSupport mediaSupport,
      MaintenanceEstimateSupport estimateSupport) {
    this.estimateLines = estimateLines;
    this.estimatePlans = estimatePlans;
    this.estimateRevisions = estimateRevisions;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.commandSupport = commandSupport;
    this.estimateModelSupport = estimateModelSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.mediaSupport = mediaSupport;
    this.estimateSupport = estimateSupport;
  }

  protected MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate, List<EstimatePlanStage> estimatePlan) {
    return createEstimateRepair(
        estimate,
        estimatePlan,
        3,
        false,
        null,
        null);
  }

  protected MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate, List<EstimatePlanStage> estimatePlan, int priority) {
    return createEstimateRepair(
        estimate,
        estimatePlan,
        priority,
        false,
        null,
        null);
  }

  protected MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate,
      List<EstimatePlanStage> estimatePlan,
      int priority,
      boolean movementToRepair,
      RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate) {
    return createEstimateRepair(
        estimate,
        estimatePlan,
        priority,
        movementToRepair,
        logisticsPlanningMode,
        logisticsScheduledDate,
        FurnitureAccountingMode.TRACKED_CABIN_CONTENTS);
  }

  protected MaintenanceRepair createEstimateRepair(
      MaintenanceEstimate estimate,
      List<EstimatePlanStage> estimatePlan,
      int priority,
      boolean movementToRepair,
      RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      FurnitureAccountingMode furnitureAccountingMode) {
    return createEstimateRepairFromPlan(
        estimate,
        estimateSupport.storedPlanInputs(estimatePlan),
        priority,
        movementToRepair,
        logisticsPlanningMode,
        logisticsScheduledDate,
        furnitureAccountingMode);
  }

  protected MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate, List<PlanStageInput> plan) {
    return createEstimateRepairFromPlan(
        estimate,
        plan,
        3,
        false,
        null,
        null);
  }

  protected MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate, List<PlanStageInput> plan, int priority) {
    return createEstimateRepairFromPlan(
        estimate,
        plan,
        priority,
        false,
        null,
        null);
  }

  protected MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate,
      List<PlanStageInput> plan,
      int priority,
      boolean movementToRepair,
      RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate) {
    return createEstimateRepairFromPlan(
        estimate,
        plan,
        priority,
        movementToRepair,
        logisticsPlanningMode,
        logisticsScheduledDate,
        FurnitureAccountingMode.TRACKED_CABIN_CONTENTS);
  }

  protected MaintenanceRepair createEstimateRepairFromPlan(
      MaintenanceEstimate estimate,
      List<PlanStageInput> plan,
      int priority,
      boolean movementToRepair,
      RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      FurnitureAccountingMode furnitureAccountingMode) {
    MaintenanceRepair newRepair = MaintenanceRepair.primary(
        estimate.getWarehouseId(), estimate.getRentalItemId(), estimate.getRentalItemVersionSnapshot(),
        estimate.getId(), RepairOrigin.ESTIMATE, estimate.getDispatchDate(), estimate.getSourceParty(), commandSupport.actorJson());
    if (furnitureAccountingMode == FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS) {
      newRepair.useUnaccountedFurnitureAccounting();
    }
    newRepair.selectForceCapitalRepair(estimate.isForceCapitalRepair());
    newRepair.selectPriority(priority);
    newRepair.selectMovementToRepair(
        movementToRepair,
        logisticsPlanningMode, logisticsScheduledDate);
    newRepair.replaceCoverMediaId(estimate.getCoverMediaId());
    MaintenanceRepair repair = repairs.saveAndFlush(newRepair);
    replaceRepairStages(
        repair,
        plan,
        estimateModelSupport.lineResponses(estimate.getId(), estimate.getRevision()));
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        eventPayloadSupport.repairLocal(repair),
        eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_CREATED, repair),
        eventPayloadSupport.repairSnapshot(repair));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        repair.getId(),
        repair.getVersion(),
        true);
    return repair;
  }

  protected void replaceEstimateRevision(
      MaintenanceEstimate estimate,
      List<EstimateLineInput> lineInputs,
      List<PlanStageInput> planInputs,
      String amendmentReason) {
    int revision = estimate.getRevision();
    if (estimate.getState() == EstimateState.DRAFT) {
      estimateLines.deleteAllByEstimateIdAndEstimateRevision(estimate.getId(), revision);
      estimatePlans.deleteAllByEstimateIdAndEstimateRevision(estimate.getId(), revision);
      estimateLines.flush();
      estimatePlans.flush();
    }
    List<EstimateLine> lines = new ArrayList<>();
    List<EstimateLineResponse> canonicalLines = new ArrayList<>();
    List<CatalogNodeSnapshot> canonicalSnapshots = new ArrayList<>();
    Set<UUID> lineIds = new HashSet<>();
    for (int index = 0; index < lineInputs.size(); index++) {
      EstimateLineInput input = lineInputs.get(index);
      if (!lineIds.add(input.id())) throw MaintenanceCommandSupport.invalid("Estimate line IDs must be unique inside a revision");
      CatalogNodeSnapshot catalogSnapshot = estimateSupport.canonicalCatalogSnapshot(
          estimate, input.catalogSnapshot());
      if (catalogSnapshot != null) canonicalSnapshots.add(catalogSnapshot);
      EstimateLineType lineType = estimateSupport.canonicalLineType(catalogSnapshot, input.lineType());
      String unit = estimateSupport.canonicalLineUnit(catalogSnapshot, input.unit());
      BigDecimal quantity = new BigDecimal(input.quantity());
      if (catalogSnapshot != null
          && catalogSnapshot.furnitureEquipment() != null
          && quantity.signum() > 0
          && quantity.stripTrailingZeros().scale() > 0) {
        throw MaintenanceCommandSupport.invalid("Furniture quantity must be a whole number");
      }
      mediaSupport.validateMediaReferences(
          "MAINTENANCE_ESTIMATE", estimate.getId(), estimate.getWarehouseId(), input.mediaReferences());
      long unitPriceMinor = commandSupport.moneyToMinor(input.unitPrice());
      int normativeMinutes =
          estimateSupport.estimateLineNormativeMinutes(catalogSnapshot, lineType, input.normativeMinutes());
      lines.add(new EstimateLine(
          input.id(), estimate.getId(), revision, index,
          catalogSnapshot == null ? null : catalogSnapshot.nodeId(),
          lineType.name(), input.description(), unit, quantity, unitPriceMinor,
          normativeMinutes,
          catalogSnapshot == null || catalogSnapshot.routing() == null
              ? null : catalogSnapshot.routing().queueId().toString(),
          catalogSnapshot == null ? null : commandSupport.write(catalogSnapshot),
          estimateSupport.workLineComment(lineType, input.comment()), commandSupport.write(input.mediaReferences())));
      canonicalLines.add(
          new EstimateLineResponse(
              input.id(),
              catalogSnapshot,
              lineType,
              input.description(),
              unit,
              commandSupport.quantity(quantity),
              commandSupport.money(unitPriceMinor),
              commandSupport.money(quantity.multiply(BigDecimal.valueOf(unitPriceMinor))),
              normativeMinutes,
              estimateSupport.workLineComment(lineType, input.comment()),
              List.copyOf(input.mediaReferences())));
    }
    estimateSupport.validateWorkLineMediaIsolation(canonicalLines);
    List<PlanStageInput> resolvedPlanInputs =
        estimateSupport.resolvePlanContent(canonicalLines, planInputs);
    estimateSupport.validateEstimateRouting(canonicalSnapshots, resolvedPlanInputs);
    estimateSupport.validatePlanContent(canonicalLines, resolvedPlanInputs);
    estimateSupport.validateCustomRoutingStructure(canonicalLines, resolvedPlanInputs);
    resolvedPlanInputs = RepairPhaseSequence.canonicalPlan(resolvedPlanInputs);
    List<EstimatePlanStage> plan = new ArrayList<>();
    for (int index = 0; index < resolvedPlanInputs.size(); index++) {
      PlanStageInput input = resolvedPlanInputs.get(index);
      plan.add(new EstimatePlanStage(
          input.id(), estimate.getId(), revision, index, input.kind(),
          input.routing().queueId(), input.routing().queueName(), input.routing().queueType(),
          commandSupport.write(input.includedLineIds()), input.primaryLineId(), input.groupComment(),
          input.taskDeadline()));
    }
    estimateLines.saveAll(lines);
    estimatePlans.saveAll(plan);
    long totalMinor = lines.stream()
        .map(line -> line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
    EstimateRevision revisionHeader = estimateRevisions
        .findByEstimateIdAndRevision(estimate.getId(), revision)
        .orElse(null);
    if (revisionHeader == null) {
      estimateRevisions.saveAndFlush(new EstimateRevision(
          estimate.getId(), revision, estimate.getDispatchDate(), estimate.getSourceParty(),
          amendmentReason, totalMinor, estimate.isForceCapitalRepair(), commandSupport.actorJson()));
    } else {
      if (estimate.getState() != EstimateState.DRAFT) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Completed estimate revisions are immutable");
      }
      revisionHeader.replaceDraft(
          estimate.getDispatchDate(), estimate.getSourceParty(), totalMinor,
          estimate.isForceCapitalRepair(), commandSupport.actorJson());
      estimateRevisions.saveAndFlush(revisionHeader);
    }
  }

  protected void replaceRepairStages(MaintenanceRepair repair, List<PlanStageInput> inputs) {
    replaceRepairStages(repair, inputs, List.of());
  }

  protected void replaceRepairStages(
      MaintenanceRepair repair,
      List<PlanStageInput> inputs,
      List<EstimateLineResponse> lines) {
    estimateSupport.validatePlan(inputs, lines.isEmpty());
    inputs = estimateSupport.resolvePlanContent(lines, inputs);
    estimateSupport.validatePlanContent(lines, inputs);
    estimateSupport.validateCustomRoutingStructure(lines, inputs);
    inputs = RepairPhaseSequence.canonicalPlan(inputs);
    Map<UUID, EstimateLineResponse> lineById =
        lines.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    EstimateLineResponse::id, value -> value));
    repairStages.deleteAllByRepairId(repair.getId());
    repairStages.flush();
    List<RepairStage> stages = new ArrayList<>();
    for (int index = 0; index < inputs.size(); index++) {
      PlanStageInput input = inputs.get(index);
      List<EstimateLineResponse> stageLines =
          input.includedLineIds().stream().map(lineById::get).toList();
      List<EstimateLineResponse> workLines =
          stageLines.stream().filter(estimateSupport::isWorkLine).toList();
      List<EstimateLineResponse> materialLines =
          stageLines.stream().filter(line -> !estimateSupport.isWorkLine(line)).toList();
      RepairStage stage = new RepairStage(
          input.id(), repair.getId(), index, input.kind(), input.routing().queueId(),
          input.routing().queueName(), input.routing().queueType(),
          commandSupport.write(workLines), commandSupport.write(materialLines), input.primaryLineId(), input.groupComment(),
          input.taskDeadline());
      if (repair.getExecutionState() == RepairExecutionState.QUEUED) stage.queued();
      stages.add(stage);
    }
    repairStages.saveAllAndFlush(stages);
  }
}
