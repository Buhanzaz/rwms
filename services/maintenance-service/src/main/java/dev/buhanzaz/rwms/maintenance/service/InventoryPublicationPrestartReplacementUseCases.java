package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationPrestartReplacement;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationPrestartReplacementRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Durable pre-start replacement saga for a queued repair. Every local phase is REQUIRES_NEW and
 * remote compensation or lease release runs outside those locks so a lost callback can be replayed
 * from the persisted intent.
 */
@Component
final class InventoryPublicationPrestartReplacementUseCases {
  private final InventoryPublicationTransactionBoundary transactions;
  private final InventoryPublicationSourceLifecycle sourceLifecycle;
  private final InventoryPublicationPlanValidation planValidation;
  private final InventoryPublicationPlanMaterialization planMaterialization;
  private final InventoryPublicationTargetSelection targetSelection;
  private final InventoryPublicationRepairMaterialization repairMaterialization;
  private final InventoryPublicationPrestartReplacementRepository prestartReplacements;
  private final InventoryPublicationPrestartReplacementRemoteGateway prestartRemote;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final RepairPlaceService repairPlaces;
  private final RentalItemFactProjectionRepository rentalItems;
  private final MaintenanceEstimateRepository estimates;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;

  InventoryPublicationPrestartReplacementUseCases(
      InventoryPublicationTransactionBoundary transactions,
      InventoryPublicationSourceLifecycle sourceLifecycle,
      InventoryPublicationPlanValidation planValidation,
      InventoryPublicationPlanMaterialization planMaterialization,
      InventoryPublicationTargetSelection targetSelection,
      InventoryPublicationRepairMaterialization repairMaterialization,
      InventoryPublicationPrestartReplacementRepository prestartReplacements,
      InventoryPublicationPrestartReplacementRemoteGateway prestartRemote,
      WarehouseLifecycleOperations warehouseLifecycle,
      RepairPlaceService repairPlaces,
      RentalItemFactProjectionRepository rentalItems,
      MaintenanceEstimateRepository estimates,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages) {
    this.transactions = transactions;
    this.sourceLifecycle = sourceLifecycle;
    this.planValidation = planValidation;
    this.planMaterialization = planMaterialization;
    this.targetSelection = targetSelection;
    this.repairMaterialization = repairMaterialization;
    this.prestartReplacements = prestartReplacements;
    this.prestartRemote = prestartRemote;
    this.warehouseLifecycle = warehouseLifecycle;
    this.repairPlaces = repairPlaces;
    this.rentalItems = rentalItems;
    this.estimates = estimates;
    this.repairs = repairs;
    this.repairStages = repairStages;
  }

