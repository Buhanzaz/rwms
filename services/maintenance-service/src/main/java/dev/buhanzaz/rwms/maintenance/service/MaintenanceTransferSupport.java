package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Builds and verifies repair-transfer plans while keeping remote transfer effects outside local transactions. */
@Service
final class MaintenanceTransferSupport {
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceDependencyGateway dependencies;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceReconciliationSupport reconciliationSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;

  MaintenanceTransferSupport(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceDependencyGateway dependencies,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceReconciliationSupport reconciliationSupport,
      MaintenanceRepairModelSupport repairModelSupport) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.dependencies = dependencies;
    this.commandSupport = commandSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.reconciliationSupport = reconciliationSupport;
    this.repairModelSupport = repairModelSupport;
  }

  protected TransferDeparturePlan transferDeparturePlan(
      List<MaintenanceRepair> candidates,
      MaintenanceRepair active,
      TransferRepairRequest request) {
    if (active == null) {
      return new TransferDeparturePlan(null, null, List.of(), null);
    }
    List<MaintenanceRepair> chain = repairs.findRepairChain(commandSupport.rootId(active));
    if (chain.isEmpty()) {
      throw new MaintenanceNotFoundException("Active repair chain not found");
    }
    repairModelSupport.requireTransferSource(chain, request.rentalItemId(), request.sourceWarehouseId());
    MaintenanceRepair owner = repairModelSupport.repairLifecycleOwner(chain, active);
    TransferLeaseReleasePlan leaseRelease = null;
    if (owner.getLeaseId() != null
        && ("ACTIVE".equals(owner.getLeaseReconciliationState())
            || "RECONCILIATION_REQUIRED".equals(owner.getLeaseReconciliationState()))) {
      if (owner.getLeaseVersion() == null || owner.getFencingToken() == null) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_LEASE_CONFLICT",
            "Transfer departure lease snapshot is incomplete");
      }
      leaseRelease =
          new TransferLeaseReleasePlan(
              owner.getId(),
              owner.getLeaseId(),
              owner.getLeaseVersion(),
              owner.getFencingToken(),
              eventPayloadSupport.ownerType(owner),
              eventPayloadSupport.ownerId(owner));
    }
    return new TransferDeparturePlan(
        request.sourceWarehouseId(),
        active.getId(),
        transferRepairSignatures(chain),
        leaseRelease);
  }

  protected void releaseTransferLease(TransferLeaseReleasePlan plan, UUID key) {
    commandSupport.requireNoCallerTransaction("release a transfer departure lease");
    dependencies.releaseLease(
        commandSupport.derived(key, "transfer-release:" + plan.ownerRepairId()),
        plan.leaseId(),
        plan.leaseVersion(),
        plan.fencingToken(),
        plan.ownerType(),
        plan.ownerId());
  }

  protected static void requireMatchingTransferDeparturePlan(
      TransferDeparturePlan expected, TransferDeparturePlan current) {
    if (!expected.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair transfer departure changed before finalization");
    }
  }

  protected TransferArrivalPlan transferArrivalPlan(
      UUID transferId, UUID lineId, TransferRepairRequest request) {
    return transferArrivalPlan(
        transferId,
        lineId,
        request.rentalItemId(),
        request.sourceWarehouseId(),
        request.targetWarehouseId(),
        null);
  }

  protected TransferArrivalPlan transferArrivalPlan(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    return transferArrivalPlan(
        transferId,
        lineId,
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId,
        null);
  }

  protected TransferArrivalPlan transferArrivalPlan(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Long expectedAssetVersion) {
    return transferArrivalPlan(
        repairs.findAllByTransferLineIdOrderByCreatedAtAscIdAsc(lineId),
        transferId,
        lineId,
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId,
        expectedAssetVersion);
  }

  protected TransferArrivalPlan transferArrivalPlan(
      List<MaintenanceRepair> chain,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    return transferArrivalPlan(
        chain,
        transferId,
        lineId,
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId,
        null);
  }

  protected TransferArrivalPlan transferArrivalPlan(
      List<MaintenanceRepair> chain,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Long expectedAssetVersion) {
    if (chain.isEmpty()) {
      return new TransferArrivalPlan(
          null,
          rentalItemId,
          sourceWarehouseId,
          targetWarehouseId,
          expectedAssetVersion,
          null,
          List.of(),
          List.of(),
          null,
          List.of());
    }
    repairModelSupport.requirePreparedTransfer(
        chain, transferId, lineId, rentalItemId, sourceWarehouseId, targetWarehouseId);
    MaintenanceRepair active = repairModelSupport.requireSingleActiveRepair(chain, true);
    if (active == null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Prepared transfer no longer has an active repair");
    }
    RepairComplexity targetComplexity =
        repairModelSupport.repairComplexityFromStoredStages(targetWarehouseId, active.getId()).type();
    List<TransferTaskPlan> tasks =
        chain.stream()
            .filter(repair -> repair.getTaskBoardVersion() != null)
            .filter(repair -> repairModelSupport.hasUnfinishedStages(repair.getId()))
            .sorted(Comparator.comparing(repair -> repair.getId().toString()))
            .map(
                repair ->
                    new TransferTaskPlan(
                        repair.getId(),
                        repair.getExternalTaskId(),
                        repair.getTaskBoardVersion(),
                        targetComplexity == RepairComplexity.CAPITAL))
            .toList();
    MaintenanceRepair owner = repairModelSupport.repairLifecycleOwner(chain, active);
    return new TransferArrivalPlan(
        active.getId(),
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId,
        expectedAssetVersion,
        targetComplexity,
        transferRepairSignatures(chain),
        tasks,
        new TransferLeaseOwnerPlan(owner.getId(), eventPayloadSupport.ownerType(owner), eventPayloadSupport.ownerId(owner)),
        transferRoutingRequirements(chain, targetComplexity));
  }

  protected static void requireMatchingTransferArrivalPlan(
      TransferArrivalPlan expected, TransferArrivalPlan current) {
    if (!expected.equals(current)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Repair transfer arrival changed before finalization");
    }
  }

  protected TransferRoutingResult resolveTransferArrivalRouting(TransferArrivalPlan plan) {
    commandSupport.requireNoCallerTransaction("resolve repair transfer routing truth");
    MaintenanceDependencyGateway.QueueCapabilities capabilities =
        dependencies.queueCapabilities(plan.targetWarehouseId());
    if (capabilities == null
        || !plan.targetWarehouseId().equals(capabilities.warehouseId())
        || capabilities.movementQueueDefinitions() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned queue capabilities for another warehouse");
    }
    if (plan.routingRequirements().isEmpty()) {
      return new TransferRoutingResult(List.of());
    }
    MaintenanceDependencyGateway.RoutingPreflight preflight =
        dependencies.preflightMaintenanceRouting(
            capabilities.warehouseId(), plan.routingRequirements());
    if (preflight == null
        || !capabilities.warehouseId().equals(preflight.warehouseId())
        || preflight.missingQueueDefinitionIds() == null
        || preflight.missingWarehouseBindingDefinitionIds() == null
        || preflight.mismatches() == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned malformed repair transfer routing truth");
    }
    Set<UUID> missing = new LinkedHashSet<>();
    missing.addAll(preflight.missingQueueDefinitionIds());
    missing.addAll(preflight.missingWarehouseBindingDefinitionIds());
    preflight.mismatches().stream()
        .map(MaintenanceDependencyGateway.RoutingMismatch::queueDefinitionId)
        .forEach(missing::add);
    if (preflight.ready() != missing.isEmpty()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned inconsistent repair transfer routing truth");
    }
    return new TransferRoutingResult(
        missing.stream().sorted(Comparator.comparing(UUID::toString)).toList());
  }

  protected TransferArrivalRemoteResult completeTransferArrivalRemote(
      TransferArrivalPlan plan, UUID key, TransferRoutingResult routing) {
    commandSupport.requireNoCallerTransaction("apply repair transfer arrival dependency effects");
    if (!routing.missingQueueDefinitionIds().isEmpty()) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_TARGET_QUEUE_MISSING",
          "The target warehouse is missing queues required by the active repair");
    }
    Map<UUID, Long> relocatedTaskVersions = new LinkedHashMap<>();
    Set<UUID> cancelledTaskRepairIds = new LinkedHashSet<>();
    for (TransferTaskPlan task : plan.tasks()) {
      MaintenanceDependencyGateway.TaskSnapshot current = dependencies.getTask(task.externalTaskId());
      validateTransferTaskReplayDiagnostic(task, current);
      if (task.cancelForCapital()) {
        MaintenanceDependencyGateway.TaskSnapshot cancelled =
            dependencies.cancelTask(
                commandSupport.derived(key, "task-withdraw-capital:" + task.repairId()),
                task.externalTaskId(),
                task.expectedVersion());
        validateCancelledTransferTask(task, cancelled);
        cancelledTaskRepairIds.add(task.repairId());
      } else {
        MaintenanceDependencyGateway.TaskSnapshot relocated =
            dependencies.relocateTask(
                commandSupport.derived(key, "task-relocate:" + task.repairId()),
                task.externalTaskId(),
                task.expectedVersion(),
                plan.targetWarehouseId());
        validateRelocatedTransferTask(task, relocated);
        relocatedTaskVersions.put(task.repairId(), relocated.version());
      }
    }

    MaintenanceDependencyGateway.AssetSnapshot asset =
        dependencies.getRentalItemSnapshot(plan.rentalItemId());
    validateTransferArrivalAssetReplayDiagnostic(plan, asset);
    long expectedAssetVersion = requiredTransferArrivalAssetVersion(plan);
    MaintenanceDependencyGateway.LeaseSnapshot lease =
        dependencies.acquireLease(
            commandSupport.derived(key, "transfer-arrival-lease:" + plan.leaseOwner().repairId()),
            plan.rentalItemId(),
            expectedAssetVersion,
            plan.leaseOwner().ownerType(),
            plan.leaseOwner().ownerId());
    MaintenanceReconciliationSupport.validateLeaseTruth(
        plan.rentalItemId(), lease, plan.leaseOwner().ownerType(), plan.leaseOwner().ownerId());

    MaintenanceDependencyGateway.AssetSnapshot finalAsset = asset;
    if (plan.targetComplexity() == RepairComplexity.CAPITAL) {
      finalAsset =
          dependencies.fencedStatus(
              commandSupport.derived(key, "transfer-arrival-capital:" + plan.activeRepairId()),
              plan.rentalItemId(),
              plan.targetWarehouseId(),
              expectedAssetVersion,
              lease.leaseId(),
              lease.fencingToken(),
              plan.leaseOwner().ownerType(),
              plan.leaseOwner().ownerId(),
              "QUEUE_TO_CAPITAL_REPAIR",
              false);
    }
    validateTransferArrivalAssetAfterEffects(plan, finalAsset);
    return new TransferArrivalRemoteResult(
        routing,
        Map.copyOf(relocatedTaskVersions),
        Set.copyOf(cancelledTaskRepairIds),
        finalAsset,
        lease);
  }

  protected static void validateTransferTaskReplayDiagnostic(
      TransferTaskPlan plan, MaintenanceDependencyGateway.TaskSnapshot task) {
    boolean preEffect =
        task != null
            && plan.externalTaskId().equals(task.externalTaskId())
            && task.version() == plan.expectedVersion()
            && "ACTIVE".equals(task.state());
    boolean postEffect =
        task != null
            && plan.externalTaskId().equals(task.externalTaskId())
            && task.version() == Math.addExact(plan.expectedVersion(), 1)
            && (plan.cancelForCapital()
                ? "CANCELLED".equals(task.state())
                : "ACTIVE".equals(task.state()));
    if (!preEffect && !postEffect) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Task-board task changed before repair transfer arrival");
    }
  }

  protected static void validateCancelledTransferTask(
      TransferTaskPlan plan, MaintenanceDependencyGateway.TaskSnapshot task) {
    if (task == null
        || !plan.externalTaskId().equals(task.externalTaskId())
        || task.version() != Math.addExact(plan.expectedVersion(), 1)
        || !"CANCELLED".equals(task.state())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board did not confirm ordinary repair route withdrawal");
    }
  }

  protected static void validateRelocatedTransferTask(
      TransferTaskPlan plan, MaintenanceDependencyGateway.TaskSnapshot task) {
    if (task == null
        || !plan.externalTaskId().equals(task.externalTaskId())
        || task.version() != Math.addExact(plan.expectedVersion(), 1)
        || !"ACTIVE".equals(task.state())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board did not confirm repair task relocation");
    }
  }

  protected static long requiredTransferArrivalAssetVersion(TransferArrivalPlan plan) {
    if (plan.expectedAssetVersion() == null || plan.expectedAssetVersion() < 0) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_TRANSFER_ASSET_VERSION_REQUIRED",
          "Transfer arrival must include the immutable asset version observed by logistics");
    }
    return plan.expectedAssetVersion();
  }

  protected static void validateTransferArrivalAssetReplayDiagnostic(
      TransferArrivalPlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    long expectedVersion = requiredTransferArrivalAssetVersion(plan);
    boolean preEffect =
        asset != null
            && plan.rentalItemId().equals(asset.rentalItemId())
            && plan.targetWarehouseId().equals(asset.warehouseId())
            && asset.version() == expectedVersion
            && "REPAIR".equals(asset.status());
    boolean postCapitalEffect =
        plan.targetComplexity() == RepairComplexity.CAPITAL
            && asset != null
            && plan.rentalItemId().equals(asset.rentalItemId())
            && plan.targetWarehouseId().equals(asset.warehouseId())
            && asset.version() == Math.addExact(expectedVersion, 1)
            && "CAPITAL_REPAIR".equals(asset.status());
    if (!preEffect && !postCapitalEffect) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Asset changed before repair transfer arrival replay");
    }
  }

  protected static void validateTransferArrivalAssetAfterEffects(
      TransferArrivalPlan plan,
      MaintenanceDependencyGateway.AssetSnapshot after) {
    long expectedVersion = requiredTransferArrivalAssetVersion(plan);
    String expectedStatus =
        plan.targetComplexity() == RepairComplexity.CAPITAL ? "CAPITAL_REPAIR" : "REPAIR";
    long expectedResultVersion =
        plan.targetComplexity() == RepairComplexity.CAPITAL
            ? Math.addExact(expectedVersion, 1)
            : expectedVersion;
    if (after == null
        || !plan.rentalItemId().equals(after.rentalItemId())
        || !plan.targetWarehouseId().equals(after.warehouseId())
        || after.version() != expectedResultVersion
        || !expectedStatus.equals(after.status())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not confirm repair transfer arrival status");
    }
  }

  protected static void validateTransferArrivalRemoteTruth(
      TransferArrivalPlan plan, TransferArrivalRemoteResult remote) {
    if (remote.routing() == null
        || !remote.routing().missingQueueDefinitionIds().isEmpty()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Repair transfer arrival routing result is incomplete");
    }
    Set<UUID> expectedTaskRepairIds =
        plan.tasks().stream().map(TransferTaskPlan::repairId).collect(java.util.stream.Collectors.toSet());
    if (plan.targetComplexity() == RepairComplexity.CAPITAL) {
      if (!expectedTaskRepairIds.equals(remote.cancelledTaskRepairIds())
          || !remote.relocatedTaskVersions().isEmpty()) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Repair transfer capital task reconciliation is incomplete");
      }
    } else if (!expectedTaskRepairIds.equals(remote.relocatedTaskVersions().keySet())
        || !remote.cancelledTaskRepairIds().isEmpty()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Repair transfer task relocation result is incomplete");
    }
    for (TransferTaskPlan task : plan.tasks()) {
      if (!task.cancelForCapital()
          && !Long.valueOf(Math.addExact(task.expectedVersion(), 1))
              .equals(remote.relocatedTaskVersions().get(task.repairId()))) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board did not return the immutable transfer relocation result");
      }
    }
    validateTransferArrivalAssetAfterEffects(plan, remote.asset());
    MaintenanceReconciliationSupport.validateLeaseTruth(
        plan.rentalItemId(),
        remote.lease(),
        plan.leaseOwner().ownerType(),
        plan.leaseOwner().ownerId());
  }

  protected List<MaintenanceDependencyGateway.RoutingQueueRequirement> transferRoutingRequirements(
      List<MaintenanceRepair> chain, RepairComplexity targetComplexity) {
    Map<UUID, MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        new LinkedHashMap<>();
    if (targetComplexity != RepairComplexity.CAPITAL) {
      for (MaintenanceRepair repair : chain) {
        for (RepairStage stage :
            repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
          if (stage.getState() == RepairStageState.DONE
              || stage.getState() == RepairStageState.CANCELLED) {
            continue;
          }
          MaintenanceDependencyGateway.RoutingQueueRequirement requirement =
              new MaintenanceDependencyGateway.RoutingQueueRequirement(
                  stage.getRoutingQueueId(),
                  stage.getRoutingQueueType().trim().toUpperCase(Locale.ROOT));
          MaintenanceDependencyGateway.RoutingQueueRequirement previous =
              requirements.putIfAbsent(
                  requirement.queueDefinitionId(), requirement);
          if (previous != null && !previous.equals(requirement)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "One repair queue definition has conflicting route types");
          }
        }
      }
    }
    return List.copyOf(requirements.values());
  }

  protected static List<TransferRepairSignature> transferRepairSignatures(
      List<MaintenanceRepair> values) {
    return values.stream()
        .map(
            repair ->
                new TransferRepairSignature(
                    repair.getId(),
                    repair.getVersion(),
                    repair.getWarehouseId(),
                    repair.getRentalItemId(),
                    repair.getRentalItemVersionSnapshot(),
                    repair.getRootRepairId(),
                    repair.getSourceRepairId(),
                    repair.getExecutionState(),
                    repair.getAcceptanceState(),
                    repair.getReclassificationState(),
                    repair.getTransferState(),
                    repair.getTransferDocumentId(),
                    repair.getTransferLineId(),
                    repair.getTransferTargetWarehouseId(),
                    repair.getExternalTaskId(),
                    repair.getTaskBoardVersion(),
                    repair.getLeaseId(),
                    repair.getLeaseVersion(),
                    repair.getFencingToken(),
                    repair.getLeaseExpiresAt(),
                    repair.getLeaseReconciliationState()))
        .sorted(Comparator.comparing(value -> value.id().toString()))
        .toList();
  }

  protected void appendRepairTransferEvents(
      List<MaintenanceRepair> saved,
      Map<UUID, Long> previousVersions,
      MaintenanceEventType eventType) {
    for (MaintenanceRepair repair :
        saved.stream()
            .sorted(Comparator.comparing(value -> value.getId().toString()))
            .toList()) {
      Long previousVersion = previousVersions.get(repair.getId());
      if (previousVersion == null) {
        throw new IllegalStateException(
            "Repair transfer lost its previous aggregate version");
      }
      events.append(
          MaintenanceAggregateType.REPAIR,
          repair.getId(),
          previousVersion,
          eventType,
          eventPayloadSupport.repairLocal(repair),
          eventPayloadSupport.repairFact(eventType, repair),
          eventPayloadSupport.repairSnapshot(repair));
    }
  }
}
