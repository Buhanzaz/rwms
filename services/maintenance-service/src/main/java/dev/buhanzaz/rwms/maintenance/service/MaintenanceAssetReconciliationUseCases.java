package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles asset-facing effects for accepted repair work and empty completed estimates. */
@Service
final class MaintenanceAssetReconciliationUseCases {
  private final MaintenanceReconciliationStore reconciliations;
  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final PropertyDispositionApplicationService propertyDispositions;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateModelSupport estimateModelSupport;
  private final MaintenanceEstimateSupport estimateSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;
  private final MaintenanceTaskBoardSupport taskBoardSupport;
  private final TransactionTemplate transactions;

  MaintenanceAssetReconciliationUseCases(
      MaintenanceReconciliationStore reconciliations,
      MaintenanceDependencyGateway dependencies,
      WarehouseLifecycleOperations warehouseLifecycle,
      PropertyDispositionApplicationService propertyDispositions,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateModelSupport estimateModelSupport,
      MaintenanceEstimateSupport estimateSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceRepairModelSupport repairModelSupport,
      MaintenanceTaskBoardSupport taskBoardSupport,
      PlatformTransactionManager transactionManager) {
    this.reconciliations = reconciliations;
    this.dependencies = dependencies;
    this.warehouseLifecycle = warehouseLifecycle;
    this.propertyDispositions = propertyDispositions;
    this.commandSupport = commandSupport;
    this.estimateModelSupport = estimateModelSupport;
    this.estimateSupport = estimateSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.repairModelSupport = repairModelSupport;
    this.taskBoardSupport = taskBoardSupport;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  void reconcileAcceptedCharacteristicClaim(MaintenanceReconciliationStore.WorkItem work) {
    CharacteristicPlan plan = commandSupport.requireReconciliationResult(
        transactions.execute(status -> prepareCharacteristicPlan(work)));
    MaintenanceDependencyGateway.AppliedCabinCharacteristic applied =
        dependencies.applyCabinCharacteristic(
            work.idempotencyKey(), plan.rentalItemId(), plan.characteristicId());
    validateAppliedCharacteristicTruth(plan, applied);
    transactions.executeWithoutResult(
        status -> {
          CharacteristicPlan current = prepareCharacteristicPlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Accepted-characteristic reconciliation changed before finalization");
          }
          reconciliations.confirmed(
              work,
              Map.of(
                  "rentalItemId", applied.rentalItemId().toString(),
                  "characteristicId", applied.characteristicId().toString(),
                  "added", applied.added(),
                  "rentalItemVersion", applied.rentalItemVersion()));
        });
  }

