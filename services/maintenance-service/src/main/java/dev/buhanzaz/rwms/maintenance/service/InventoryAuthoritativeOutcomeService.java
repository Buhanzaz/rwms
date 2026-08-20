package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcome;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeTarget;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Authoritative latest-inventory maintenance workflow shared by work and FREE outcomes.
 *
 * <p>Every remote attempt is committed before its call, all calls run without a database
 * transaction, and final local supersession plus source or compatibility-receipt binding is atomic.
 */
@Service
public class InventoryAuthoritativeOutcomeService {
  private final InventoryPublicationTransactionBoundary transactions;
  private final InventoryAuthoritativeOutcomeStore store;
  private final InventoryAuthoritativeOutcomeRemoteGateway remote;
  private final InventoryAuthoritativeLocalSupersession localSupersession;
  private final InventoryPublicationSourceLifecycle sourceLifecycle;
  private final InventoryPublicationPlanValidation planValidation;
  private final InventoryPublicationPlanMaterialization planMaterialization;
  private final InventoryPublicationRepairMaterialization repairMaterialization;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceRepairRepository repairs;

  InventoryAuthoritativeOutcomeService(
      InventoryPublicationTransactionBoundary transactions,
      InventoryAuthoritativeOutcomeStore store,
      InventoryAuthoritativeOutcomeRemoteGateway remote,
      InventoryAuthoritativeLocalSupersession localSupersession,
      InventoryPublicationSourceLifecycle sourceLifecycle,
      InventoryPublicationPlanValidation planValidation,
      InventoryPublicationPlanMaterialization planMaterialization,
      InventoryPublicationRepairMaterialization repairMaterialization,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceRepairRepository repairs) {
    this.transactions = transactions;
    this.store = store;
    this.remote = remote;
    this.localSupersession = localSupersession;
    this.sourceLifecycle = sourceLifecycle;
    this.planValidation = planValidation;
    this.planMaterialization = planMaterialization;
    this.repairMaterialization = repairMaterialization;
    this.warehouseLifecycle = warehouseLifecycle;
    this.repairs = repairs;
  }

  /** Applies one full frozen repair/capital-repair plan after replacing all predecessors. */
  public InventoryPublicationWorkflowResult applyWork(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request) {
    transactions.requireNoCallerTransaction("apply an authoritative inventory work outcome");
    requireIdentity(inventoryId, findingId, idempotencyKey);
    InventoryPublicationFindingInput finding = request.finding(findingId);
    InventoryPublicationValidatedPlan publication =
        planValidation.validatePublication(request.warehouseId(), finding);
    AuthoritativePreparation preparation = transactions.inNewTransaction(
        () -> store.prepareWork(
            inventoryId, findingId, idempotencyKey, request, publication));
    if (preparation.replayed()) {
      return new InventoryPublicationWorkflowResult(
          store.read(preparation.replaySnapshot(), InventoryPublicationApplyResult.class), true);
    }

    settleRemoteEffects(preparation.sourceId());
    transactions.inNewTransaction(() -> {
      store.markRemoteEffectsSettled(preparation.sourceId(), preparation.requestSha256());
      return Boolean.TRUE;
    });
    if (preparation.replaceAppliedReplay()) {
      return replaceAppliedLegacyReplay(preparation, idempotencyKey, request, finding, publication);
    }
    UUID targetRepairId = ensureWorkTarget(preparation, request, finding, publication);
    return transactions.inNewTransaction(
        () -> finalizeWork(
            preparation,
            idempotencyKey,
            request,
            finding,
            publication,
            targetRepairId));
  }