  InventoryPublicationPrestartExecution execute(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request) {
    if (inventoryId == null || findingId == null || idempotencyKey == null) {
      throw InventoryPublicationPlanValidation.invalid(
          "Inventory publication identity and idempotency key are required");
    }
    InventoryPublicationFindingInput finding = request.finding(findingId);
    InventoryPublicationValidatedPlan publication =
        planValidation.validatePublication(request.warehouseId(), finding);
    String requestSha256 = sourceLifecycle.requestSha256(inventoryId, findingId, request);
    InventoryPublicationSourceId sourceId = sourceLifecycle.sourceId(inventoryId, request, findingId);
    String requestSnapshot = sourceLifecycle.write(request);

    InventoryPublicationPrestartPreparation preparation;
    try {
      preparation =
          transactions.inNewTransaction(
              () ->
                  preparePrestartReplacement(
                      sourceId,
                      requestSha256,
                      idempotencyKey,
                      requestSnapshot,
                      request,
                      finding));
    } catch (MaintenanceConflictException exception) {
      if (transactions.inNewTransaction(
          () -> sourceLifecycle.sourceBoundToDifferentRequest(sourceId, requestSha256))) {
        throw exception;
      }
      if (transactions.inNewTransaction(
          () -> abortUnattemptedPrestartReplacement(sourceId, requestSha256))) {
        throw exception;
      }
      throw retryablePrestartConflict(exception);
    } catch (RuntimeException exception) {
      if (transactions.inNewTransaction(
          () -> abortUnattemptedPrestartReplacement(sourceId, requestSha256))) {
        throw exception;
      }
      throw retryablePrestartFailure(exception);
    }
    if (preparation.replay() != null) {
      return InventoryPublicationPrestartExecution.handled(
          new InventoryPublicationWorkflowResult(preparation.replay(), true));
    }
    if (!preparation.applicable()) {
      return InventoryPublicationPrestartExecution.notApplicable();
    }

    InventoryPublicationPrestartReplacement intent = preparation.intent();
    if ("APPLIED".equals(intent.getPhase())) {
      return InventoryPublicationPrestartExecution.handled(
          transactions.inNewTransaction(() -> replayPrestartReplacement(sourceId, requestSha256)));
    }
    if ("PREPARED".equals(intent.getPhase())) {
      if (intent.getRemoteAttemptCount() == 0) {
        warehouseLifecycle.requireIncoming(request.warehouseId());
      }
      InventoryPublicationRemoteAttempt remoteAttempt = transactions.inNewTransaction(
          () -> beginPrestartRemoteAttempt(sourceId, requestSha256));
      InventoryPublicationPrestartReplacementRemoteGateway.RemoteCompensation remote;
      try {
        remote = prestartRemote.compensate(
            intent,
            sourceLifecycle.stableKey("inventory-publication-cancel-driver", sourceId),
            sourceLifecycle.stableKey("inventory-publication-cancel-repair-task", sourceId));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
      if (remote.disposition()
          == InventoryPublicationPrestartReplacementRemoteGateway.Disposition
              .NO_EFFECT_VERSION_CONFLICT) {
        if (remoteAttempt.firstAttempt()
            && transactions.inNewTransaction(
                () -> abortFirstNoEffectPrestartReplacement(sourceId, requestSha256))) {
          throw InventoryPublicationPlanValidation.conflict(
              "Task-board version changed before inventory could prove pre-start cancellation; "
                  + "refresh the publication target and retry");
        }
        throw new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board version conflict followed an earlier or concurrent pre-start remote "
                + "attempt; reconcile and retry the same inventory publication");
      }
      if (remote.disposition()
          == InventoryPublicationPrestartReplacementRemoteGateway.Disposition
              .WAIT_FOR_INBOUND_COMPLETION) {
        throw new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Inbound delivery has started after repair-task cancellation; wait for completed "
                + "occupied-place truth before retrying this inventory publication");
      }
      if (remote.disposition()
          == InventoryPublicationPrestartReplacementRemoteGateway.Disposition.REPAIR_WORK_STARTED) {
        try {
          return InventoryPublicationPrestartExecution.handled(
              applyStartedPrestartReplacementWithRemotePreflight(
                  sourceId,
                  requestSha256,
                  idempotencyKey,
                  request,
                  finding,
                  publication,
                  remote));
        } catch (MaintenanceConflictException exception) {
          throw retryablePrestartConflict(exception);
        } catch (MaintenanceDependencyException exception) {
          throw retryablePrestartDependency(exception);
        }
      }
      try {
        intent = transactions.inNewTransaction(
            () -> recordPrestartCompensation(sourceId, requestSha256, remote));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
      if ("PREPARED".equals(intent.getPhase())) {
        return execute(inventoryId, findingId, idempotencyKey, request);
      }
    }

    if ("COMPENSATED".equals(intent.getPhase())) {
      try {
        InventoryPublicationRepairPlan plan = planMaterialization.fullRepairPlan(
            sourceId, publication.snapshot(), publication.sourceMedia());
        warehouseLifecycle.requireIncoming(request.warehouseId());
        planValidation.requireWarehouseRoutingReady(
            request.warehouseId(),
            plan.allocations().stream()
                .filter(allocation -> !allocation.lines().isEmpty())
                .map(InventoryPublicationPublishedStage::stage)
                .toList());
        intent = transactions.inNewTransaction(
            () -> createPrestartReplacementSuccessor(
                sourceId, requestSha256, request, finding, publication, plan));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
      if ("PREPARED".equals(intent.getPhase())) {
        return execute(inventoryId, findingId, idempotencyKey, request);
      }
    }

    if ("SUCCESSOR_CREATED".equals(intent.getPhase())) {
      try {
        prestartRemote.releaseLease(
            intent, sourceLifecycle.stableKey("inventory-publication-release-lease", sourceId));
        intent = transactions.inNewTransaction(
            () -> markPrestartReplacementLeaseReleased(sourceId, requestSha256));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
    }

    if ("LEASE_RELEASED".equals(intent.getPhase())) {
      try {
        return InventoryPublicationPrestartExecution.handled(transactions.inNewTransaction(
            () -> finalizePrestartReplacement(sourceId, requestSha256, request, finding, publication)));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
    }
    if ("APPLIED".equals(intent.getPhase())) {
      return InventoryPublicationPrestartExecution.handled(
          transactions.inNewTransaction(() -> replayPrestartReplacement(sourceId, requestSha256)));
    }
    throw new IllegalStateException("Inventory pre-start replacement has an unsupported phase");
  }

  private InventoryPublicationPrestartPreparation preparePrestartReplacement(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      UUID idempotencyKey,
      String requestSnapshot,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding) {
    InventoryPublicationRegisteredSource registered =
        sourceLifecycle.registerAndLock(sourceId, requestSha256, false);
    if (registered.replay() != null) {
      return InventoryPublicationPrestartPreparation.replay(
          sourceLifecycle.replay(registered.replay()).response());
    }
    InventoryPublicationPrestartReplacement existing =
        prestartReplacements.findByIdForUpdate(sourceId).orElse(null);
    if (existing != null) {
      try {
        existing.requireSameRequest(requestSha256);
      } catch (IllegalArgumentException exception) {
        throw InventoryPublicationPlanValidation.conflict(
            "Completed inventory source is already bound to different publication input");
      }
      return InventoryPublicationPrestartPreparation.intent(existing);
    }

    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    InventoryPublicationAssetFence.requireAuthoritative(request, asset);
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(finding.assetId());
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    MaintenanceRepair repair = lockedRepairs.stream()
        .filter(value -> request.selectedTargetId().equals(value.getId()))
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Selected repair not found"));
    if (!finding.assetId().equals(repair.getRentalItemId())
        || !request.warehouseId().equals(repair.getWarehouseId())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Selected repair does not belong to this inventory asset and warehouse");
    }
    InventoryPublicationPrestartCandidate candidate = targetSelection.prestartCandidate(repair);
    if (candidate == null) return InventoryPublicationPrestartPreparation.notApplicable();
    targetSelection.requireNoOtherActiveTarget(
        InventoryPublicationTargetKind.REPAIR,
        repair.getId(),
        lockedEstimates,
        lockedRepairs,
        request.warehouseId());
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    boolean stageTaskEffect = stages.stream().anyMatch(stage -> stage.getExternalQueueEntryId() != null);
    boolean taskGuardRequired = repair.getTaskBoardVersion() != null;
    if (!taskGuardRequired && stageTaskEffect) {
      throw InventoryPublicationPlanValidation.conflict(
          "Queued repair has task-board state without a repair task version for atomic cancellation");
    }
    InventoryPublicationPrestartReplacement.LeaseIdentity lease = targetSelection.prestartLease(repair);
    InventoryPublicationPrestartReplacement intent = InventoryPublicationPrestartReplacement.prepare(
        sourceId,
        requestSha256,
        idempotencyKey,
        requestSnapshot,
        request.warehouseId(),
        finding.assetId(),
        repair.getId(),
        candidate.mode(),
        candidate.driverKind(),
        repair.getExternalTaskId(),
        taskGuardRequired ? repair.getTaskBoardVersion() : null,
        taskGuardRequired,
        lease);
    return InventoryPublicationPrestartPreparation.intent(prestartReplacements.saveAndFlush(intent));
  }

  private InventoryPublicationRemoteAttempt beginPrestartRemoteAttempt(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    boolean firstAttempt = intent.beginRemoteAttempt();
    InventoryPublicationPrestartReplacement saved = prestartReplacements.saveAndFlush(intent);
    return new InventoryPublicationRemoteAttempt(firstAttempt, saved.getRemoteAttemptCount());
  }

  private boolean abortUnattemptedPrestartReplacement(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    return abortPrestartReplacement(sourceId, requestSha256, false);
  }

  private boolean abortFirstNoEffectPrestartReplacement(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    return abortPrestartReplacement(sourceId, requestSha256, true);
  }

  private boolean abortPrestartReplacement(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      boolean requireFirstNoEffectAttempt) {
    return sourceLifecycle.abortUnpublished(sourceId, requestSha256, () -> {
      InventoryPublicationPrestartReplacement intent =
          prestartReplacements.findByIdForUpdate(sourceId).orElse(null);
      if (intent == null) return true;
      try {
        intent.requireSameRequest(requestSha256);
      } catch (IllegalArgumentException exception) {
        return false;
      }
      boolean eligible = requireFirstNoEffectAttempt
          ? intent.canAbortAfterProvedNoEffect()
          : intent.getRemoteAttemptCount() == 0;
      if (!eligible) return false;
      prestartReplacements.delete(intent);
      prestartReplacements.flush();
      return true;
    });
  }

  private InventoryPublicationPrestartReplacement recordPrestartCompensation(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      InventoryPublicationPrestartReplacementRemoteGateway.RemoteCompensation remote) {
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    MaintenanceRepair predecessor = repairs
        .findAllByIdForUpdate(List.of(intent.getPredecessorRepairId()))
        .stream()
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start predecessor repair not found"));
    if (!intent.isTaskGuardRequired()) {
      boolean stageTaskEffect = repairStages
          .findAllByRepairIdOrderByStageNo(predecessor.getId())
          .stream()
          .anyMatch(stage -> stage.getExternalQueueEntryId() != null);
      if (predecessor.getTaskBoardVersion() != null) {
        intent.requireTaskGuard(predecessor.getTaskBoardVersion());
        return prestartReplacements.saveAndFlush(intent);
      }
      if (stageTaskEffect) {
        throw InventoryPublicationPlanValidation.conflict(
            "Queued repair gained task-board state without a repair task version for atomic cancellation");
      }
    }
    intent.recordCompensation(
        remote.driverOutcome(),
        remote.taskOutcome(),
        remote.occupancyReassignmentRequired(),
        remote.repairPlaceAllocationId(),
        remote.repairPlaceAllocationVersion());
    return prestartReplacements.saveAndFlush(intent);
  }

  private InventoryPublicationPrestartReplacement createPrestartReplacementSuccessor(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication,
      InventoryPublicationRepairPlan plan) {
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    if (!"COMPENSATED".equals(intent.getPhase())) {
      return intent;
    }
    if (!request.warehouseId().equals(intent.getWarehouseId())
        || !finding.assetId().equals(intent.getAssetId())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Stored pre-start replacement intent does not match publication ownership");
    }
    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    InventoryPublicationAssetFence.requireAuthoritative(request, asset);
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(finding.assetId());
    List<MaintenanceRepair> lockedRepairs = repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    MaintenanceRepair predecessor = lockedRepairs.stream()
        .filter(value -> intent.getPredecessorRepairId().equals(value.getId()))
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start predecessor repair not found"));
    targetSelection.requirePrestartCandidate(predecessor, intent);
    if (!intent.isTaskGuardRequired()) {
      boolean stageTaskEffect = repairStages
          .findAllByRepairIdOrderByStageNo(predecessor.getId())
          .stream()
          .anyMatch(stage -> stage.getExternalQueueEntryId() != null);
      if (predecessor.getTaskBoardVersion() != null) {
        intent.requireTaskGuard(predecessor.getTaskBoardVersion());
        return prestartReplacements.saveAndFlush(intent);
      }
      if (stageTaskEffect) {
        throw InventoryPublicationPlanValidation.conflict(
            "Queued repair gained task-board state without a repair task version for atomic cancellation");
      }
    }
    targetSelection.requireNoOtherActiveTarget(
        InventoryPublicationTargetKind.REPAIR,
        predecessor.getId(),
        lockedEstimates,
        lockedRepairs,
        request.warehouseId());
    InventoryPublicationCreatedTarget created = repairMaterialization.createRepair(
        sourceId,
        request.warehouseId(),
        finding,
        request.authoritativeAssetVersion(),
        plan,
        false,
        request.warehouseId(),
        plan.allocations().stream()
            .filter(allocation -> !allocation.lines().isEmpty())
            .map(InventoryPublicationPublishedStage::stage)
            .toList(),
        sourceLifecycle.stableKey("inventory-publication-queue-repair", sourceId));
    if (intent.isOccupancyReassignmentRequired()) {
      repairPlaces.reassignOccupiedForInventoryReplacement(
          request.warehouseId(),
          predecessor.getId(),
          created.repairId(),
          intent.getRepairPlaceAllocationId(),
          intent.getRepairPlaceAllocationVersion());
    } else {
      repairPlaces.requireNoActiveAllocationForInventoryReplacement(
          request.warehouseId(),
          predecessor.getId(),
          intent.getRepairPlaceAllocationId(),
          intent.getRepairPlaceAllocationVersion());
    }
    intent.attachSuccessor(created.repairId());
    return prestartReplacements.saveAndFlush(intent);
  }

  private InventoryPublicationPrestartReplacement markPrestartReplacementLeaseReleased(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    intent.markLeaseReleased();
    return prestartReplacements.saveAndFlush(intent);
  }

  private InventoryPublicationWorkflowResult finalizePrestartReplacement(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication) {
    sourceLifecycle.requireRegisteredRequest(sourceId, requestSha256);
    InventoryPublicationSource replay = sourceLifecycle.requireReplayOrNull(sourceId, requestSha256);
    if (replay != null) return sourceLifecycle.replay(replay);
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    if (!"LEASE_RELEASED".equals(intent.getPhase())) {
      throw new IllegalStateException("Inventory pre-start replacement is not ready to finalize");
    }
    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    InventoryPublicationAssetFence.requireAuthoritative(request, asset);
    List<MaintenanceRepair> lockedRepairs = repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    MaintenanceRepair predecessor = lockedRepairs.stream()
        .filter(value -> intent.getPredecessorRepairId().equals(value.getId()))
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start predecessor repair not found"));
    MaintenanceRepair successor = lockedRepairs.stream()
        .filter(value -> intent.getSuccessorRepairId().equals(value.getId()))
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start successor repair not found"));
    if (successor.getRentalItemVersionSnapshot() > request.authoritativeAssetVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Pre-start replacement successor is newer than the authoritative inventory outcome");
    }
    successor.confirmRentalItemVersion(request.authoritativeAssetVersion());
    targetSelection.requirePrestartCandidate(predecessor, intent);
    if (successor.getExecutionState() != RepairExecutionState.DRAFT
        || !predecessor.getWarehouseId().equals(successor.getWarehouseId())
        || !predecessor.getRentalItemId().equals(successor.getRentalItemId())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Pre-start replacement successor is no longer safe to queue");
    }
    if (intent.isOccupancyReassignmentRequired()) {
      if (!repairPlaces.isOccupied(request.warehouseId(), successor.getId())) {
        throw InventoryPublicationPlanValidation.conflict(
            "Delivered inventory replacement lost its occupied repair-place allocation");
      }
      repairPlaces.requireNoActiveAllocationForInventoryReplacement(
          request.warehouseId(), predecessor.getId(), null, null);
    } else {
      repairPlaces.requireNoActiveAllocationForInventoryReplacement(
          request.warehouseId(),
          predecessor.getId(),
          intent.getRepairPlaceAllocationId(),
          intent.getRepairPlaceAllocationVersion());
    }
    MaintenanceRepair savedPredecessor = targetSelection.finalizePrestartPredecessor(predecessor, intent);
    InventoryPublicationWorkflowResult result = sourceLifecycle.persist(new InventoryPublicationSourceWrite(
        sourceId,
        request,
        finding,
        publication,
        new InventoryPublicationSupersededTarget(
            InventoryPublicationTargetKind.REPAIR, savedPredecessor.getId()),
        InventoryPublicationOutcome.CREATED,
        null,
        null,
        InventoryPublicationPlanMaterialization.fullDelta(publication.snapshot()),
        new InventoryPublicationCreatedTarget(
            InventoryPublicationTargetKind.REPAIR, successor.getId(), null, successor.getId()),
        requestSha256,
        intent.getRequestIdempotencyKey(),
        null,
        null));
    repairMaterialization.enqueue(
        successor.getId(), sourceLifecycle.stableKey("inventory-publication-queue-repair", sourceId));
    intent.markApplied();
    prestartReplacements.saveAndFlush(intent);
    return result;
  }

  private InventoryPublicationWorkflowResult applyStartedPrestartReplacementWithRemotePreflight(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication,
      InventoryPublicationPrestartReplacementRemoteGateway.RemoteCompensation remote) {
    UUID incomingAdmissionWarehouseId = null;
    List<InventoryPlanStageSnapshot> routingPreflightStages = null;
    while (true) {
      UUID admittedWarehouseId = incomingAdmissionWarehouseId;
      List<InventoryPlanStageSnapshot> preflightedStages = routingPreflightStages;
      try {
        return transactions.inNewTransaction(
            () -> applyStartedPrestartReplacement(
                sourceId,
                requestSha256,
                idempotencyKey,
                request,
                finding,
                publication,
                remote,
                admittedWarehouseId,
                preflightedStages));
      } catch (InventoryPublicationPlanValidation.RemotePreflightRequired requirement) {
        switch (requirement.kind()) {
          case INCOMING -> {
            if (requirement.warehouseId().equals(incomingAdmissionWarehouseId)) {
              throw new IllegalStateException(
                  "Started inventory publication repeated warehouse admission");
            }
            warehouseLifecycle.requireIncoming(requirement.warehouseId());
            incomingAdmissionWarehouseId = requirement.warehouseId();
          }
          case ROUTING -> {
            if (requirement.stages().equals(routingPreflightStages)) {
              throw new IllegalStateException("Started inventory publication repeated routing preflight");
            }
            planValidation.requireWarehouseRoutingReady(requirement.warehouseId(), requirement.stages());
            routingPreflightStages = requirement.stages();
          }
        }
      }
    }
  }

  private InventoryPublicationWorkflowResult applyStartedPrestartReplacement(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      InventoryPublicationValidatedPlan publication,
      InventoryPublicationPrestartReplacementRemoteGateway.RemoteCompensation remote,
      UUID incomingAdmissionWarehouseId,
      List<InventoryPlanStageSnapshot> routingPreflightStages) {
    sourceLifecycle.requireRegisteredRequest(sourceId, requestSha256);
    InventoryPublicationSource replay = sourceLifecycle.requireReplayOrNull(sourceId, requestSha256);
    if (replay != null) return sourceLifecycle.replay(replay);
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    InventoryPublicationAssetFence.requireAuthoritative(request, asset);
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(finding.assetId());
    List<MaintenanceRepair> lockedRepairs = repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    MaintenanceRepair predecessor = lockedRepairs.stream()
        .filter(value -> intent.getPredecessorRepairId().equals(value.getId()))
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start predecessor repair not found"));
    targetSelection.requireNoOtherActiveTarget(
        InventoryPublicationTargetKind.REPAIR,
        predecessor.getId(),
        lockedEstimates,
        lockedRepairs,
        request.warehouseId());
    InventoryPublicationDeltaRepairPlan successorPlan = planMaterialization.successorPlan(
        sourceId, publication.snapshot(), publication.sourceMedia(), predecessor);
    InventoryPublicationOutcome outcome;
    InventoryPublicationCreatedTarget created;
    if (successorPlan.plan().lines().isEmpty()) {
      outcome = InventoryPublicationOutcome.MATCHED;
      created = InventoryPublicationCreatedTarget.none();
    } else {
      outcome = InventoryPublicationOutcome.SUCCESSOR;
      created = repairMaterialization.createRepair(
          sourceId,
          request.warehouseId(),
          finding,
          request.authoritativeAssetVersion(),
          successorPlan.plan(),
          false,
          incomingAdmissionWarehouseId,
          routingPreflightStages,
          sourceLifecycle.stableKey("inventory-publication-queue-repair", sourceId));
    }
    InventoryPublicationWorkflowResult result = sourceLifecycle.persist(new InventoryPublicationSourceWrite(
        sourceId,
        request,
        finding,
        publication,
        null,
        outcome,
        predecessor,
        predecessor.getId(),
        successorPlan.delta(),
        created,
        requestSha256,
        idempotencyKey,
        targetSelection.terminalProof(predecessor),
        targetSelection.acceptanceProof(predecessor)));
    intent.markAppliedAfterStartedTruth(remote.driverOutcome(), remote.taskOutcome());
    prestartReplacements.saveAndFlush(intent);
    return result;
  }

  private InventoryPublicationWorkflowResult replayPrestartReplacement(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    return sourceLifecycle.replayRequired(
        sourceId,
        requestSha256,
        "Applied pre-start replacement has no source row");
  }

  private InventoryPublicationPrestartReplacement requirePrestartIntent(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationPrestartReplacement intent = prestartReplacements
        .findByIdForUpdate(sourceId)
        .orElseThrow(() -> new IllegalStateException("Inventory pre-start replacement intent is absent"));
    try {
      intent.requireSameRequest(requestSha256);
    } catch (IllegalArgumentException exception) {
      throw InventoryPublicationPlanValidation.conflict(
          "Completed inventory source is already bound to different publication input");
    }
    return intent;
  }

  private RentalItemFactProjection requireAssetForUpdate(UUID assetId) {
    return rentalItems.findByIdForUpdate(assetId).orElseThrow(
        () -> new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE, "Current rental-item fact is unavailable"));
  }

  private static MaintenanceDependencyException retryablePrestartConflict(
      MaintenanceConflictException exception) {
    return new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Pre-start inventory replacement requires reconciliation after a possible remote effect: "
            + exception.getMessage(),
        exception);
  }

  private static MaintenanceDependencyException retryablePrestartDependency(
      MaintenanceDependencyException exception) {
    if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE) {
      return exception;
    }
    return new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Pre-start inventory replacement has a possible remote effect and dependency truth "
            + "must be reconciled before retry: "
            + exception.getMessage(),
        exception);
  }

  private static MaintenanceDependencyException retryablePrestartFailure(RuntimeException exception) {
    if (exception instanceof MaintenanceDependencyException dependency) {
      return retryablePrestartDependency(dependency);
    }
    return new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Pre-start inventory replacement has a possible remote effect and must be reconciled "
            + "before retry",
        exception);
  }
}

