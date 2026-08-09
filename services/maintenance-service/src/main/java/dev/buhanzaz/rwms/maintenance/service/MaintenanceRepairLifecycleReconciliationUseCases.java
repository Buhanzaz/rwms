package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles queued-repair, lease, asset-status and complexity lifecycle effects. */
@Service
final class MaintenanceRepairLifecycleReconciliationUseCases {
  private static final Set<String> ESTIMATE_REPAIR_QUEUE_SOURCE_STATUSES = Set.of(
      "FREE",
      "WAREHOUSE",
      "OWN_NEEDS",
      "AFTER_RENT",
      "WAITING_ESTIMATE_CONFIRMATION",
      "REPAIR",
      "CAPITAL_REPAIR",
      "USED_SALE");
  private static final Set<String> DIRECT_REPAIR_QUEUE_SOURCE_STATUSES = Set.of(
      "BOOKED",
      "REPAIR",
      "WAITING_REPAIR_CHECK",
      "WRITTEN_OFF",
      "CAPITAL_REPAIR",
      "WAITING_ESTIMATE_CONFIRMATION",
      "SALE",
      "USED_SALE",
      "RESERVED",
      "FREE",
      "WAREHOUSE",
      "OWN_NEEDS",
      "IN_TRANSFER");

  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceReconciliationStore reconciliations;
  private final RepairPlaceService repairPlaces;
  private final InventoryPublicationPrestartReplacementGuard prestartReplacementGuard;
  private final MaintenanceDependencyGateway dependencies;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateSupport estimateSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceReconciliationSupport reconciliationSupport;
  private final MaintenanceRepairLifecycleSupport repairLifecycleSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;
  private final MaintenanceTaskBoardSupport taskBoardSupport;
  private final TransactionTemplate transactions;