  /** Applies a FREE result and proves that no active maintenance target remains. */
  public InventoryNoWorkOutcomeResult applyNoWork(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryNoWorkOutcomeRequest request) {
    transactions.requireNoCallerTransaction("apply an authoritative inventory no-work outcome");
    requireIdentity(inventoryId, findingId, idempotencyKey);
    AuthoritativePreparation preparation = transactions.inNewTransaction(
        () -> store.prepareNoWork(inventoryId, findingId, idempotencyKey, request));
    if (preparation.replayed()) {
      InventoryNoWorkOutcomeResult stored =
          store.read(preparation.replaySnapshot(), InventoryNoWorkOutcomeResult.class);
      return new InventoryNoWorkOutcomeResult(
          stored.inventoryId(),
          stored.findingId(),
          stored.assetId(),
          stored.supersededEstimateIds(),
          stored.supersededRepairIds(),
          stored.cancelledExternalTaskIds(),
          stored.cancelledDriverTaskIds(),
          stored.releasedLeaseIds(),
          true);
    }
    settleRemoteEffects(preparation.sourceId());
    transactions.inNewTransaction(() -> {
      store.markRemoteEffectsSettled(preparation.sourceId(), preparation.requestSha256());
      return Boolean.TRUE;
    });
    return transactions.inNewTransaction(
        () -> finalizeNoWork(preparation, idempotencyKey, inventoryId, findingId, request));
  }

  private void settleRemoteEffects(InventoryPublicationSourceId sourceId) {
    for (UUID targetId : transactions.inNewTransaction(() -> store.targetIds(sourceId))) {
      settleTask(targetId, sourceId);
      settleDriver(targetId, sourceId);
      settleLease(targetId, sourceId);
    }
  }

  private void settleTask(UUID targetId, InventoryPublicationSourceId sourceId) {
    InventoryAuthoritativeOutcomeTarget target =
        transactions.inNewTransaction(() -> store.target(targetId));
    if (target.getTaskExternalId() == null || target.getTaskOutcome() != null) return;
    InventoryAuthoritativeOutcomeRemoteGateway.TaskCancellation current = remote.task(target);
    if (InventoryAuthoritativeOutcomeRemoteGateway.terminalTask(current.outcome())) {
      transactions.inNewTransaction(() -> {
        store.recordTask(targetId, current.outcome(), current.taskVersion());
        return Boolean.TRUE;
      });
      return;
    }
    InventoryAuthoritativeOutcomeTarget attempted = transactions.inNewTransaction(
        () -> store.beginTask(targetId, current.taskVersion()));
    InventoryAuthoritativeOutcomeRemoteGateway.TaskCancellation cancelled = remote.cancelTask(
        attempted, stableKey("cancel-task", sourceId, attempted.getTargetId()));
    transactions.inNewTransaction(() -> {
      store.recordTask(targetId, cancelled.outcome(), cancelled.taskVersion());
      return Boolean.TRUE;
    });
  }

  private void settleDriver(UUID targetId, InventoryPublicationSourceId sourceId) {
    InventoryAuthoritativeOutcomeTarget target =
        transactions.inNewTransaction(() -> store.target(targetId));
    if (target.getDriverKind() == null || target.getDriverOutcome() != null) return;
    InventoryAuthoritativeOutcomeTarget attempted =
        transactions.inNewTransaction(() -> store.beginDriver(targetId));
    InventoryAuthoritativeOutcomeRemoteGateway.DriverCompensation truth =
        remote.compensateDriver(
            attempted, stableKey("cancel-driver", sourceId, attempted.getTargetId()));
    transactions.inNewTransaction(() -> {
      store.recordDriver(
          targetId,
          truth.outcome(),
          truth.driverTaskId(),
          truth.repairPlaceAllocationId(),
          truth.repairPlaceAllocationVersion());
      return Boolean.TRUE;
    });
  }

  private void settleLease(UUID targetId, InventoryPublicationSourceId sourceId) {
    InventoryAuthoritativeOutcomeTarget target =
        transactions.inNewTransaction(() -> store.target(targetId));
    if (target.getLeaseId() == null || target.isLeaseReleased()) return;
    InventoryAuthoritativeOutcomeTarget attempted =
        transactions.inNewTransaction(() -> store.beginLease(targetId));
    remote.releaseLease(
        attempted, stableKey("release-lease", sourceId, attempted.getTargetId()));
    transactions.inNewTransaction(() -> {
      store.recordLeaseReleased(targetId);
      return Boolean.TRUE;
    });
  }