  private CharacteristicPlan prepareCharacteristicPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = taskBoardSupport.requireWorkRepair(work);
    UUID rentalItemId = commandSupport.uuidField(work.payload(), "rentalItemId");
    UUID characteristicId = commandSupport.uuidField(work.payload(), "characteristicId");
    UUID rootRepairId = commandSupport.uuidField(work.payload(), "rootRepairId");
    if (!"ASSET".equals(work.dependency())
        || !"APPLY_CHARACTERISTIC".equals(work.operation())
        || !repair.getRentalItemId().equals(rentalItemId)
        || !commandSupport.rootId(repair).equals(rootRepairId)
        || repair.getAcceptanceState() != RepairAcceptanceState.ACCEPTED) {
      throw new IllegalStateException("Stored accepted-characteristic reconciliation is incomplete");
    }
    return new CharacteristicPlan(
        rentalItemId, characteristicId, rootRepairId, repair.getRentalItemVersionSnapshot());
  }

  private static void validateAppliedCharacteristicTruth(
      CharacteristicPlan plan,
      MaintenanceDependencyGateway.AppliedCabinCharacteristic applied) {
    if (applied == null
        || !plan.rentalItemId().equals(applied.rentalItemId())
        || !plan.characteristicId().equals(applied.characteristicId())
        || applied.rentalItemVersion() < plan.minimumRentalItemVersion()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service returned mismatched cabin characteristic truth");
    }
  }

  void reconcileFurnitureCustodyClaim(MaintenanceReconciliationStore.WorkItem work) {
    FurnitureCustodyPlan plan = commandSupport.requireReconciliationResult(
        transactions.execute(status -> prepareFurnitureCustodyPlan(work)));
    if (plan.furnitureAccountingMode() == FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS) {
      warehouseLifecycle.requireOutgoing(plan.warehouseId());
      transactions.executeWithoutResult(
          status -> {
            FurnitureCustodyPlan current = prepareFurnitureCustodyPlan(work);
            if (!plan.equals(current)) {
              throw new MaintenanceConflictException(
                  "MAINTENANCE_STATE_CONFLICT",
                  "Unaccounted furniture reconciliation changed before finalization");
            }
            int created = propertyDispositions.materializeUnaccountedFurnitureLocally(
                repairModelSupport.requireRepair(current.repairId()),
                current.equipmentNames(),
                current.equipmentQuantities());
            reconciliations.confirmed(
                work,
                Map.of(
                    "repairId", current.repairId().toString(),
                    "accountingMode", current.furnitureAccountingMode().name(),
                    "createdDecisionCount", created));
          });
      return;
    }
    List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims =
        dependencies.unresolvedFurnitureCustody(plan.ownerType(), plan.ownerId());
    // Property disposition admission is another remote warehouse-service read. It is deliberately
    // completed before final local locks and before the local-only materializer below.
    warehouseLifecycle.requireOutgoing(plan.warehouseId());
    transactions.executeWithoutResult(
        status -> {
          FurnitureCustodyPlan current = prepareFurnitureCustodyPlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Furniture-custody reconciliation changed before finalization");
          }
          validateFurnitureCustodyClaims(current, claims);
          int created = propertyDispositions.materializeFurnitureCustodyLocally(
              repairModelSupport.requireRepair(current.repairId()), current.equipmentNames(), claims);
          reconciliations.confirmed(
              work,
              Map.of(
                  "repairId", current.repairId().toString(),
                  "ownerType", current.ownerType(),
                  "ownerId", current.ownerId().toString(),
                  "claimCount", claims.size(),
                  "createdDecisionCount", created));
        });
  }

  private FurnitureCustodyPlan prepareFurnitureCustodyPlan(
      MaintenanceReconciliationStore.WorkItem work) {
    MaintenanceRepair repair = taskBoardSupport.requireWorkRepair(work);
    if (repair.getAcceptanceState() != RepairAcceptanceState.PENDING) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Furniture custody can be materialized only after repair completion");
    }
    return new FurnitureCustodyPlan(
        repair.getId(),
        repair.getWarehouseId(),
        repair.getRentalItemId(),
        eventPayloadSupport.ownerType(repair),
        UUID.fromString(eventPayloadSupport.ownerId(repair)),
        repair.getFurnitureAccountingMode(),
        Map.copyOf(estimateSupport.furnitureEquipmentNames(repair)),
        estimateSupport.furnitureQuantities(repair).stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                FurnitureQuantity::equipmentId, FurnitureQuantity::quantity)));
  }

  private static void validateFurnitureCustodyClaims(
      FurnitureCustodyPlan plan,
      List<MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim> claims) {
    if (claims == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service did not return furniture custody truth");
    }
    for (MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim claim : claims) {
      if (!plan.warehouseId().equals(claim.warehouseId())
          || !plan.rentalItemId().equals(claim.rentalItemId())
          || !plan.ownerType().equals(claim.ownerType())
          || !plan.ownerId().equals(claim.ownerId())) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Asset-service returned furniture custody for another repair asset");
      }
    }
  }

  void reconcileEmptyEstimateClaim(MaintenanceReconciliationStore.WorkItem work) {
    EmptyEstimatePlan plan = commandSupport.requireReconciliationResult(
        transactions.execute(status -> prepareEmptyEstimatePlan(work)));
    MaintenanceDependencyGateway.LeaseSnapshot lease = dependencies.acquireLease(
        commandSupport.derived(work.idempotencyKey(), "acquire"),
        plan.rentalItemId(),
        plan.rentalItemVersion(),
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
    MaintenanceDependencyGateway.AssetSnapshot asset = dependencies.fencedStatus(
        commandSupport.derived(work.idempotencyKey(), "status"),
        plan.rentalItemId(),
        plan.warehouseId(),
        plan.rentalItemVersion(),
        lease.leaseId(),
        lease.fencingToken(),
        plan.ownerType(),
        plan.ownerId(),
        "EMPTY_ESTIMATE_TO_FREE",
        false);
    validateEstimateAssetTruth(plan, asset);
    dependencies.releaseLease(
        commandSupport.derived(work.idempotencyKey(), "release"),
        lease.leaseId(),
        lease.version(),
        lease.fencingToken(),
        plan.ownerType(),
        plan.ownerId());
    MaintenanceDependencyGateway.LeaseSnapshot finalLease = lease;
    transactions.executeWithoutResult(
        status -> {
          EmptyEstimatePlan current = prepareEmptyEstimatePlan(work);
          if (!plan.equals(current)) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "Empty-estimate reconciliation changed before finalization");
          }
          validateEstimateAssetTruth(current, asset);
          reconciliations.confirmed(
              work,
              Map.of(
                  "estimateId", current.estimateId().toString(),
                  "rentalItemVersion", asset.version(),
                  "leaseReleased", true,
                  "leaseId", finalLease.leaseId().toString()));
        });
  }

  private EmptyEstimatePlan prepareEmptyEstimatePlan(
      MaintenanceReconciliationStore.WorkItem work) {
    UUID estimateId = commandSupport.uuidField(work.payload(), "estimateId");
    MaintenanceEstimate estimate = estimateModelSupport.requireEstimate(estimateId);
    if (estimate.getState() != EstimateState.COMPLETED || estimate.getRepairId() != null) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Estimate is no longer an empty completed estimate");
    }
    return new EmptyEstimatePlan(
        estimate.getId(),
        estimate.getWarehouseId(),
        estimate.getRentalItemId(),
        estimate.getRentalItemVersionSnapshot(),
        "MAINTENANCE_ESTIMATE",
        estimate.getId().toString());
  }

  private static void validateEstimateAssetTruth(
      EmptyEstimatePlan plan, MaintenanceDependencyGateway.AssetSnapshot asset) {
    if (asset == null
        || !plan.rentalItemId().equals(asset.rentalItemId())
        || !plan.warehouseId().equals(asset.warehouseId())
        || asset.version() <= plan.rentalItemVersion()) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service fenced truth does not advance the expected rental item");
    }
  }
}
