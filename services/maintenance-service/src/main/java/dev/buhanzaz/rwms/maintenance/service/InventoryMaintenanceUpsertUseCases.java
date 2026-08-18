package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSource;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes one frozen inventory source into a repair and its outbox/reconciliation effects while
 * preserving immutable source identity, idempotency and remote-preflight retry fences.
 */
@Component
final class InventoryMaintenanceUpsertUseCases {
  private static final Set<String> REPAIR_QUEUE_SOURCES = Set.of(
      "FREE", "WAREHOUSE", "OWN_NEEDS", "AFTER_RENT");
  private static final UUID INVENTORY_ACTOR = UUID.nameUUIDFromBytes(
      "inventory-service".getBytes(StandardCharsets.UTF_8));

  private final InventoryRepairSourceRepository sources;
  private final RentalItemFactProjectionRepository rentalItems;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final InventoryRepairReconciliationWriter reconciliations;
  private final MaintenanceReconciliationStore ownerProofs;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceJsonbCanonicalizer canonicalizer;
  private final ObjectMapper mapper;
  private final InventoryMaintenancePlanValidation planValidation;
  private final InventoryMaintenanceTransactionBoundary transactions;

  InventoryMaintenanceUpsertUseCases(
      InventoryRepairSourceRepository sources,
      RentalItemFactProjectionRepository rentalItems,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      InventoryRepairReconciliationWriter reconciliations,
      MaintenanceReconciliationStore ownerProofs,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceJsonbCanonicalizer canonicalizer,
      ObjectMapper mapper,
      InventoryMaintenancePlanValidation planValidation,
      InventoryMaintenanceTransactionBoundary transactions) {
    this.sources = sources;
    this.rentalItems = rentalItems;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.reconciliations = reconciliations;
    this.ownerProofs = ownerProofs;
    this.warehouseLifecycle = warehouseLifecycle;
    this.canonicalizer = canonicalizer;
    this.mapper = mapper;
    this.planValidation = planValidation;
    this.transactions = transactions;
  }

  InventoryMaintenanceUpsertResult upsert(
      UUID inventoryId, UUID findingId, UpsertInventoryRepairRequest request) {
    transactions.requireNoCallerTransaction("upsert an inventory repair");
    UUID incomingAdmissionWarehouseId = null;
    List<InventoryPlanStageSnapshot> routingPreflightStages = null;
    while (true) {
      UUID admittedWarehouseId = incomingAdmissionWarehouseId;
      List<InventoryPlanStageSnapshot> preflightedStages = routingPreflightStages;
      try {
        return transactions.inNewTransaction(
            () -> upsertLocally(
                inventoryId, findingId, request, admittedWarehouseId, preflightedStages));
      } catch (InventoryMaintenancePlanValidation.RemotePreflightRequired requirement) {
        switch (requirement.kind()) {
          case INCOMING -> {
            if (requirement.warehouseId().equals(incomingAdmissionWarehouseId)) {
              throw new IllegalStateException("Inventory upsert repeated warehouse admission");
            }
            warehouseLifecycle.requireIncoming(requirement.warehouseId());
            incomingAdmissionWarehouseId = requirement.warehouseId();
          }
          case ROUTING -> {
            if (requirement.stages().equals(routingPreflightStages)) {
              throw new IllegalStateException("Inventory upsert repeated routing preflight");
            }
            planValidation.requireWarehouseRoutingReady(
                requirement.warehouseId(), requirement.stages());
            routingPreflightStages = requirement.stages();
          }
        }
      }
    }
  }