  /**
   * Replaces a repair that an already-APPLIED coordinator adopted from a pre-V45 publication.
   * Repair creation, local supersession and the new receipt binding commit atomically while the
   * immutable legacy outcome and its original receipt remain unchanged.
   */
  private InventoryPublicationWorkflowResult replaceAppliedLegacyReplay(
      AuthoritativePreparation preparation,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication) {
    InventoryPublicationRepairPlan plan = planMaterialization.fullRepairPlan(
        preparation.sourceId(), publication.snapshot(), publication.sourceMedia());
    List<InventoryPlanStageSnapshot> routingStages = plan.allocations().stream()
        .filter(value -> !value.lines().isEmpty())
        .map(InventoryPublicationPublishedStage::stage)
        .toList();
    warehouseLifecycle.requireIncoming(request.warehouseId());
    planValidation.requireWarehouseRoutingReady(request.warehouseId(), routingStages);
    return transactions.inNewTransaction(() -> {
      InventoryAuthoritativeOutcome outcome =
          store.outcomeForUpdate(preparation.sourceId(), preparation.requestSha256());
      sourceLifecycle.requireAuthoritativeRegistration(preparation.sourceId());
      AuthoritativeReplacementReceipt replacement =
          store.replacementReceipt(outcome, preparation.requestSha256());
      UUID currentRepairId;
      InventoryPublicationWorkflowResult result;
      if (replacement != null) {
        requireCurrentRepair(replacement.repairId(), request, finding);
        currentRepairId = replacement.repairId();
        result = new InventoryPublicationWorkflowResult(
            store.read(
                replacement.responseSnapshot(), InventoryPublicationApplyResult.class),
            true);
      } else {
        InventoryPublicationCreatedTarget created = repairMaterialization.createRepair(
            preparation.sourceId(),
            request.warehouseId(),
            finding,
            request.authoritativeAssetVersion(),
            plan,
            false,
            request.warehouseId(),
            routingStages,
            idempotencyKey);
        currentRepairId = created.repairId();
        result = sourceLifecycle.authoritativeReplacement(sourceWrite(
            preparation,
            idempotencyKey,
            request,
            finding,
            publication,
            currentRepairId));
      }
      InventoryAuthoritativeOutcome replacementView =
          store.historicalReplacementView(outcome, currentRepairId);
      List<InventoryAuthoritativeOutcomeTarget> targetRows =
          store.targetsForUpdate(preparation.sourceId());
      localSupersession.apply(replacementView, targetRows);
      repairMaterialization.enqueue(
          currentRepairId,
          stableKey(
              "queue-repair-legacy-replacement", preparation.sourceId(), currentRepairId));
      reassertWorkTarget(preparation, idempotencyKey, currentRepairId);
      store.completeReceipt(
          preparation.sourceId(),
          preparation.requestSha256(),
          idempotencyKey,
          store.write(result.response()));
      return result;
    });
  }

