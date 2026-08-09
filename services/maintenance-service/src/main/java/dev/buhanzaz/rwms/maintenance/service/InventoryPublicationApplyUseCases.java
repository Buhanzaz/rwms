package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Coordinates normal inventory-publication application after all immutable source and target
 * checks have been fenced. Remote admission and routing preflights deliberately run outside the
 * local REQUIRES_NEW retry segment.
 */
@Component
final class InventoryPublicationApplyUseCases {
  private static final Set<String> REPAIR_QUEUE_SOURCES =
      Set.of("FREE", "WAREHOUSE", "OWN_NEEDS", "AFTER_RENT");

  private final InventoryPublicationTransactionBoundary transactions;
  private final InventoryPublicationPrestartReplacementUseCases prestartReplacement;
  private final InventoryPublicationSourceLifecycle sourceLifecycle;
  private final InventoryPublicationPlanValidation planValidation;
  private final InventoryPublicationPlanMaterialization planMaterialization;
  private final InventoryPublicationTargetSelection targetSelection;
  private final InventoryPublicationEstimateMaterialization estimateMaterialization;
  private final InventoryPublicationRepairMaterialization repairMaterialization;
  private final RentalItemFactProjectionRepository rentalItems;
  private final MaintenanceEstimateRepository estimates;
  private final MaintenanceRepairRepository repairs;
  private final WarehouseLifecycleOperations warehouseLifecycle;

  InventoryPublicationApplyUseCases(
      InventoryPublicationTransactionBoundary transactions,
      InventoryPublicationPrestartReplacementUseCases prestartReplacement,
      InventoryPublicationSourceLifecycle sourceLifecycle,
      InventoryPublicationPlanValidation planValidation,
      InventoryPublicationPlanMaterialization planMaterialization,
      InventoryPublicationTargetSelection targetSelection,
      InventoryPublicationEstimateMaterialization estimateMaterialization,
      InventoryPublicationRepairMaterialization repairMaterialization,
      RentalItemFactProjectionRepository rentalItems,
      MaintenanceEstimateRepository estimates,
      MaintenanceRepairRepository repairs,
      WarehouseLifecycleOperations warehouseLifecycle) {
    this.transactions = transactions;
    this.prestartReplacement = prestartReplacement;
    this.sourceLifecycle = sourceLifecycle;
    this.planValidation = planValidation;
    this.planMaterialization = planMaterialization;
    this.targetSelection = targetSelection;
    this.estimateMaterialization = estimateMaterialization;
    this.repairMaterialization = repairMaterialization;
    this.rentalItems = rentalItems;
    this.estimates = estimates;
    this.repairs = repairs;
    this.warehouseLifecycle = warehouseLifecycle;
  }

  InventoryPublicationWorkflowResult apply(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request) {
    transactions.requireNoCallerTransaction("apply an inventory publication");
    if (prestartReplacementRequest(request)) {
      InventoryPublicationPrestartExecution prestart =
          prestartReplacement.execute(inventoryId, findingId, idempotencyKey, request);
      if (prestart.applicable()) {
        return prestart.result();
      }
    }
    return applyLocallyWithRemotePreflight(inventoryId, findingId, idempotencyKey, request);
  }

  private InventoryPublicationWorkflowResult applyLocallyWithRemotePreflight(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request) {
    UUID incomingAdmissionWarehouseId = null;
    List<InventoryPlanStageSnapshot> routingPreflightStages = null;
    while (true) {
      UUID admittedWarehouseId = incomingAdmissionWarehouseId;
      List<InventoryPlanStageSnapshot> preflightedStages = routingPreflightStages;
      try {
        return transactions.inNewTransaction(
            () -> applyLocally(
                inventoryId,
                findingId,
                idempotencyKey,
                request,
                admittedWarehouseId,
                preflightedStages));
      } catch (InventoryPublicationPlanValidation.RemotePreflightRequired requirement) {
        switch (requirement.kind()) {
          case INCOMING -> {
            if (requirement.warehouseId().equals(incomingAdmissionWarehouseId)) {
              throw new IllegalStateException("Inventory publication repeated warehouse admission");
            }
            warehouseLifecycle.requireIncoming(requirement.warehouseId());
            incomingAdmissionWarehouseId = requirement.warehouseId();
          }
          case ROUTING -> {
            if (requirement.stages().equals(routingPreflightStages)) {
              throw new IllegalStateException("Inventory publication repeated routing preflight");
            }
            planValidation.requireWarehouseRoutingReady(requirement.warehouseId(), requirement.stages());
            routingPreflightStages = requirement.stages();
          }
        }
      }
    }
  }

