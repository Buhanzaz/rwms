package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles task-board and driver-task effects after their maintenance intent is committed. */
@Service
final class MaintenanceTaskReconciliationUseCases {
  private static final int DELIVERED_REPAIR_TASK_BOARD_PRIORITY = 1;

  private final MaintenanceRepairRepository repairs;
  private final MaintenanceEventStore events;
  private final MaintenanceReconciliationStore reconciliations;
  private final InventoryRepairSourceRepository inventorySources;
  private final InventoryPublicationSourceRepository publicationSources;
  private final InventoryAuthoritativeOutcomeRepository authoritativeOutcomes;
  private final RepairCapacitySettingsService repairCapacitySettings;
  private final RepairPlaceService repairPlaces;
  private final InventoryPublicationPrestartReplacementGuard prestartReplacementGuard;
  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceReconciliationSupport reconciliationSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;
  private final MaintenanceTaskBoardSupport taskBoardSupport;
  private final TransactionTemplate transactions;

  MaintenanceTaskReconciliationUseCases(
      MaintenanceRepairRepository repairs,
      MaintenanceEventStore events,
      MaintenanceReconciliationStore reconciliations,
      InventoryRepairSourceRepository inventorySources,
      InventoryPublicationSourceRepository publicationSources,
      InventoryAuthoritativeOutcomeRepository authoritativeOutcomes,
      RepairCapacitySettingsService repairCapacitySettings,
      RepairPlaceService repairPlaces,
      InventoryPublicationPrestartReplacementGuard prestartReplacementGuard,
      MaintenanceDependencyGateway dependencies,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceReconciliationSupport reconciliationSupport,
      MaintenanceRepairModelSupport repairModelSupport,
      MaintenanceTaskBoardSupport taskBoardSupport,
      PlatformTransactionManager transactionManager) {
    this.repairs = repairs;
    this.events = events;
    this.reconciliations = reconciliations;
    this.inventorySources = inventorySources;
    this.publicationSources = publicationSources;
    this.authoritativeOutcomes = authoritativeOutcomes;
    this.repairCapacitySettings = repairCapacitySettings;
    this.repairPlaces = repairPlaces;
    this.prestartReplacementGuard = prestartReplacementGuard;
    this.dependencies = dependencies;
    this.warehouseLifecycle = warehouseLifecycle;
    this.commandSupport = commandSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.reconciliationSupport = reconciliationSupport;
    this.repairModelSupport = repairModelSupport;
    this.taskBoardSupport = taskBoardSupport;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  void reconcileCatalogPositionRegistrationClaim(MaintenanceReconciliationStore.WorkItem work) {
    if (!catalogPositionReadyInClaim(work)) return;
    MaintenanceDependencyGateway.CatalogPositionReference reference =
        dependencies.registerCatalogPosition(
            work.catalogQueueId(), work.catalogExternalReferenceId());
    validateCatalogPositionReferenceTruth(work, reference);
    transactions.executeWithoutResult(
        status -> {
          taskBoardSupport.requireCatalogPositionWork(work, "REGISTER_CATALOG_POSITION");
          reconciliations.confirmed(
              work,
              Map.of(
                  "catalogVersionId", work.catalogVersionId().toString(),
                  "catalogNodeId", work.catalogNodeId().toString(),
                  "queueDefinitionId", reference.queueDefinitionId().toString(),
                  "externalReferenceId", reference.externalReferenceId(),
                  "referenceId", reference.id().toString(),
                  "referenceVersion", reference.version()));
        });
  }

  void reconcileCatalogPositionDeletionClaim(MaintenanceReconciliationStore.WorkItem work) {
    if (!catalogPositionReadyInClaim(work)) return;
    dependencies.deleteCatalogPosition(work.catalogExternalReferenceId(), 0L);
    transactions.executeWithoutResult(
        status -> {
          taskBoardSupport.requireCatalogPositionWork(work, "DELETE_CATALOG_POSITION");
          reconciliations.confirmed(
              work,
              Map.of(
                  "catalogVersionId", work.catalogVersionId().toString(),
                  "catalogNodeId", work.catalogNodeId().toString(),
                  "queueId", work.catalogQueueId().toString(),
                  "externalReferenceId", work.catalogExternalReferenceId(),
                  "deletedReferenceVersion", 0L));
        });
  }

  private static void validateCatalogPositionReferenceTruth(
      MaintenanceReconciliationStore.WorkItem work,
      MaintenanceDependencyGateway.CatalogPositionReference reference) {
    if (reference == null
        || !work.catalogQueueId().equals(reference.queueDefinitionId())
        || !work.catalogExternalReferenceId().equals(reference.externalReferenceId())
        || !"CATALOG_POSITION".equals(reference.type())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned mismatched catalog-position truth");
    }
  }

  private boolean catalogPositionReadyInClaim(MaintenanceReconciliationStore.WorkItem work) {
    Boolean ready = transactions.execute(
        status -> {
          taskBoardSupport.requireCatalogPositionWork(work, work.operation());
          List<UUID> predecessorKeys = commandSupport.uuidListField(work.payload(), "predecessorKeys");
          if (reconciliations.allCatalogPositionPredecessorsConfirmed(predecessorKeys)) {
            return true;
          }
          reconciliations.defer(work, Duration.ofSeconds(5));
          return false;
        });
    return Boolean.TRUE.equals(ready);
  }

  void reconcileDriverLogisticsTaskClaim(MaintenanceReconciliationStore.WorkItem work) {
    DriverTaskPlan plan = commandSupport.requireReconciliationResult(
        transactions.execute(status -> prepareDriverTaskPlan(work)));
    MaintenanceDependencyGateway.DriverTaskSnapshot task =
        dependencies.createDriverTask(work.idempotencyKey(), plan.command());
    validateDriverTaskTruth(plan.command(), task);
    transactions.executeWithoutResult(
        status -> {
          DriverTaskPlan current = prepareDriverTaskPlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Driver-task reconciliation changed before finalization");
          }
          reconciliations.confirmed(
              work,
              Map.of(
                  "repairId", plan.repairId().toString(),
                  "driverTaskId", task.id().toString(),
                  "driverTaskVersion", task.version(),
                  "state", task.state()));
        });
  }