  /** Locks and validates a receipt- or saga-bound current repair before preserving it. */
  private MaintenanceRepair requireCurrentRepair(
      UUID repairId,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding) {
    MaintenanceRepair repair = repairs.findAllByIdForUpdate(List.of(repairId))
        .stream()
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException(
            "Recovered inventory replacement target not found"));
    if (!finding.assetId().equals(repair.getRentalItemId())
        || !request.warehouseId().equals(repair.getWarehouseId())
        || repair.getExecutionState() == RepairExecutionState.CANCELLED
        || repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw InventoryPublicationPlanValidation.conflict(
          "Recovered inventory replacement target is not a non-terminal exact-source repair");
    }
    return repair;
  }

  private UUID ensureWorkTarget(
      AuthoritativePreparation preparation,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication) {
    InventoryAuthoritativeOutcome outcome = transactions.inNewTransaction(
        () -> store.outcomeForUpdate(preparation.sourceId(), preparation.requestSha256()));
    if (outcome.getTargetRepairId() != null) return outcome.getTargetRepairId();
    if (preparation.adoptedRepairId() != null) {
      transactions.inNewTransaction(() -> {
        MaintenanceRepair repair =
            requireCurrentRepair(preparation.adoptedRepairId(), request, finding);
        store.attachWorkTarget(
            preparation.sourceId(), preparation.requestSha256(), repair.getId());
        return Boolean.TRUE;
      });
      return preparation.adoptedRepairId();
    }

    InventoryPublicationRepairPlan plan = planMaterialization.fullRepairPlan(
        preparation.sourceId(), publication.snapshot(), publication.sourceMedia());
    List<InventoryPlanStageSnapshot> routingStages = plan.allocations().stream()
        .filter(value -> !value.lines().isEmpty())
        .map(InventoryPublicationPublishedStage::stage)
        .toList();
    warehouseLifecycle.requireIncoming(request.warehouseId());
    planValidation.requireWarehouseRoutingReady(request.warehouseId(), routingStages);
    return transactions.inNewTransaction(() -> {
      InventoryAuthoritativeOutcome locked =
          store.outcomeForUpdate(preparation.sourceId(), preparation.requestSha256());
      if (locked.getTargetRepairId() != null) return locked.getTargetRepairId();
      InventoryPublicationCreatedTarget created = repairMaterialization.createRepair(
          preparation.sourceId(),
          request.warehouseId(),
          finding,
          request.authoritativeAssetVersion(),
          plan,
          false,
          request.warehouseId(),
          routingStages,
          stableKey("queue-repair", preparation.sourceId(), finding.assetId()));
      store.attachWorkTarget(
          preparation.sourceId(), preparation.requestSha256(), created.repairId());
      return created.repairId();
    });
  }

  /** Builds one full-plan repair result for source persistence or receipt-only compatibility. */
  private static InventoryPublicationSourceWrite sourceWrite(
      AuthoritativePreparation preparation,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication,
      UUID targetRepairId) {
    InventoryPublicationSupersededTarget selected =
        request.strategy() == InventoryPublicationStrategy.CREATE
            ? null
            : new InventoryPublicationSupersededTarget(
                request.selectedTargetKind(), request.selectedTargetId());
    return new InventoryPublicationSourceWrite(
        preparation.sourceId(),
        request,
        finding,
        publication,
        selected,
        InventoryPublicationOutcome.CREATED,
        null,
        null,
        InventoryPublicationPlanMaterialization.fullDelta(publication.snapshot()),
        new InventoryPublicationCreatedTarget(
            InventoryPublicationTargetKind.REPAIR,
            targetRepairId,
            null,
            targetRepairId),
        preparation.requestSha256(),
        idempotencyKey,
        null,
        null);
  }

  private InventoryPublicationWorkflowResult finalizeWork(
      AuthoritativePreparation preparation,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication,
      UUID targetRepairId) {
    sourceLifecycle.requireAuthoritativeRegistration(preparation.sourceId());
    InventoryAuthoritativeOutcome outcome =
        store.outcomeForUpdate(preparation.sourceId(), preparation.requestSha256());
    if (!targetRepairId.equals(outcome.getTargetRepairId())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Authoritative inventory work target changed before finalization");
    }
    List<InventoryAuthoritativeOutcomeTarget> targetRows =
        store.targetsForUpdate(preparation.sourceId());
    localSupersession.apply(outcome, targetRows);
    InventoryPublicationSource existing =
        sourceLifecycle.requireAuthoritativeReplayOrNull(
            preparation.sourceId(),
            preparation.requestSha256(),
            preparation.historicalReplay());
    InventoryPublicationSourceWrite sourceWrite = sourceWrite(
        preparation, idempotencyKey, request, finding, publication, targetRepairId);
    if (existing != null && preparation.historicalReplay()) {
      InventoryPublicationWorkflowResult result = "APPLIED".equals(outcome.getPhase())
          ? new InventoryPublicationWorkflowResult(
              store.read(
                  outcome.getResponseSnapshot(), InventoryPublicationApplyResult.class),
              true)
          : sourceLifecycle.authoritativeReplacement(sourceWrite);
      enqueueWorkTarget(preparation, idempotencyKey, finding.assetId(), targetRepairId);
      if ("APPLIED".equals(outcome.getPhase())) {
        store.completeReceipt(
            preparation.sourceId(),
            preparation.requestSha256(),
            idempotencyKey,
            store.write(result.response()));
      } else {
        store.complete(
            preparation.sourceId(),
            preparation.requestSha256(),
            idempotencyKey,
            store.write(result.response()));
      }
      return result;
    }
    if (existing != null) {
      InventoryPublicationWorkflowResult replay = preparation.reassertion()
          ? new InventoryPublicationWorkflowResult(
              store.read(
                  outcome.getResponseSnapshot(), InventoryPublicationApplyResult.class),
              true)
          : sourceLifecycle.replay(existing);
      enqueueWorkTarget(preparation, idempotencyKey, finding.assetId(), targetRepairId);
      if (preparation.reassertion()) {
        store.completeReceipt(
            preparation.sourceId(),
            preparation.requestSha256(),
            idempotencyKey,
            store.write(replay.response()));
      } else {
        store.complete(
            preparation.sourceId(),
            preparation.requestSha256(),
            idempotencyKey,
            store.write(replay.response()));
      }
      return replay;
    }
    InventoryPublicationWorkflowResult result = sourceLifecycle.persist(sourceWrite);
    enqueueWorkTarget(preparation, idempotencyKey, finding.assetId(), targetRepairId);
    store.complete(
        preparation.sourceId(),
        preparation.requestSha256(),
        idempotencyKey,
        store.write(result.response()));
    return result;
  }

  private void enqueueWorkTarget(
      AuthoritativePreparation preparation,
      UUID idempotencyKey,
      UUID assetId,
      UUID targetRepairId) {
    repairMaterialization.enqueue(
        targetRepairId, stableKey("queue-repair", preparation.sourceId(), assetId));
    reassertWorkTarget(preparation, idempotencyKey, targetRepairId);
  }

  private void reassertWorkTarget(
      AuthoritativePreparation preparation, UUID idempotencyKey, UUID targetRepairId) {
    repairMaterialization.reassertActiveTarget(
        targetRepairId,
        stableKey(
            "reassert-repair-status:" + idempotencyKey,
            preparation.sourceId(),
            targetRepairId),
        stableKey(
            "reassert-repair-task:" + idempotencyKey,
            preparation.sourceId(),
            targetRepairId),
        stableKey(
            "reassert-repair-driver:" + idempotencyKey,
            preparation.sourceId(),
            targetRepairId));
  }

  private InventoryNoWorkOutcomeResult finalizeNoWork(
      AuthoritativePreparation preparation,
      UUID idempotencyKey,
      UUID inventoryId,
      UUID findingId,
      InventoryNoWorkOutcomeRequest request) {
    InventoryAuthoritativeOutcome outcome =
        store.outcomeForUpdate(preparation.sourceId(), preparation.requestSha256());
    List<InventoryAuthoritativeOutcomeTarget> targetRows =
        store.targetsForUpdate(preparation.sourceId());
    AuthoritativeSupersessionResult superseded = localSupersession.apply(outcome, targetRows);
    InventoryNoWorkOutcomeResult response = new InventoryNoWorkOutcomeResult(
        inventoryId,
        findingId,
        request.assetId(),
        superseded.supersededEstimateIds(),
        superseded.supersededRepairIds(),
        superseded.cancelledExternalTaskIds(),
        superseded.cancelledDriverTaskIds(),
        superseded.releasedLeaseIds(),
        false);
    if (preparation.reassertion()) {
      store.completeReceipt(
          preparation.sourceId(),
          preparation.requestSha256(),
          preparation.receiptRequestSha256(),
          idempotencyKey,
          store.write(response));
    } else {
      store.complete(
          preparation.sourceId(),
          preparation.requestSha256(),
          preparation.receiptRequestSha256(),
          idempotencyKey,
          store.write(response));
    }
    return response;
  }

  private static void requireIdentity(UUID inventoryId, UUID findingId, UUID idempotencyKey) {
    if (inventoryId == null || findingId == null || idempotencyKey == null) {
      throw InventoryPublicationPlanValidation.invalid(
          "Inventory outcome identity and Idempotency-Key are required");
    }
  }

  private static UUID stableKey(
      String operation, InventoryPublicationSourceId sourceId, UUID targetId) {
    return UUID.nameUUIDFromBytes(
        (operation
                + ":"
                + sourceId.getInventoryId()
                + ":"
                + sourceId.getFinalPlanVersion()
                + ":"
                + sourceId.getFindingId()
                + ":"
                + targetId)
            .getBytes(StandardCharsets.UTF_8));
  }
}
