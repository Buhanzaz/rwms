package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.MediaReferenceInput;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPlanStageSnapshot;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationFindingInput;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Materializes inventory repair targets, their frozen stage snapshots, durable queue work, and
 * media-owner evidence without deciding whether a predecessor may be superseded.
 */
@Component
final class InventoryPublicationRepairMaterialization {
  private static final UUID INVENTORY_ACTOR =
      UUID.nameUUIDFromBytes("inventory-service".getBytes(StandardCharsets.UTF_8));

  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceMediaReferenceRepository mediaReferences;
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceReconciliationStore reconciliations;
  private final InventoryRepairReconciliationWriter repairQueue;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final ObjectMapper mapper;

  InventoryPublicationRepairMaterialization(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceMediaReferenceRepository mediaReferences,
      MediaFactProjectionRepository mediaFacts,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceReconciliationStore reconciliations,
      InventoryRepairReconciliationWriter repairQueue,
      WarehouseLifecycleOperations warehouseLifecycle,
      ObjectMapper mapper) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.mediaReferences = mediaReferences;
    this.mediaFacts = mediaFacts;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.reconciliations = reconciliations;
    this.repairQueue = repairQueue;
    this.warehouseLifecycle = warehouseLifecycle;
    this.mapper = mapper;
  }

  InventoryPublicationCreatedTarget createRepair(
      InventoryPublicationSourceId sourceId,
      UUID warehouseId,
      InventoryPublicationFindingInput finding,
      InventoryPublicationRepairPlan plan,
      boolean enqueueImmediately,
      UUID incomingAdmissionWarehouseId,
      List<InventoryPlanStageSnapshot> routingPreflightStages,
      UUID queueKey) {
    if (!warehouseId.equals(incomingAdmissionWarehouseId)) {
      throw InventoryPublicationPlanValidation.RemotePreflightRequired.incoming(warehouseId);
    }
    List<InventoryPlanStageSnapshot> requiredRoutingStages = plan.allocations().stream()
        .filter(allocation -> !allocation.lines().isEmpty())
        .map(InventoryPublicationPublishedStage::stage)
        .toList();
    if (!requiredRoutingStages.equals(routingPreflightStages)) {
      throw InventoryPublicationPlanValidation.RemotePreflightRequired.routing(
          warehouseId, requiredRoutingStages);
    }
    RepairLogisticsPlanningMode planningMode = finding.movementToRepair()
        ? finding.movementScheduledDate() == null
            ? RepairLogisticsPlanningMode.AUTO
            : RepairLogisticsPlanningMode.FIXED_DATE
        : null;
    MaintenanceRepair draft = MaintenanceRepair.primary(
        warehouseId,
        finding.assetId(),
        finding.assetVersion(),
        null,
        RepairOrigin.INVENTORY,
        finding.repairScheduledDate(),
        "Инвентаризация",
        inventoryActorJson());
    draft.selectForceCapitalRepair(finding.forceCapitalRepair());
    draft.selectPriority(finding.priority());
    draft.selectMovementToRepair(
        finding.movementToRepair(), planningMode, finding.movementScheduledDate());
    draft.replaceCoverMediaId(plan.coverMediaId());
    MaintenanceRepair repair = repairs.saveAndFlush(draft);
    warehouseLifecycle.recordOperation(repair.getWarehouseId(), repair.getId(), repair.getCreatedAt());
    List<RepairStage> stages = plan.allocations().stream()
        .filter(allocation -> !allocation.lines().isEmpty())
        .map(
            allocation ->
                new RepairStage(
                    allocation.stage().id(),
                    repair.getId(),
                    allocation.stage().order(),
                    allocation.stage().kind(),
                    allocation.stage().routing().queueId(),
                    allocation.stage().routing().queueName(),
                    allocation.stage().routing().queueType(),
                    write(allocation.workLines()),
                    write(allocation.materialLines()),
                    allocation.primaryLineId(),
                    "",
                    null))
        .toList();
    repairStages.saveAllAndFlush(stages);
    attachMedia(
        "REPAIR", "MAINTENANCE_REPAIR", repair.getId(), warehouseId, plan.sourceMedia());
    Map<String, Object> state = projectionSnapshots.repair(repair);
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        state,
        eventFacts.repairPayload(MaintenanceEventType.REPAIR_CREATED, repair, stages),
        state);
    reconciliations.enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        warehouseId,
        repair.getId(),
        repair.getVersion(),
        true);
    if (enqueueImmediately) {
      repairQueue.enqueue(repair.getId(), queueKey);
    }
    return new InventoryPublicationCreatedTarget(
        dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationTargetKind.REPAIR,
        repair.getId(),
        null,
        repair.getId());
  }

  void enqueue(UUID repairId, UUID queueKey) {
    repairQueue.enqueue(repairId, queueKey);
  }

  private void attachMedia(
      String aggregateType,
      String ownerType,
      UUID aggregateId,
      UUID warehouseId,
      List<MediaReferenceInput> references) {
    if (references.isEmpty()) return;
    List<MaintenanceMediaReference> values = references.stream()
        .map(
            reference ->
                new MaintenanceMediaReference(
                    aggregateType,
                    aggregateId,
                    reference.mediaId(),
                    reference.generation(),
                    ownerType,
                    warehouseId,
                    mediaFacts.findById(reference.mediaId())
                        .map(MediaFactProjection::getSafeMetadata)
                        .orElse("{}")))
        .toList();
    mediaReferences.saveAll(values);
  }

  private String inventoryActorJson() {
    return write(Map.of("subjectId", INVENTORY_ACTOR.toString(), "principalType", "SERVICE"));
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory publication value cannot be serialized", exception);
    }
  }
}