  MaintenanceRepairLifecycleReconciliationUseCases(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceReconciliationStore reconciliations,
      RepairPlaceService repairPlaces,
      InventoryPublicationPrestartReplacementGuard prestartReplacementGuard,
      MaintenanceDependencyGateway dependencies,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateSupport estimateSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceReconciliationSupport reconciliationSupport,
      MaintenanceRepairLifecycleSupport repairLifecycleSupport,
      MaintenanceRepairModelSupport repairModelSupport,
      MaintenanceTaskBoardSupport taskBoardSupport,
      PlatformTransactionManager transactionManager) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.reconciliations = reconciliations;
    this.repairPlaces = repairPlaces;
    this.prestartReplacementGuard = prestartReplacementGuard;
    this.dependencies = dependencies;
    this.commandSupport = commandSupport;
    this.estimateSupport = estimateSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.reconciliationSupport = reconciliationSupport;
    this.repairLifecycleSupport = repairLifecycleSupport;
    this.repairModelSupport = repairModelSupport;
    this.taskBoardSupport = taskBoardSupport;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  void reconcileQueuedRepairClaim(MaintenanceReconciliationStore.WorkItem work) {
    Optional<QueueRepairPlan> prepared = transactions.execute(status -> prepareQueueRepairPlan(work));
    if (prepared == null || prepared.isEmpty()) return;
    QueueRepairPlan plan = prepared.orElseThrow();
    if (plan.alreadyQueued()) {
      transactions.executeWithoutResult(
          status -> {
            Optional<QueueRepairPlan> current = prepareQueueRepairPlan(work);
            if (current == null || current.isEmpty() || !plan.equals(current.orElseThrow())) {
              throw new MaintenanceConflictException(
                  "MAINTENANCE_STATE_CONFLICT",
                  "Repair queue reconciliation changed before finalization");
            }
            reconciliations.confirmed(
                work, Map.of("repairId", plan.repairId().toString(), "alreadyQueued", true));
          });
      return;
    }

    // All asset reads and mutations take place only after the prepare transaction has committed.
    MaintenanceDependencyGateway.AssetSnapshot liveAsset =
        dependencies.getRentalItemSnapshot(plan.rentalItemId());
    validateQueueAssetTruth(plan, liveAsset);
    List<MaintenanceDependencyGateway.FurniturePendingReturn> furniture;
    if (plan.requestedFurniture().isEmpty()) {
      furniture = List.of();
    } else {
      MaintenanceDependencyGateway.PropertyAssetSnapshot cabin =
          dependencies.getPropertyAssetSnapshot(
              MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
              plan.rentalItemId(),
              plan.warehouseId());
      furniture =
          plan.furnitureAccountingMode() == FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS
              ? unaccountedFurnitureReturnsFromEmptyCabin(plan, cabin)
              : pendingReturnsFromCabinSnapshot(plan, cabin);
    }

    QueueRepairRemoteResult remote;
    if (("REPAIR".equals(liveAsset.status()) || "CAPITAL_REPAIR".equals(liveAsset.status()))
        && plan.existingLifecycleOwner()) {
      remote = QueueRepairRemoteResult.adopt(liveAsset, furniture);
    } else {
      if (("REPAIR".equals(liveAsset.status()) || "CAPITAL_REPAIR".equals(liveAsset.status()))
          && !liveAsset.status().equals(plan.projectedAssetStatus())) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Rental item repair adoption requires a matching canonical asset fact");
      }
      MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.acquireLease(
          commandSupport.derived(work.idempotencyKey(), "acquire"),
          plan.rentalItemId(),
          liveAsset.version(),
          plan.ownerType(),
          plan.ownerId());
      MaintenanceReconciliationSupport.validateLeaseTruth(
          plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      if (!commandSupport.leaseIsFresh(lease.expiresAt())) {
        lease = dependencies.renewLease(
            commandSupport.derived(work.idempotencyKey(), "renew"),
            lease.leaseId(),
            lease.version(),
            lease.fencingToken(),
            plan.ownerType(),
            plan.ownerId());
        MaintenanceReconciliationSupport.validateLeaseTruth(
            plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      }
      commandSupport.requireFreshDependencyLease(lease);
      MaintenanceDependencyGateway.AssetSnapshot asset = furniture.isEmpty()
          ? dependencies.fencedStatus(
              commandSupport.derived(work.idempotencyKey(), "status"),
              plan.rentalItemId(),
              plan.warehouseId(),
              liveAsset.version(),
              lease.leaseId(),
              lease.fencingToken(),
              plan.ownerType(),
              plan.ownerId(),
              plan.queueTransition(),
              plan.linkedReturn())
          : dependencies.fencedStatus(
              commandSupport.derived(work.idempotencyKey(), "status"),
              plan.rentalItemId(),
              plan.warehouseId(),
              liveAsset.version(),
              lease.leaseId(),
              lease.fencingToken(),
              plan.ownerType(),
              plan.ownerId(),
              plan.queueTransition(),
              plan.linkedReturn(),
              furniture);
      validateQueueTransitionTruth(plan, liveAsset, asset);
      remote = QueueRepairRemoteResult.queued(liveAsset, lease, asset, furniture);
    }

    transactions.executeWithoutResult(status -> finalizeQueuedRepairClaim(work, plan, remote));
  }

  private Optional<QueueRepairPlan> prepareQueueRepairPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = taskBoardSupport.requireWorkRepair(work);
    if (prestartReplacementGuard.blocksRepairExecution(repair.getId())) {
      reconciliations.defer(work, Duration.ofSeconds(2));
      return Optional.empty();
    }
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      return Optional.of(QueueRepairPlan.alreadyQueued(repair));
    }
    if (repair.getExecutionState() != RepairExecutionState.DRAFT || repair.getKind() == RepairKind.REWORK) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair is no longer eligible for primary queue reconciliation");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    if (stages.isEmpty()) {
      throw commandSupport.invalid("Repair needs at least one planned stage before queueing");
    }
    RepairComplexitySnapshot complexity =
        repairModelSupport.repairComplexityFromStoredStages(repair.getWarehouseId(), repair.getId());
    RentalItemFactProjection fact = repairModelSupport.requireRentalItemFact(
        repair.getRentalItemId(), repair.getWarehouseId());
    boolean existingOwner = taskBoardSupport.primaryLifecycleOwnerWithLeaseIdentity(repair).isPresent();
    return Optional.of(
        new QueueRepairPlan(
            repair.getId(),
            repair.getVersion(),
            false,
            repair.getWarehouseId(),
            repair.getRentalItemId(),
            repair.getRentalItemVersionSnapshot(),
            repair.getOrigin(),
            complexity.type() == RepairComplexity.CAPITAL,
            complexity.type() == RepairComplexity.CAPITAL ? "CAPITAL_REPAIR" : "REPAIR",
            complexity.type() == RepairComplexity.CAPITAL
                ? "QUEUE_TO_CAPITAL_REPAIR"
                : "QUEUE_TO_REPAIR",
            eventPayloadSupport.ownerType(repair),
            eventPayloadSupport.ownerId(repair),
            work.payload().path("linkedReturn").asBoolean(false),
            fact.getAssetStatus(),
            fact.getAggregateVersion(),
            existingOwner,
            repair.getFurnitureAccountingMode(),
            List.copyOf(estimateSupport.furnitureQuantities(repair))));
  }

  private void finalizeQueuedRepairClaim(
      MaintenanceReconciliationStore.WorkItem work,
      QueueRepairPlan plan,
      QueueRepairRemoteResult remote) {
    Optional<QueueRepairPlan> currentOptional = prepareQueueRepairPlan(work);
    if (currentOptional == null || currentOptional.isEmpty()) return;
    QueueRepairPlan current = currentOptional.orElseThrow();
    if (!plan.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Repair queue reconciliation changed before finalization");
    }
    MaintenanceRepair repair = repairModelSupport.requireRepair(plan.repairId());
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    commandSupport.assertVersion(repair.getVersion(), expectedVersion);
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      reconciliations.confirmed(
          work, Map.of("repairId", repair.getId().toString(), "alreadyQueued", true));
      return;
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    if (remote.adoptExistingRepair()) {
      if (!current.existingLifecycleOwner()) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "Repair lifecycle owner disappeared before finalization");
      }
      taskBoardSupport.prepareStagesForQueue(stages, current.capital());
      repairStages.saveAllAndFlush(stages);
      repair.confirmRentalItemVersion(remote.liveAsset().version());
      if (current.capital()) {
        repair.queueExternalCapitalUnderExistingRepair();
      } else {
        repair.queueUnderExistingRepair();
      }
      MaintenanceRepair saved = repairs.saveAndFlush(repair);
      if (!current.capital()) {
        taskBoardSupport.enqueueOrdinaryRepairExecution(
            saved,
            commandSupport.stableOperationKey("register-task", saved.getExternalTaskId(), 0),
            commandSupport.stableOperationKey("driver-logistics-task", saved.getId(), 0));
        taskBoardSupport.enqueueRepairComplexityStatusSync(
            saved, commandSupport.derived(work.idempotencyKey(), "repair-complexity-status"));
      }
      events.append(
          MaintenanceAggregateType.REPAIR,
          saved.getId(),
          expectedVersion,
          MaintenanceEventType.REPAIR_QUEUED,
          eventPayloadSupport.repairLocal(saved),
          eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_QUEUED, saved),
          eventPayloadSupport.repairSnapshot(saved));
      reconciliations.confirmed(
          work,
          Map.of(
              "repairId", saved.getId().toString(),
              "assetAlreadyInRepair", true,
              "rentalItemVersion", remote.liveAsset().version()));
      return;
    }

    MaintenanceDependencyGateway.LeaseSnapshot lease = remote.lease();
    MaintenanceDependencyGateway.AssetSnapshot asset = remote.asset();
    if (lease == null || asset == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Repair queue remote result is incomplete");
    }
    MaintenanceReconciliationSupport.validateLeaseTruth(repair, lease, current.ownerType(), current.ownerId());
    validateQueueTransitionTruth(current, remote.liveAsset(), asset);
    taskBoardSupport.prepareStagesForQueue(stages, current.capital());
    repairStages.saveAllAndFlush(stages);
    repair.confirmRentalItemVersion(asset.version());
    if (current.capital()) {
      repair.queueExternalCapital(
          lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
    } else {
      repair.queue(lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
    }
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    if (!current.capital()) {
      taskBoardSupport.enqueueOrdinaryRepairExecution(
          saved,
          commandSupport.stableOperationKey("register-task", saved.getExternalTaskId(), 0),
          commandSupport.stableOperationKey("driver-logistics-task", saved.getId(), 0));
    }
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_QUEUED,
        eventPayloadSupport.repairLocal(saved),
        eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_QUEUED, saved),
        eventPayloadSupport.repairSnapshot(saved));
    reconciliations.confirmed(
        work,
        Map.of(
            "repairId", saved.getId().toString(),
            "leaseId", lease.leaseId().toString(),
            "rentalItemVersion", asset.version()));
  }

  private static void validateQueueAssetTruth(
      QueueRepairPlan plan, MaintenanceDependencyGateway.AssetSnapshot snapshot) {
    Set<String> eligibleStatuses = plan.origin() == RepairOrigin.DIRECT_REPAIR
        ? DIRECT_REPAIR_QUEUE_SOURCE_STATUSES
        : ESTIMATE_REPAIR_QUEUE_SOURCE_STATUSES;
    if (snapshot == null
        || !plan.rentalItemId().equals(snapshot.rentalItemId())
        || !plan.warehouseId().equals(snapshot.warehouseId())
        || snapshot.version() < 0
        || snapshot.status() == null
        || !eligibleStatuses.contains(snapshot.status())
        || snapshot.version() < plan.rentalItemVersion()
        || snapshot.version() < plan.projectedAssetVersion()
        || (snapshot.version() == plan.projectedAssetVersion()
            && !snapshot.status().equals(plan.projectedAssetStatus()))) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Canonical rental-item snapshot is not safe for repair queueing");
    }
  }

  private static List<MaintenanceDependencyGateway.FurniturePendingReturn>
      pendingReturnsFromCabinSnapshot(
          QueueRepairPlan plan, MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    MaintenanceReconciliationSupport.requireMatchingCabinForFurniture(
        plan.rentalItemId(), plan.warehouseId(), snapshot);
    Map<UUID, MaintenanceDependencyGateway.PropertyAssetContentSnapshot> contents =
        snapshot.contents().stream().collect(
            java.util.stream.Collectors.toMap(
                MaintenanceDependencyGateway.PropertyAssetContentSnapshot::equipmentId,
                value -> value));
    return plan.requestedFurniture().stream().map(
        line -> {
          MaintenanceDependencyGateway.PropertyAssetContentSnapshot content =
              contents.get(line.equipmentId());
          if (content == null || content.quantity() < line.quantity()) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Cabin no longer contains the furniture selected for maintenance");
          }
          return new MaintenanceDependencyGateway.FurniturePendingReturn(
              line.equipmentId(), content.balanceVersion(), line.quantity());
        }).toList();
  }

  private static List<MaintenanceDependencyGateway.FurniturePendingReturn>
      unaccountedFurnitureReturnsFromEmptyCabin(
          QueueRepairPlan plan, MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    if (!MaintenanceReconciliationSupport.hasNoRecordedCabinContents(
        plan.rentalItemId(), plan.warehouseId(), snapshot)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Cabin contents were recorded after unaccounted furniture confirmation; the repair cannot bypass normal furniture accounting");
    }
    return List.of();
  }

  private static void validateQueueTransitionTruth(
      QueueRepairPlan plan,
      MaintenanceDependencyGateway.AssetSnapshot liveAsset,
      MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (liveAsset == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Repair queue live asset snapshot is missing");
    }
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() < liveAsset.version()
        || (asset.version() == liveAsset.version()
            && !plan.desiredAssetStatus().equals(liveAsset.status()))
        || !plan.desiredAssetStatus().equals(asset.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not confirm the calculated repair status");
    }
  }

  void reconcileAssetTransitionClaim(MaintenanceReconciliationStore.WorkItem work) {
    AssetTransitionPlan plan = commandSupport.requireReconciliationResult(
        transactions.execute(status -> prepareAssetTransitionPlan(work)));
    MaintenanceDependencyGateway.LeaseSnapshot lease = plan.localLease();
    if (lease == null) {
      lease = dependencies.acquireLease(
          commandSupport.derived(work.idempotencyKey(), "acquire"),
          plan.rentalItemId(),
          plan.rentalItemVersion(),
          plan.ownerType(),
          plan.ownerId());
    }
    MaintenanceReconciliationSupport.validateLeaseTruth(
        plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    if (!commandSupport.leaseIsFresh(lease.expiresAt())) {
      lease = dependencies.renewLease(
          commandSupport.derived(work.idempotencyKey(), "renew"),
          lease.leaseId(),
          lease.version(),
          lease.fencingToken(),
          plan.ownerType(),
          plan.ownerId());
      MaintenanceReconciliationSupport.validateLeaseTruth(
          plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    }
    commandSupport.requireFreshDependencyLease(lease);
    MaintenanceDependencyGateway.AssetSnapshot asset = dependencies.fencedStatus(
        commandSupport.derived(work.idempotencyKey(), "status"),
        plan.rentalItemId(),
        plan.warehouseId(),
        plan.rentalItemVersion(),
        lease.leaseId(),
        lease.fencingToken(),
        plan.ownerType(),
        plan.ownerId(),
        plan.transition(),
        false);
    validateAssetTransitionTruth(plan, asset);
    boolean terminal = !"PENDING_ACCEPTANCE".equals(plan.transition());
    if (terminal) {
      dependencies.releaseLease(
          commandSupport.derived(work.idempotencyKey(), "release"),
          lease.leaseId(),
          lease.version(),
          lease.fencingToken(),
          plan.ownerType(),
          plan.ownerId());
    }
    MaintenanceDependencyGateway.LeaseSnapshot finalLease = lease;
    transactions.executeWithoutResult(
        status -> finalizeAssetTransitionClaim(work, plan, asset, finalLease, terminal));
  }

  private AssetTransitionPlan prepareAssetTransitionPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = taskBoardSupport.requireWorkRepair(work);
    List<MaintenanceRepair> sources = repairLifecycleSupport.sourceChain(repair);
    String ownerType = eventPayloadSupport.ownerType(repair);
    String ownerId = eventPayloadSupport.ownerId(repair);
    return new AssetTransitionPlan(
        repair.getId(),
        repair.getVersion(),
        sources.stream().map(MaintenanceRepair::getId).toList(),
        repair.getWarehouseId(),
        repair.getRentalItemId(),
        repair.getRentalItemVersionSnapshot(),
        ownerType,
        ownerId,
        work.operation(),
        reconciliationSupport.localLease(repair, sources, ownerType, ownerId).orElse(null));
  }

  private void finalizeAssetTransitionClaim(
      MaintenanceReconciliationStore.WorkItem work,
      AssetTransitionPlan plan,
      MaintenanceDependencyGateway.AssetSnapshot asset,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      boolean terminal) {
    MaintenanceRepair initial = repairModelSupport.requireRepair(plan.repairId());
    LockedRepairChain locked = repairLifecycleSupport.lockAndReloadRepairChain(initial, plan.repairVersion());
    MaintenanceRepair repair = locked.repair();
    List<MaintenanceRepair> sources = locked.sources();
    if (!plan.sourceRepairIds().equals(sources.stream().map(MaintenanceRepair::getId).toList())
        || !plan.warehouseId().equals(repair.getWarehouseId())
        || !plan.rentalItemId().equals(repair.getRentalItemId())
        || !plan.transition().equals(work.operation())
        || !plan.ownerType().equals(eventPayloadSupport.ownerType(repair))
        || !plan.ownerId().equals(eventPayloadSupport.ownerId(repair))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Asset-transition reconciliation changed before finalization");
    }
    MaintenanceReconciliationSupport.validateLeaseTruth(repair, lease, plan.ownerType(), plan.ownerId());
    MaintenanceReconciliationSupport.validateAssetTruth(repair, asset);
    reconciliationSupport.reconcileLeaseProjection(
        repair,
        locked.streamVersions().get(repairLifecycleSupport.stream(repair.getId())),
        asset.version(),
        lease,
        terminal,
        reconciliationSupport.transitionEvent(repair, plan.transition()));
    for (MaintenanceRepair source : sources) {
      reconciliationSupport.reconcileLeaseProjection(
          source,
          locked.streamVersions().get(repairLifecycleSupport.stream(source.getId())),
          asset.version(),
          lease,
          terminal,
          reconciliationSupport.transitionEvent(source, plan.transition()));
    }
    reconciliations.confirmed(
        work,
        Map.of(
            "repairId", repair.getId().toString(),
            "rentalItemVersion", asset.version(),
            "leaseReleased", terminal));
  }

  private static void validateAssetTransitionTruth(
      AssetTransitionPlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() <= plan.rentalItemVersion()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not match the expected rental item");
    }
  }

  void reconcileLeaseRenewalClaim(MaintenanceReconciliationStore.WorkItem work) {
    LeaseRenewalPlan plan = commandSupport.requireReconciliationResult(
        transactions.execute(status -> prepareLeaseRenewalPlan(work)));
    MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.renewLease(
        work.idempotencyKey(),
        plan.leaseId(),
        plan.leaseVersion(),
        plan.fencingToken(),
        plan.ownerType(),
        plan.ownerId());
    validateLeaseRenewalTruth(plan, lease);
    commandSupport.requireFreshDependencyLease(lease);
    transactions.executeWithoutResult(
        status -> {
          LeaseRenewalPlan current = prepareLeaseRenewalPlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT", "Lease renewal changed before finalization");
          }
          MaintenanceRepair repair = repairModelSupport.requireRepair(plan.repairId());
          long expectedVersion =
              events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
          commandSupport.assertVersion(repair.getVersion(), expectedVersion);
          repair.renewLease(lease.version(), lease.expiresAt());
          MaintenanceRepair saved = repairs.saveAndFlush(repair);
          MaintenanceEventType renewalEvent = reconciliationSupport.renewalEvent(saved);
          events.append(
              MaintenanceAggregateType.REPAIR,
              saved.getId(),
              expectedVersion,
              renewalEvent,
              eventPayloadSupport.repairLocal(saved),
              eventPayloadSupport.repairFact(renewalEvent, saved),
              eventPayloadSupport.repairSnapshot(saved));
          reconciliations.confirmed(
              work,
              Map.of(
                  "repairId", saved.getId().toString(),
                  "leaseVersion", lease.version(),
                  "expiresAt", lease.expiresAt().toString()));
        });
  }

  private LeaseRenewalPlan prepareLeaseRenewalPlan(MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = taskBoardSupport.requireWorkRepair(work);
    repairLifecycleSupport.requireRenewableLease(repair);
    return new LeaseRenewalPlan(
        repair.getId(),
        repair.getVersion(),
        repair.getRentalItemId(),
        repair.getLeaseId(),
        repair.getLeaseVersion(),
        repair.getFencingToken(),
        eventPayloadSupport.ownerType(repair),
        eventPayloadSupport.ownerId(repair));
  }

  private static void validateLeaseRenewalTruth(
      LeaseRenewalPlan plan, MaintenanceDependencyGateway.LeaseSnapshot lease) {
    MaintenanceReconciliationSupport.validateLeaseTruth(
        plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    if (!plan.leaseId().equals(lease.leaseId())
        || plan.fencingToken() != lease.fencingToken()
        || lease.version() != Math.addExact(plan.leaseVersion(), 1)) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease renewal does not preserve the current fence and version");
    }
  }

  void reconcileRepairComplexityStatusClaim(MaintenanceReconciliationStore.WorkItem work) {
    ComplexitySyncPlan plan = commandSupport.requireReconciliationResult(
        transactions.execute(status -> prepareComplexitySyncPlan(work, false)));

    // Withdraw existing ordinary routes first. Each call has a stable derived key, so a crash
    // before the final local commit replays the same task-board effect safely.
    for (TaskCancellationPlan cancellation : plan.taskCancellations()) {
      MaintenanceDependencyGateway.TaskSnapshot cancelled = dependencies.cancelTask(
          commandSupport.derived(work.idempotencyKey(), "withdraw-task:" + cancellation.repairId()),
          cancellation.externalTaskId(),
          cancellation.expectedVersion());
      if (cancelled == null || !"CANCELLED".equals(cancelled.state())) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board did not confirm ordinary repair route withdrawal");
      }
    }

    MaintenanceDependencyGateway.AssetSnapshot currentAsset =
        dependencies.getRentalItemSnapshot(plan.rentalItemId());
    validateComplexityAssetTruth(plan, currentAsset);
    MaintenanceDependencyGateway.AssetSnapshot synchronizedAsset = currentAsset;
    MaintenanceDependencyGateway.LeaseSnapshot lease = plan.lease();
    if (!plan.desiredStatus().equals(currentAsset.status())) {
      MaintenanceReconciliationSupport.validateLeaseTruth(
          plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
      if (!commandSupport.leaseIsFresh(lease.expiresAt())) {
        lease = dependencies.renewLease(
            commandSupport.derived(work.idempotencyKey(), "renew"),
            lease.leaseId(),
            lease.version(),
            lease.fencingToken(),
            plan.ownerType(),
            plan.ownerId());
        validateComplexityRenewedLeaseTruth(plan, lease);
      }
      commandSupport.requireFreshDependencyLease(lease);
      synchronizedAsset = dependencies.fencedStatus(
          commandSupport.derived(work.idempotencyKey(), "status"),
          plan.rentalItemId(),
          plan.warehouseId(),
          currentAsset.version(),
          lease.leaseId(),
          lease.fencingToken(),
          plan.ownerType(),
          plan.ownerId(),
          plan.transition(),
          false);
      validateComplexityTransitionTruth(plan, currentAsset, synchronizedAsset);
    }
    MaintenanceDependencyGateway.LeaseSnapshot finalLease = lease;
    MaintenanceDependencyGateway.AssetSnapshot finalAsset = synchronizedAsset;
    transactions.executeWithoutResult(
        status -> finalizeComplexitySyncClaim(work, plan, finalLease, finalAsset));
  }

  private ComplexitySyncPlan prepareComplexitySyncPlan(
      MaintenanceReconciliationStore.WorkItem work, boolean lockRepairs) {
    MaintenanceRepair requested = taskBoardSupport.requireWorkRepair(work);
    List<MaintenanceRepair> rentalItemRepairs = lockRepairs
        ? repairs.findAllByRentalItemIdForUpdate(requested.getRentalItemId())
        : repairs.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(requested.getRentalItemId());
    return complexitySyncPlan(work, requested.getId(), rentalItemRepairs);
  }

  private ComplexitySyncPlan complexitySyncPlan(
      MaintenanceReconciliationStore.WorkItem work,
      UUID requestedRepairId,
      List<MaintenanceRepair> rentalItemRepairs) {
    MaintenanceRepair repair = rentalItemRepairs.stream()
        .filter(value -> value.getId().equals(requestedRepairId))
        .findFirst()
        .orElseThrow(
            () -> new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Queued repair disappeared during complexity synchronization"));
    boolean externalCapitalPendingStatusSync =
        repair.getExecutionState() == RepairExecutionState.COMPLETED
            && repair.getAcceptanceState() == RepairAcceptanceState.PENDING
            && repair.getReclassificationState()
                == dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState.EXTERNAL_CAPITAL;
    if (repair.getExecutionState() != RepairExecutionState.QUEUED
        && repair.getExecutionState() != RepairExecutionState.IN_PROGRESS
        && !externalCapitalPendingStatusSync) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only active ordinary work or pending external capital repair can synchronize its calculated status");
    }
    List<MaintenanceRepair> complexityRepairs = rentalItemRepairs.stream().filter(
        value -> value.getExecutionState() != RepairExecutionState.DRAFT
            && value.getExecutionState() != RepairExecutionState.CANCELLED
            && value.getAcceptanceState() != RepairAcceptanceState.ACCEPTED
            && value.getAcceptanceState() != RepairAcceptanceState.WRITTEN_OFF).toList();
    List<MaintenanceRepair> leaseOwners = rentalItemRepairs.stream()
        .filter(value -> value.getKind() == RepairKind.PRIMARY)
        .filter(
            value -> value.getExecutionState() != RepairExecutionState.DRAFT
                && value.getAcceptanceState() != RepairAcceptanceState.ACCEPTED
                && value.getAcceptanceState() != RepairAcceptanceState.WRITTEN_OFF
                && value.getLeaseId() != null
                && value.getLeaseVersion() != null
                && value.getFencingToken() != null
                && value.getLeaseExpiresAt() != null
                && Set.of("ACTIVE", "RECONCILIATION_REQUIRED")
                    .contains(value.getLeaseReconciliationState()))
        .toList();
    if (leaseOwners.size() != 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_LEASE_CONFLICT",
          "Repair complexity synchronization requires one cabin lifecycle owner");
    }
    MaintenanceRepair leaseOwner = leaseOwners.getFirst();
    Map<UUID, MaintenanceRepair> scope = new LinkedHashMap<>();
    complexityRepairs.forEach(value -> scope.put(value.getId(), value));
    scope.put(leaseOwner.getId(), leaseOwner);
    List<MaintenanceRepair> synchronizedRepairs = List.copyOf(scope.values());
    repairModelSupport.requireTransferSource(
        synchronizedRepairs, repair.getRentalItemId(), repair.getWarehouseId());

    boolean capital = complexityRepairs.stream().map(
        value -> repairModelSupport.repairComplexityFromStoredStages(
            value.getWarehouseId(), value.getId()))
        .anyMatch(complexity -> complexity.type() == RepairComplexity.CAPITAL);
    List<TaskCancellationPlan> cancellations = capital
        ? complexityRepairs.stream()
            .filter(value -> value.getExecutionState() == RepairExecutionState.QUEUED
                || value.getExecutionState() == RepairExecutionState.IN_PROGRESS)
            .filter(value -> value.getTaskBoardVersion() != null)
            .map(value -> new TaskCancellationPlan(
                value.getId(), value.getExternalTaskId(), value.getTaskBoardVersion()))
            .toList()
        : List.of();
    long latestProjectedVersion = synchronizedRepairs.stream()
        .mapToLong(MaintenanceRepair::getRentalItemVersionSnapshot)
        .max()
        .orElseThrow();
    String ownerType = eventPayloadSupport.ownerType(leaseOwner);
    String ownerId = eventPayloadSupport.ownerId(leaseOwner);
    MaintenanceDependencyGateway.LeaseSnapshot lease =
        new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseOwner.getLeaseId(),
            leaseOwner.getLeaseVersion(),
            leaseOwner.getRentalItemId(),
            ownerType,
            UUID.fromString(ownerId),
            leaseOwner.getFencingToken(),
            leaseOwner.getLeaseExpiresAt());
    List<RepairSyncSignature> signatures = synchronizedRepairs.stream()
        .sorted(Comparator.comparing(value -> value.getId().toString()))
        .map(MaintenanceRepairLifecycleReconciliationUseCases::repairSyncSignature)
        .toList();
    return new ComplexitySyncPlan(
        repair.getId(),
        repair.getWarehouseId(),
        repair.getRentalItemId(),
        capital,
        capital ? "CAPITAL_REPAIR" : "REPAIR",
        capital ? "QUEUE_TO_CAPITAL_REPAIR" : "QUEUE_TO_REPAIR",
        latestProjectedVersion,
        ownerType,
        ownerId,
        lease,
        leaseOwner.getId(),
        complexityRepairs.stream().map(MaintenanceRepair::getId).sorted(
            Comparator.comparing(UUID::toString)).toList(),
        signatures,
        List.copyOf(cancellations));
  }

  private void finalizeComplexitySyncClaim(
      MaintenanceReconciliationStore.WorkItem work,
      ComplexitySyncPlan plan,
      MaintenanceDependencyGateway.LeaseSnapshot lease,
      MaintenanceDependencyGateway.AssetSnapshot synchronizedAsset) {
    ComplexitySyncPlan current = prepareComplexitySyncPlan(work, true);
    if (!plan.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair complexity synchronization changed before finalization");
    }
    validateComplexityAssetTruth(plan, synchronizedAsset);
    if (!plan.desiredStatus().equals(synchronizedAsset.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not confirm the recalculated repair status");
    }
    MaintenanceReconciliationSupport.validateLeaseTruth(
        plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());

    Map<UUID, MaintenanceRepair> byId = repairs
        .findAllByRentalItemIdForUpdate(plan.rentalItemId())
        .stream()
        .collect(java.util.stream.Collectors.toMap(MaintenanceRepair::getId, value -> value));
    List<MaintenanceRepair> synchronizedRepairs = plan.signatures().stream()
        .map(signature -> Optional.ofNullable(byId.get(signature.id())).orElseThrow(
            () -> new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT", "Repair synchronization scope changed")))
        .toList();
    Map<MaintenanceEventStore.StreamRef, Long> streamVersions = events.lockStreams(
        synchronizedRepairs.stream().map(value -> repairLifecycleSupport.stream(value.getId())).toList());
    synchronizedRepairs.forEach(value -> repairLifecycleSupport.assertStreamParity(value, streamVersions));
    Map<UUID, MaintenanceRepair> changed = new LinkedHashMap<>();
    if (plan.capital()) {
      for (MaintenanceRepair value : synchronizedRepairs) {
        if (!plan.complexityRepairIds().contains(value.getId())) continue;
        if (value.getExecutionState() != RepairExecutionState.QUEUED
            && value.getExecutionState() != RepairExecutionState.IN_PROGRESS) {
          continue;
        }
        List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(value.getId());
        stages.forEach(RepairStage::completeAsExternalCapital);
        repairStages.saveAllAndFlush(stages);
        value.completeAsExternalCapital();
        repairPlaces.completeAfterRepair(
            value.getWarehouseId(), value.getId(), value.isMovementToRepair());
        changed.put(value.getId(), value);
      }
    } else {
      for (MaintenanceRepair value : synchronizedRepairs) {
        if (!plan.complexityRepairIds().contains(value.getId())) continue;
        if ((value.getExecutionState() == RepairExecutionState.QUEUED
                || value.getExecutionState() == RepairExecutionState.IN_PROGRESS)
            && value.stabilizeOrdinaryClassification()) {
          changed.put(value.getId(), value);
        }
      }
    }
    for (MaintenanceRepair value : synchronizedRepairs) {
      if (value.getRentalItemVersionSnapshot() != synchronizedAsset.version()) {
        value.confirmRentalItemVersion(synchronizedAsset.version());
        changed.put(value.getId(), value);
      }
    }
    RepairSyncSignature ownerSignature = plan.signatures().stream()
        .filter(signature -> signature.id().equals(plan.leaseOwnerRepairId()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Repair lifecycle owner is missing"));
    if (lease.version() != ownerSignature.leaseVersion()
        || !lease.expiresAt().equals(ownerSignature.leaseExpiresAt())) {
      UUID previousLeaseId = ownerSignature.leaseId();
      long previousFencingToken = ownerSignature.fencingToken();
      for (MaintenanceRepair value : synchronizedRepairs) {
        if (previousLeaseId.equals(value.getLeaseId())
            && value.getFencingToken() != null
            && value.getFencingToken() == previousFencingToken) {
          repairLifecycleSupport.applyLeaseSnapshot(value, lease);
          changed.put(value.getId(), value);
        }
      }
    }
    if (!changed.isEmpty()) {
      List<MaintenanceRepair> saved = repairs.saveAllAndFlush(
          changed.values().stream()
              .sorted(Comparator.comparing(value -> value.getId().toString()))
              .toList());
      for (MaintenanceRepair value : saved) {
        long expectedVersion = streamVersions.get(repairLifecycleSupport.stream(value.getId()));
        events.append(
            MaintenanceAggregateType.REPAIR,
            value.getId(),
            expectedVersion,
            MaintenanceEventType.REPAIR_PLAN_CHANGED,
            eventPayloadSupport.repairLocal(value),
            eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, value),
            eventPayloadSupport.repairSnapshot(value));
      }
    }
    reconciliations.confirmed(
        work,
        Map.of(
            "repairId", plan.repairId().toString(),
            "repairStatus", plan.desiredStatus(),
            "rentalItemVersion", synchronizedAsset.version()));
  }

  private static RepairSyncSignature repairSyncSignature(MaintenanceRepair value) {
    return new RepairSyncSignature(
        value.getId(),
        value.getVersion(),
        value.getExecutionState(),
        value.getAcceptanceState(),
        value.getReclassificationState(),
        value.getTaskBoardVersion(),
        value.getExternalTaskId(),
        value.getRentalItemVersionSnapshot(),
        value.getLeaseId(),
        value.getLeaseVersion(),
        value.getFencingToken(),
        value.getLeaseExpiresAt(),
        value.isMovementToRepair());
  }

  private static void validateComplexityAssetTruth(
      ComplexitySyncPlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() < plan.latestProjectedVersion()
        || !Set.of("REPAIR", "CAPITAL_REPAIR").contains(asset.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Canonical rental-item snapshot is not safe for repair complexity synchronization");
    }
  }

  private static void validateComplexityRenewedLeaseTruth(
      ComplexitySyncPlan plan, MaintenanceDependencyGateway.LeaseSnapshot lease) {
    MaintenanceReconciliationSupport.validateLeaseTruth(
        plan.rentalItemId(), lease, plan.ownerType(), plan.ownerId());
    if (!plan.lease().leaseId().equals(lease.leaseId())
        || plan.lease().fencingToken() != lease.fencingToken()
        || lease.version() != Math.addExact(plan.lease().version(), 1)) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease renewal does not preserve the current fence and version");
    }
  }

  private static void validateComplexityTransitionTruth(
      ComplexitySyncPlan plan,
      MaintenanceDependencyGateway.AssetSnapshot current,
      MaintenanceDependencyGateway.AssetSnapshot synchronizedAsset) {
    if (synchronizedAsset == null
        || !plan.rentalItemId().equals(synchronizedAsset.rentalItemId())
        || !plan.warehouseId().equals(synchronizedAsset.warehouseId())
        || synchronizedAsset.version() < current.version()
        || (synchronizedAsset.version() == current.version()
            && !plan.desiredStatus().equals(current.status()))
        || !plan.desiredStatus().equals(synchronizedAsset.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not confirm the recalculated repair status");
    }
  }
}