  private InventoryMaintenanceUpsertResult upsertLocally(
      UUID inventoryId,
      UUID findingId,
      UpsertInventoryRepairRequest request,
      UUID incomingAdmissionWarehouseId,
      List<InventoryPlanStageSnapshot> routingPreflightStages) {
    InventoryRepairSource source =
        sources
            .findBySourceRevisionForUpdate(inventoryId, findingId, request.sourceRevision())
        .orElseThrow(() -> new MaintenanceNotFoundException("Frozen inventory plan not found"));
    requireHistoricalSource(source, request);
    String sourceFingerprint = sourceFingerprint(inventoryId, findingId, request);
    if (source.getRepairId() != null) {
      if (!sourceFingerprint.equals(source.getSourceFingerprint())
          || !request.rentalItemId().equals(source.getRentalItemId())
          || !request.rentalItemVersion().equals(source.getRentalItemVersionSnapshot())) {
        throw conflict("Inventory source is already bound to different immutable repair input");
      }
      MaintenanceRepair existing = repairs.findById(source.getRepairId())
          .orElseThrow(() -> conflict("Inventory source points to a missing repair"));
      return result(existing, source, true);
    }

    if (!request.warehouseId().equals(incomingAdmissionWarehouseId)) {
      throw InventoryMaintenancePlanValidation.RemotePreflightRequired.incoming(request.warehouseId());
    }

    RentalItemFactProjection rentalItem = rentalItems.findById(request.rentalItemId())
        .orElseThrow(() -> new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Current rental-item fact is unavailable"));
    if (!request.warehouseId().equals(rentalItem.getWarehouseId())
        || request.rentalItemVersion() != rentalItem.getAggregateVersion()) {
      throw conflict("Current rental-item warehouse/version differs from the frozen source");
    }
    if (!REPAIR_QUEUE_SOURCES.contains(rentalItem.getAssetStatus())) {
      throw conflict("Current rental-item status is unsafe for maintenance queueing");
    }
    planValidation.validateSnapshotMedia(findingId, request.warehouseId(), request.snapshot());
    if (!request.snapshot().stages().equals(routingPreflightStages)) {
      throw InventoryMaintenancePlanValidation.RemotePreflightRequired.routing(
          request.warehouseId(), request.snapshot().stages());
    }

    MaintenanceRepair draft = MaintenanceRepair.primary(
        request.warehouseId(), request.rentalItemId(), request.rentalItemVersion(), null,
        RepairOrigin.INVENTORY, request.dispatchDate(), "Инвентаризация", inventoryActorJson());
    draft.selectForceCapitalRepair(request.snapshot().forceCapitalRepair());
    draft.selectPriority(request.snapshot().priority());
    draft.selectMovementToRepair(
        request.snapshot().movementToRepair(),
        request.snapshot().logisticsPlanningMode(),
        request.snapshot().logisticsScheduledDate());
    draft.replaceCoverMediaId(request.snapshot().coverMediaId());
    MaintenanceRepair repair = repairs.saveAndFlush(draft);
    List<RepairStage> stages = planValidation.inventoryRepairStages(
        repair.getId(), inventoryId, findingId, request.snapshot());
    repairStages.saveAllAndFlush(stages);
    source.bindRepair(
        repair.getId(), request.rentalItemId(), request.rentalItemVersion(), sourceFingerprint);
    InventoryRepairSource bound = sources.saveAndFlush(source);

    Map<String, Object> snapshot = projectionSnapshots.repair(repair);
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        snapshot,
        eventFacts.repairPayload(MaintenanceEventType.REPAIR_CREATED, repair, stages),
        snapshot);
    ownerProofs.enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        repair.getId(),
        repair.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        repair.getWarehouseId(), repair.getId(), repair.getCreatedAt());
    reconciliations.enqueue(
        repair.getId(), stableKey("inventory-queue-repair", inventoryId, findingId));
    return result(repair, bound, false);
  }

  private void requireHistoricalSource(
      InventoryRepairSource source, UpsertInventoryRepairRequest request) {
    if (!request.warehouseId().equals(source.getWarehouseId())
        || request.sourceRevision() != source.getSourceRevision()
        || !request.planFingerprint().equals(source.getPlanFingerprint())) {
      throw conflict("Inventory publication does not match its frozen source identity");
    }
    ObjectNode requestedSnapshot = mapper.valueToTree(request.snapshot());
    String exactSnapshotFingerprint = canonicalizer.sha256(requestedSnapshot);
    if (!exactSnapshotFingerprint.equals(source.getPlanFingerprint())
        || !sameJson(write(requestedSnapshot), source.getPlanSnapshot())) {
      throw conflict("Inventory publication changed the historical maintenance plan snapshot");
    }
  }

  private InventoryMaintenanceUpsertResult result(
      MaintenanceRepair repair, InventoryRepairSource source, boolean replayed) {
    InventorySourceReference reference = new InventorySourceReference(
        source.getInventoryId(), source.getFindingId(), source.getSourceRevision(),
        source.getPlanFingerprint(), source.getSourceFingerprint());
    DeliverySnapshot delivery = new DeliverySnapshot(
        DeliveryState.valueOf(repair.getDeliveryState()), repair.getDeliveryAttempts(),
        repair.getDeliveryUpdatedAt());
    return new InventoryMaintenanceUpsertResult(repair.getId(), reference, delivery, replayed);
  }

  private String sourceFingerprint(
      UUID inventoryId, UUID findingId, UpsertInventoryRepairRequest request) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("inventoryId", inventoryId);
    value.put("findingId", findingId);
    value.put("sourceRevision", request.sourceRevision());
    value.put("warehouseId", request.warehouseId());
    value.put("rentalItemId", request.rentalItemId());
    value.put("rentalItemVersion", request.rentalItemVersion());
    value.put("dispatchDate", request.dispatchDate());
    value.put("planFingerprint", request.planFingerprint());
    value.put("snapshot", request.snapshot());
    return canonicalizer.sha256(value);
  }

  private String inventoryActorJson() {
    return write(Map.of(
        "subjectId", INVENTORY_ACTOR.toString(),
        "principalType", "SERVICE"));
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory maintenance value cannot be serialized", exception);
    }
  }

  private boolean sameJson(String first, String second) {
    try {
      return mapper.readTree(first).equals(mapper.readTree(second));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory maintenance snapshot is invalid", exception);
    }
  }

  private static UUID stableKey(String operation, UUID inventoryId, UUID findingId) {
    return UUID.nameUUIDFromBytes(
        (operation + ":" + inventoryId + ":" + findingId).getBytes(StandardCharsets.UTF_8));
  }

  private static MaintenanceConflictException conflict(String detail) {
    return new MaintenanceConflictException("MAINTENANCE_IDEMPOTENCY_CONFLICT", detail);
  }
}

/** Internal immutable result adapted to the facade's public upsert response. */
record InventoryMaintenanceUpsertResult(
    UUID repairId,
    InventorySourceReference source,
    DeliverySnapshot delivery,
    boolean replayed) {}