  private InventoryPublicationWorkflowResult applyLocally(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      UUID incomingAdmissionWarehouseId,
      List<InventoryPlanStageSnapshot> routingPreflightStages) {
    if (inventoryId == null || findingId == null || idempotencyKey == null) {
      throw InventoryPublicationPlanValidation.invalid(
          "Inventory publication identity and idempotency key are required");
    }
    InventoryPublicationFindingInput finding = request.finding(findingId);
    InventoryPublicationValidatedPlan publication =
        planValidation.validatePublication(request.warehouseId(), finding);
    String requestSha256 = sourceLifecycle.requestSha256(inventoryId, findingId, request);
    InventoryPublicationSourceId sourceId = sourceLifecycle.sourceId(inventoryId, request, findingId);

    InventoryPublicationRegisteredSource registered =
        sourceLifecycle.registerAndLock(sourceId, requestSha256, true);
    if (registered.replay() != null) {
      return sourceLifecycle.replay(registered.replay());
    }

    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    assertCurrentAsset(request.warehouseId(), finding, asset);
    InventoryPublicationTargetKind targetKind = targetKind(asset);
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(finding.assetId());
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    InventoryPublicationStrategyResolution strategy = targetSelection.applyStrategy(
        request, finding, lockedEstimates, lockedRepairs, request.warehouseId());
    boolean successorMayUseActiveRepairAsset = strategy.predecessor() != null
        && Set.of("REPAIR", "CAPITAL_REPAIR").contains(asset.getAssetStatus());
    if (targetKind == InventoryPublicationTargetKind.REPAIR
        && !REPAIR_QUEUE_SOURCES.contains(asset.getAssetStatus())
        && !successorMayUseActiveRepairAsset) {
      throw InventoryPublicationPlanValidation.conflict(
          "Current rental-item status is unsafe for maintenance queueing");
    }

    InventoryPublicationOutcome outcome;
    InventoryPublicationDelta delta;
    InventoryPublicationCreatedTarget created;
    if (strategy.predecessor() == null) {
      outcome = InventoryPublicationOutcome.CREATED;
      delta = InventoryPublicationPlanMaterialization.fullDelta(publication.snapshot());
      created = targetKind == InventoryPublicationTargetKind.ESTIMATE
          ? estimateMaterialization.createEstimate(
              sourceId,
              request.warehouseId(),
              finding,
              publication,
              incomingAdmissionWarehouseId,
              planMaterialization)
          : repairMaterialization.createRepair(
              sourceId,
              request.warehouseId(),
              finding,
              planMaterialization.fullRepairPlan(
                  sourceId, publication.snapshot(), publication.sourceMedia()),
              true,
              incomingAdmissionWarehouseId,
              routingPreflightStages,
              sourceLifecycle.stableKey("inventory-publication-queue-repair", sourceId));
    } else {
      if (targetKind != InventoryPublicationTargetKind.REPAIR) {
        throw InventoryPublicationPlanValidation.conflict(
            "A started maintenance repair can receive only a repair successor while it remains active");
      }
      InventoryPublicationDeltaRepairPlan successorPlan = planMaterialization.successorPlan(
          sourceId, publication.snapshot(), publication.sourceMedia(), strategy.predecessor());
      delta = successorPlan.delta();
      if (successorPlan.plan().lines().isEmpty()) {
        outcome = InventoryPublicationOutcome.MATCHED;
        created = InventoryPublicationCreatedTarget.none();
      } else {
        outcome = InventoryPublicationOutcome.SUCCESSOR;
        created = repairMaterialization.createRepair(
            sourceId,
            request.warehouseId(),
            finding,
            successorPlan.plan(),
            false,
            incomingAdmissionWarehouseId,
            routingPreflightStages,
            sourceLifecycle.stableKey("inventory-publication-queue-repair", sourceId));
      }
    }
    return sourceLifecycle.persist(new InventoryPublicationSourceWrite(
        sourceId,
        request,
        finding,
        publication,
        strategy.superseded(),
        outcome,
        strategy.predecessor(),
        strategy.predecessor() == null ? null : strategy.predecessor().getId(),
        delta,
        created,
        requestSha256,
        idempotencyKey,
        strategy.terminalProof(),
        null));
  }

  private RentalItemFactProjection requireAssetForUpdate(UUID assetId) {
    return rentalItems.findByIdForUpdate(assetId).orElseThrow(
        () -> new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE, "Current rental-item fact is unavailable"));
  }

  private static boolean prestartReplacementRequest(InventoryPublicationApplyRequest request) {
    return request != null
        && request.selectedTargetKind() == InventoryPublicationTargetKind.REPAIR
        && (request.strategy() == InventoryPublicationStrategy.REPLACE
            || request.strategy() == InventoryPublicationStrategy.MERGE);
  }

  private static void assertCurrentAsset(
      UUID warehouseId, InventoryPublicationFindingInput finding, RentalItemFactProjection asset) {
    if (!warehouseId.equals(asset.getWarehouseId())
        || finding.assetVersion() != asset.getAggregateVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Current rental-item warehouse/version differs from completed inventory evidence");
    }
  }

  private static InventoryPublicationTargetKind targetKind(RentalItemFactProjection asset) {
    return "AFTER_RENT".equals(asset.getAssetStatus())
        ? InventoryPublicationTargetKind.ESTIMATE
        : InventoryPublicationTargetKind.REPAIR;
  }
}
