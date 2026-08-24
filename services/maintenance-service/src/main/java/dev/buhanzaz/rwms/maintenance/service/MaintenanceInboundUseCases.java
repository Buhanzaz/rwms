package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Applies maintenance-owned inbound media, asset, lease and task facts inside the caller's required transaction without acquiring remote dependencies. */
@Service
public class MaintenanceInboundUseCases {
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceInboundFactProjectionUseCases factProjections;
  private final MaintenanceEventStore events;
  private final MaintenanceReconciliationStore reconciliations;
  private final RepairPlaceService repairPlaces;
  private final InventoryPublicationSuccessorActivator inventorySuccessors;
  private final InventoryPublicationPrestartReplacementGuard prestartReplacementGuard;
  private final MaintenanceDependencyGateway dependencies;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceMediaSupport mediaSupport;
  private final MaintenanceReconciliationSupport reconciliationSupport;
  private final MaintenanceRepairLifecycleSupport repairLifecycleSupport;
  private final MaintenanceTaskBoardSupport taskBoardSupport;

  public MaintenanceInboundUseCases(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceInboundFactProjectionUseCases factProjections,
      MaintenanceEventStore events,
      MaintenanceReconciliationStore reconciliations,
      RepairPlaceService repairPlaces,
      InventoryPublicationSuccessorActivator inventorySuccessors,
      InventoryPublicationPrestartReplacementGuard prestartReplacementGuard,
      MaintenanceDependencyGateway dependencies,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceMediaSupport mediaSupport,
      MaintenanceReconciliationSupport reconciliationSupport,
      MaintenanceRepairLifecycleSupport repairLifecycleSupport,
      MaintenanceTaskBoardSupport taskBoardSupport) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.factProjections = factProjections;
    this.events = events;
    this.reconciliations = reconciliations;
    this.repairPlaces = repairPlaces;
    this.inventorySuccessors = inventorySuccessors;
    this.prestartReplacementGuard = prestartReplacementGuard;
    this.dependencies = dependencies;
    this.commandSupport = commandSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.mediaSupport = mediaSupport;
    this.reconciliationSupport = reconciliationSupport;
    this.repairLifecycleSupport = repairLifecycleSupport;
    this.taskBoardSupport = taskBoardSupport;
  }

  public void applyInboundMediaFact(
      UUID mediaId,
      long generation,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      String status,
      String safeMetadata,
      long aggregateVersion) {
    factProjections.applyInboundMediaFact(
        mediaId, generation, ownerType, ownerId, warehouseId, status, safeMetadata, aggregateVersion);
  }

  public void applyInboundRentalItemFact(
      UUID rentalItemId,
      UUID warehouseId,
      String status,
      long aggregateVersion) {
    factProjections.applyInboundRentalItemFact(rentalItemId, warehouseId, status, aggregateVersion);
  }

  public void applyInboundLeaseFact(
      UUID leaseId,
      UUID rentalItemId,
      long fencingToken,
      String state,
      long aggregateVersion) {
    factProjections.applyInboundLeaseFact(
        leaseId, rentalItemId, fencingToken, state, aggregateVersion);
  }

  public void applyInboundTaskOutcome(
      UUID eventId,
      String eventType,
      UUID externalTaskId,
      UUID queueEntryId,
      long queueEntryVersion,
      OffsetDateTime occurredAt) {
    MaintenanceRepair initial = repairs.findByExternalTaskId(externalTaskId).orElseThrow(() ->
        new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Task-board fact has no maintenance repair owner"));
    if (initial.getHistoricalShipmentDocumentId() != null) {
      // Historical shipment closure owns every in-flight task fact after it has recorded the
      // durable audit marker. A delayed task-board event must not reopen or overwrite that result.
      return;
    }
    if (eventType.endsWith("cancelled.v1")
        && prestartReplacementGuard.ownsTaskCancellation(initial.getId(), externalTaskId)) {
      // cancel-if-pre-start can publish before the coordinator has persisted compensation or
      // finalized the replacement. Keep the event as corroborating external audit truth; V31
      // will atomically mirror its cancellation together with the successor/source outcome.
      return;
    }
    LockedTaskOutcome locked = lockTaskOutcome(initial);
    MaintenanceRepair repair = locked.repair();
    long expectedVersion = locked.streamVersions().get(repairLifecycleSupport.stream(repair.getId()));
    RepairStage stage = repairStages.findByExternalQueueEntryIdForUpdate(queueEntryId)
        .orElseThrow(() -> new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Task-board route entry has no repair-stage mapping"));
    if (!repair.getId().equals(stage.getRepairId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Task-board route entry belongs to another repair");
    }
    MaintenanceEventType maintenanceEvent;
    if (eventType.endsWith("completed.v1")) {
      if (stage.getState() == RepairStageState.DONE) return;
      stage.completed(eventId, queueEntryVersion, occurredAt);
      repairStages.saveAndFlush(stage);
      boolean allDone = repairStages.countByRepairIdAndStateNotIn(
          repair.getId(), List.of(RepairStageState.DONE)) == 0;
      repair.applyStageCompletion(allDone);
      maintenanceEvent = allDone
          ? MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE
          : MaintenanceEventType.REPAIR_STAGE_COMPLETED;
      if (allDone) {
        repairPlaces.completeAfterRepair(
            repair.getWarehouseId(), repair.getId(), repair.isMovementToRepair());
        if (repair.getKind() == RepairKind.REWORK) {
          returnSourceFromRework(locked);
        } else {
          repair.markLeaseReconciliationRequired();
          taskBoardSupport.enqueueTerminalAsset(
              repair,
              "PENDING_ACCEPTANCE",
              commandSupport.stableOperationKey("pending-acceptance", repair.getId(), expectedVersion + 1));
        }
      }
    } else if (eventType.endsWith("cancelled.v1")) {
      if (!stage.cancelled(eventId, queueEntryVersion)) return;
      repairStages.saveAndFlush(stage);
      repair.applyExternalTaskCancellation();
      if (repair.getKind() == RepairKind.REWORK) {
        repair.markReconciled();
        returnSourceFromRework(locked);
      } else if (repair.isQueuedWithoutOperationLease()) {
        repair.markReconciled();
      } else {
        repairLifecycleSupport.requireRenewableLease(repair);
        repair.markReconciliationRequired();
        reconciliations.enqueueRequired(
            repair.getId(),
            "ASSET",
            "CANCELLED_PRIMARY_RECONCILIATION",
            commandSupport.stableOperationKey(
                "cancelled-primary-reconciliation", repair.getId(), expectedVersion + 1),
            Map.of(
                "repairId", repair.getId().toString(),
                "leaseId", repair.getLeaseId().toString(),
                "reason", "NO_APPROVED_REVERSE_STATUS"));
      }
      maintenanceEvent = MaintenanceEventType.REPAIR_PLAN_CHANGED;
    } else {
      throw new IllegalArgumentException("Unsupported actionable task event " + eventType);
    }
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        maintenanceEvent,
        eventPayloadSupport.repairLocal(saved),
        eventPayloadSupport.repairFact(maintenanceEvent, saved),
        eventPayloadSupport.repairSnapshot(saved));
    if (maintenanceEvent == MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE) {
      reconciliations.enqueue(
          saved.getId(),
          "ASSET",
          "MATERIALIZE_FURNITURE_CUSTODY",
          commandSupport.stableOperationKey("materialize-furniture-custody", saved.getId(), 0),
          Map.of("repairId", saved.getId().toString()));
      inventorySuccessors.releaseAfterTaskBoardCompletion(saved, eventId, occurredAt);
      mediaSupport.enqueueMediaOwnerProof(
          "MAINTENANCE_ACCEPTANCE",
          saved.getId(),
          saved.getWarehouseId(),
          saved.getId(),
          saved.getVersion(),
          true);
    }
  }

  public void applyInboundTaskSchedule(
      UUID externalTaskId, LocalDate scheduledDate, long taskBoardVersion) {
    MaintenanceRepair initial = repairs.findByExternalTaskId(externalTaskId).orElseThrow(() ->
        new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Task-board fact has no maintenance repair owner"));
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(
        List.of(repairLifecycleSupport.stream(initial.getId())));
    long expectedVersion = locked.get(repairLifecycleSupport.stream(initial.getId()));
    commandSupport.assertVersion(expectedVersion, initial.getVersion());
    MaintenanceRepair repair = repairs.findAllByIdForUpdate(List.of(initial.getId())).stream()
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    repairLifecycleSupport.assertStreamParity(repair, locked);
    if (!repair.synchronizeTaskBoardSchedule(scheduledDate, taskBoardVersion)) return;
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        eventPayloadSupport.repairLocal(saved),
        eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
        eventPayloadSupport.repairSnapshot(saved));
  }

  private LockedTaskOutcome lockTaskOutcome(MaintenanceRepair initial) {
    if (initial.getKind() == RepairKind.REWORK) {
      LockedRework locked = repairLifecycleSupport.lockAndReloadRework(initial, initial.getVersion());
      return new LockedTaskOutcome(
          locked.rework(), locked.source(), locked.streamVersions());
    }
    Map<MaintenanceEventStore.StreamRef, Long> versions = events.lockStreams(
        List.of(repairLifecycleSupport.stream(initial.getId())));
    long expectedVersion = versions.get(repairLifecycleSupport.stream(initial.getId()));
    commandSupport.assertVersion(expectedVersion, initial.getVersion());
    MaintenanceRepair repair = repairs.findAllByIdForUpdate(List.of(initial.getId())).stream()
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    repairLifecycleSupport.assertStreamParity(repair, versions);
    return new LockedTaskOutcome(repair, null, versions);
  }

  private void returnSourceFromRework(LockedTaskOutcome locked) {
    MaintenanceRepair source = Optional.ofNullable(locked.source()).orElseThrow(() ->
        new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Rework task outcome is missing its source repair"));
    source.returnFromRework();
    MaintenanceRepair saved = repairs.saveAndFlush(source);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        locked.streamVersions().get(repairLifecycleSupport.stream(saved.getId())),
        MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE,
        eventPayloadSupport.repairLocal(saved),
        eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_PENDING_ACCEPTANCE, saved),
        eventPayloadSupport.repairSnapshot(saved));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_ACCEPTANCE",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
  }

}