  /**
   * Rehydrates either the ordinary inbound route or the dedicated external-capital outbound route
   * from one durable reconciliation intent.
   */
  private DriverTaskPlan prepareDriverTaskPlan(MaintenanceReconciliationStore.WorkItem work) {
    String kind = work.payload().path("kind").asText();
    if (!"LOGISTICS".equals(work.dependency())
        || !Set.of("DELIVER_TO_REPAIR", "CAPITAL_TO_PRODUCTION").contains(kind)) {
      throw new IllegalStateException("Stored maintenance driver-task intent is invalid");
    }
    MaintenanceRepair repair = taskBoardSupport.requireWorkRepair(work);
    if (prestartReplacementGuard.blocksRepairExecution(repair.getId())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair is blocked by a pre-start replacement");
    }
    if (!repair.isMovementToRepair()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair no longer requires the stored movement task");
    }
    boolean capitalMovement = "CAPITAL_TO_PRODUCTION".equals(kind);
    if (capitalMovement) {
      if (repair.getExecutionState() != RepairExecutionState.COMPLETED
          || repair.getAcceptanceState() != RepairAcceptanceState.PENDING
          || repair.getReclassificationState() != RepairReclassificationState.EXTERNAL_CAPITAL) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT",
            "Only pending external capital repair can create a production movement");
      }
    } else if (repair.getExecutionState() != RepairExecutionState.QUEUED
        || repair.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only queued ordinary repair work can create a delivery task");
    }
    UUID inventoryFindingId =
        capitalMovement
            ? null
            : authoritativeOutcomes
                .findByTargetRepairId(repair.getId())
                .map(value -> value.getId().getFindingId())
                .or(
                    () ->
                        publicationSources
                            .findByRepairId(repair.getId())
                            .map(value -> value.getId().getFindingId()))
                .or(
                    () ->
                        inventorySources
                            .findByRepairId(repair.getId())
                            .map(value -> value.getFindingId()))
                .orElse(null);
    String sourceType;
    UUID sourceId;
    if (capitalMovement) {
      sourceType = "CAPITAL_REPAIR";
      sourceId = repair.getId();
    } else if (inventoryFindingId != null) {
      sourceType = "INVENTORY";
      sourceId = inventoryFindingId;
    } else if (repair.getEstimateId() != null) {
      sourceType = "ESTIMATE";
      sourceId = repair.getEstimateId();
    } else {
      sourceType = "REPAIR";
      sourceId = repair.getId();
    }
    return new DriverTaskPlan(
        repair.getId(),
        new MaintenanceDependencyGateway.DriverTaskCommand(
            repair.getWarehouseId(),
            repair.getRentalItemId(),
            repair.getId(),
            sourceType,
            sourceId,
            kind,
            repair.getLogisticsPlanningMode(),
            repair.getLogisticsScheduledDate(),
            repair.getPriority(),
            false));
  }

  private static void validateDriverTaskTruth(
      MaintenanceDependencyGateway.DriverTaskCommand command,
      MaintenanceDependencyGateway.DriverTaskSnapshot task) {
    if (task == null
        || task.id() == null
        || task.version() < 0
        || !command.warehouseId().equals(task.warehouseId())
        || !command.cabinId().equals(task.cabinId())
        || !command.repairId().equals(task.repairId())
        || !command.sourceType().equals(task.sourceType())
        || !command.sourceId().equals(task.sourceId())
        || !command.kind().equals(task.kind())
        || command.planningMode() != task.planningMode()
        || (command.planningMode() == RepairLogisticsPlanningMode.FIXED_DATE
            && !command.scheduledDate().equals(task.scheduledDate()))
        || command.priority() != task.priority()
        || !Set.of("REGISTERING", "SCHEDULED", "CURRENT", "FINALIZING", "COMPLETED")
            .contains(task.state())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Logistics-service returned mismatched driver-task truth");
    }
  }

  void reconcileTaskClaim(MaintenanceReconciliationStore.WorkItem work, boolean update) {
    Optional<TaskPlan> prepared = transactions.execute(status -> prepareTaskPlan(work, update));
    if (prepared == null || prepared.isEmpty()) return;
    TaskPlan plan = prepared.orElseThrow();

    // Both the canonical cabin read and the task-board mutation are deliberately outside any
    // maintenance transaction. The task command retains its stable reconciliation key.
    MaintenanceDependencyGateway.AssetSnapshot asset =
        dependencies.getRentalItemSnapshot(plan.rentalItemId());
    validateTaskAssetTruth(plan, asset);
    LocalDate scheduledDate = plan.resolveDeliveredLocalDate()
        ? warehouseLifecycle.localDateAt(
            plan.warehouseId(), OffsetDateTime.now(java.time.ZoneOffset.UTC))
        : plan.scheduledDate();
    int priority = plan.resolveDeliveredLocalDate()
        ? DELIVERED_REPAIR_TASK_BOARD_PRIORITY
        : plan.priority();
    MaintenanceDependencyGateway.TaskSnapshot task = update
        ? dependencies.updatePreStartTask(
            work.idempotencyKey(),
            plan.externalTaskId(),
            plan.expectedTaskVersion(),
            asset.number(),
            plan.stages())
        : dependencies.registerTask(
            work.idempotencyKey(),
            plan.externalTaskId(),
            plan.repairId(),
            plan.warehouseId(),
            plan.rentalItemId(),
            asset.number(),
            scheduledDate,
            priority,
            plan.dailyCapacity(),
            plan.stages());
    validateTaskPlanTruth(plan, task);

    transactions.executeWithoutResult(
        status -> {
          Optional<TaskPlan> current = prepareTaskPlan(work, update);
          if (current == null || current.isEmpty()) return;
          if (!plan.equals(current.orElseThrow())) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT", "Task reconciliation changed before finalization");
          }
          MaintenanceRepair repair = repairModelSupport.requireRepair(plan.repairId());
          long expectedVersion =
              events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
          commandSupport.assertVersion(repair.getVersion(), expectedVersion);
          MaintenanceReconciliationSupport.validateTaskTruth(repair, task, plan.stages().size());
          taskBoardSupport.confirmTaskRegistration(repair.getId(), task);
          repair.markTaskGenerated(task.version());
          repair.markReconciled();
          MaintenanceRepair saved = repairs.saveAndFlush(repair);
          MaintenanceEventType eventType =
              update ? MaintenanceEventType.REPAIR_PLAN_CHANGED : MaintenanceEventType.REPAIR_QUEUED;
          events.append(
              MaintenanceAggregateType.REPAIR,
              saved.getId(),
              expectedVersion,
              eventType,
              eventPayloadSupport.repairLocal(saved),
              eventPayloadSupport.repairFact(eventType, saved),
              eventPayloadSupport.repairSnapshot(saved));
          reconciliations.confirmed(
              work,
              Map.of(
                  "repairId", saved.getId().toString(),
                  "externalTaskId", task.externalTaskId().toString(),
                  "taskBoardVersion", task.version()));
        });
  }

  private Optional<TaskPlan> prepareTaskPlan(
      MaintenanceReconciliationStore.WorkItem work, boolean update) {
    MaintenanceRepair repair = taskBoardSupport.requireWorkRepair(work);
    if (prestartReplacementGuard.blocksRepairExecution(repair.getId())) {
      reconciliations.defer(work, Duration.ofSeconds(2));
      return Optional.empty();
    }
    if (repair.getExecutionState() != RepairExecutionState.QUEUED) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Only a queued repair can reconcile its task");
    }
    if (update && repair.getTaskBoardVersion() == null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Task update has no confirmed task-board version");
    }
    LocalDate scheduledDate = repair.getDispatchDate();
    int priority = repair.getPriority();
    boolean resolveDeliveredLocalDate = !update
        && taskBoardSupport.requiresDriverDeliveryToRepair(repair)
        && repairPlaces.isOccupied(repair.getWarehouseId(), repair.getId());
    return Optional.of(
        new TaskPlan(
            repair.getId(),
            repair.getWarehouseId(),
            repair.getRentalItemId(),
            repair.getExternalTaskId(),
            update,
            update ? repair.getTaskBoardVersion() : -1L,
            scheduledDate,
            priority,
            resolveDeliveredLocalDate,
            repairCapacitySettings.get(repair.getWarehouseId()).repairPlaceCount(),
            List.copyOf(taskBoardSupport.taskStages(repair))));
  }

  private static void validateTaskAssetTruth(
      TaskPlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.number() == null
        || asset.number().isBlank()
        || asset.number().length() > 64) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Canonical rental-item snapshot is not safe for task synchronization");
    }
  }

  private static void validateTaskPlanTruth(
      TaskPlan plan, MaintenanceDependencyGateway.TaskSnapshot task) {
    if (task == null
        || !plan.externalTaskId().equals(task.externalTaskId())
        || task.version() < 0
        || !"ACTIVE".equals(task.state())
        || task.stages() == null
        || task.stages().size() != plan.stages().size()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board truth does not match the maintenance repair");
    }
  }
}