/** Prepare-phase result: immutable replay, durable intent, or ordinary-apply fallback. */
record InventoryPublicationPrestartPreparation(
    InventoryPublicationApplyResult replay, InventoryPublicationPrestartReplacement intent) {
  static InventoryPublicationPrestartPreparation replay(InventoryPublicationApplyResult value) {
    return new InventoryPublicationPrestartPreparation(value, null);
  }

  static InventoryPublicationPrestartPreparation intent(InventoryPublicationPrestartReplacement value) {
    return new InventoryPublicationPrestartPreparation(null, value);
  }

  static InventoryPublicationPrestartPreparation notApplicable() {
    return new InventoryPublicationPrestartPreparation(null, null);
  }

  boolean applicable() {
    return intent != null;
  }
}

/** Result sent back to normal apply when pre-start handling either owns or declines the request. */
record InventoryPublicationPrestartExecution(
    InventoryPublicationWorkflowResult result, boolean applicable) {
  static InventoryPublicationPrestartExecution handled(InventoryPublicationWorkflowResult value) {
    return new InventoryPublicationPrestartExecution(value, true);
  }

  static InventoryPublicationPrestartExecution notApplicable() {
    return new InventoryPublicationPrestartExecution(null, false);
  }
}

/** Persisted remote-attempt fence required to distinguish the one provably inert conflict. */
record InventoryPublicationRemoteAttempt(boolean firstAttempt, long attemptCount) {}
