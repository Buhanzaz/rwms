package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcome;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeReceipt;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeTarget;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeTarget.LeaseIdentity;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeWatermark;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationPrestartReplacement;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeReceiptRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeTargetRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeWatermarkRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationPrestartReplacementRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns short local transactions for authoritative inventory source registration, ordering,
 * predecessor discovery, receipt-bound legacy replacement and durable remote-effect checkpoints.
 */
@Component
final class InventoryAuthoritativeOutcomeStore {
  private final InventoryAuthoritativeOutcomeRepository outcomes;
  private final InventoryAuthoritativeOutcomeReceiptRepository receipts;
  private final InventoryAuthoritativeOutcomeWatermarkRepository watermarks;
  private final InventoryAuthoritativeOutcomeTargetRepository targets;
  private final InventoryPublicationPrestartReplacementRepository legacyReplacements;
  private final RentalItemFactProjectionRepository rentalItems;
  private final MaintenanceEstimateRepository estimates;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final InventoryPublicationSourceLifecycle sourceLifecycle;
  private final MaintenanceJsonbCanonicalizer canonicalizer;
  private final ObjectMapper mapper;

  InventoryAuthoritativeOutcomeStore(
      InventoryAuthoritativeOutcomeRepository outcomes,
      InventoryAuthoritativeOutcomeReceiptRepository receipts,
      InventoryAuthoritativeOutcomeWatermarkRepository watermarks,
      InventoryAuthoritativeOutcomeTargetRepository targets,
      InventoryPublicationPrestartReplacementRepository legacyReplacements,
      RentalItemFactProjectionRepository rentalItems,
      MaintenanceEstimateRepository estimates,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      InventoryPublicationSourceLifecycle sourceLifecycle,
      MaintenanceJsonbCanonicalizer canonicalizer,
      ObjectMapper mapper) {
    this.outcomes = outcomes;
    this.receipts = receipts;
    this.watermarks = watermarks;
    this.targets = targets;
    this.legacyReplacements = legacyReplacements;
    this.rentalItems = rentalItems;
    this.estimates = estimates;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.sourceLifecycle = sourceLifecycle;
    this.canonicalizer = canonicalizer;
    this.mapper = mapper;
  }

  /** Registers and fences a work-producing outcome before any predecessor effect. */
  AuthoritativePreparation prepareWork(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      InventoryPublicationValidatedPlan publication) {
    InventoryPublicationSourceId sourceId =
        sourceLifecycle.sourceId(inventoryId, request, findingId);
    String requestSha256 = sourceLifecycle.requestSha256(inventoryId, findingId, request);
    InventoryAuthoritativeOutcomeReceipt existingReceipt =
        receipt(idempotencyKey, sourceId, requestSha256);
    if (existingReceipt != null && existingReceipt.getResponseSnapshot() != null) {
      return AuthoritativePreparation.replay(
          sourceId, requestSha256, existingReceipt.getResponseSnapshot());
    }

    InventoryAuthoritativeOutcome existingOutcome =
        outcomes.findByIdForUpdate(sourceId).orElse(null);
    InventoryPublicationRegisteredSource registered =
        sourceLifecycle.registerAuthoritativeAndLock(
            sourceId, requestSha256, true, existingOutcome);
    String desiredStatus = request.forceCapitalRepair() ? "CAPITAL_REPAIR" : "REPAIR";
    InventoryAuthoritativeOutcome outcome = outcome(
        existingOutcome,
        sourceId,
        requestSha256,
        sourceLifecycle.write(request),
        request.warehouseId(),
        request.assetId(),
        request.inventoryCompletedAt(),
        request.finalPlanSha256(),
        request.findingRevision(),
        request.authoritativeAssetVersion(),
        desiredStatus,
        "WORK");
    if (registered.historicalReplay()) {
      requireHistoricalSourceIdentity(
          registered.replay(),
          request.warehouseId(),
          request.assetId(),
          request.finalPlanSha256(),
          request.findingRevision());
    }
    RentalItemFactProjection asset = requireAssetForUpdate(request.assetId());
    InventoryPublicationAssetFence.requireAuthoritative(request, asset);
    fenceWatermark(outcome);
    boolean newInvocation = existingReceipt == null;
    InventoryAuthoritativeOutcomeReceipt currentReceipt = newInvocation
        ? registerReceipt(idempotencyKey, sourceId, requestSha256)
        : existingReceipt;

    if (registered.replay() != null && !registered.historicalReplay()) {
      InventoryPublicationApplyResult response = sourceLifecycle.replay(registered.replay()).response();
      UUID replayRepairId = exactSourceRepairId(registered.replay(), response);
      String snapshot = write(response);
      if (outcome.getTargetRepairId() != null
          && !outcome.getTargetRepairId().equals(replayRepairId)) {
        throw InventoryPublicationPlanValidation.conflict(
            "Authoritative inventory source and coordinator reference different repairs");
      }
      if ("APPLIED".equals(outcome.getPhase())) {
        snapshot = outcome.getResponseSnapshot();
        if (!newInvocation) {
          currentReceipt.complete(snapshot);
          receipts.saveAndFlush(currentReceipt);
          return AuthoritativePreparation.replay(sourceId, requestSha256, snapshot);
        }
      }
      discoverTargets(
          outcome,
          request.selectedTargetKind(),
          request.selectedTargetId(),
          replayRepairId,
          false);
      return AuthoritativePreparation.pending(
          sourceId,
          requestSha256,
          outcome.getPhase(),
          outcome.getTargetRepairId(),
          replayRepairId,
          "APPLIED".equals(outcome.getPhase()),
          false,
          false);
    }
    if ("APPLIED".equals(outcome.getPhase())) {
      if (registered.historicalReplay()) {
        UUID legacyReplayRepairId = historicalReplayRepairId(registered.replay());
        if (!java.util.Objects.equals(legacyReplayRepairId, outcome.getTargetRepairId())) {
          String snapshot = outcome.getResponseSnapshot();
          if (!newInvocation) {
            currentReceipt.complete(snapshot);
            receipts.saveAndFlush(currentReceipt);
            return AuthoritativePreparation.replay(sourceId, requestSha256, snapshot);
          }
          discoverTargets(
              outcome,
              request.selectedTargetKind(),
              request.selectedTargetId(),
              outcome.getTargetRepairId(),
              false);
          return AuthoritativePreparation.pending(
              sourceId,
              requestSha256,
              outcome.getPhase(),
              outcome.getTargetRepairId(),
              outcome.getTargetRepairId(),
              true,
              true,
              false);
        }
        AuthoritativeReplacementReceipt replacement = replacementReceipt(outcome, requestSha256);
        if (!newInvocation && replacement != null) {
          currentReceipt.complete(replacement.responseSnapshot());
          receipts.saveAndFlush(currentReceipt);
          return AuthoritativePreparation.replay(
              sourceId, requestSha256, replacement.responseSnapshot());
        }
        UUID replacementRepairId = replacement == null ? null : replacement.repairId();
        discoverTargets(
            outcome,
            request.selectedTargetKind(),
            request.selectedTargetId(),
            replacementRepairId,
            true);
        return AuthoritativePreparation.pending(
            sourceId,
            requestSha256,
            outcome.getPhase(),
            outcome.getTargetRepairId(),
            replacementRepairId,
            replacement != null,
            true,
            true);
      }
      throw InventoryPublicationPlanValidation.conflict(
          "Applied authoritative inventory work outcome has no immutable source response");
    }
    UUID adoptedRepairId = adoptedLegacySuccessor(sourceId);
    discoverTargets(
        outcome,
        request.selectedTargetKind(),
        request.selectedTargetId(),
        adoptedRepairId,
        false);
    return AuthoritativePreparation.pending(
        sourceId,
        requestSha256,
        outcome.getPhase(),
        outcome.getTargetRepairId(),
        adoptedRepairId,
        false,
        registered.historicalReplay(),
        false);
  }

  /** Registers and fences a FREE outcome before any predecessor effect. */
  AuthoritativePreparation prepareNoWork(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryNoWorkOutcomeRequest request) {
    InventoryPublicationSourceId sourceId =
        new InventoryPublicationSourceId(inventoryId, request.finalPlanVersion(), findingId);
    String receiptRequestSha256 = noWorkRequestSha256(inventoryId, findingId, request);
    InventoryAuthoritativeOutcomeReceipt existingReceipt =
        receipt(idempotencyKey, sourceId, receiptRequestSha256);
    if (existingReceipt != null && existingReceipt.getResponseSnapshot() != null) {
      return AuthoritativePreparation.replay(
          sourceId, receiptRequestSha256, existingReceipt.getResponseSnapshot());
    }
    InventoryAuthoritativeOutcome existingOutcome =
        outcomes.findByIdForUpdate(sourceId).orElse(null);
    InventoryPublicationSource historicalSource =
        sourceLifecycle.lockHistoricalSourceOrNull(sourceId);
    if (historicalSource != null) {
      requireHistoricalSourceIdentity(
          historicalSource,
          request.warehouseId(),
          request.assetId(),
          request.finalPlanSha256(),
          request.findingRevision());
    }
    InventoryAuthoritativeOutcome outcome = noWorkOutcome(
        existingOutcome,
        sourceId,
        receiptRequestSha256,
        request);
    String coordinatorRequestSha256 = outcome.getRequestSha256();
    RentalItemFactProjection asset = requireAssetForUpdate(request.assetId());
    InventoryPublicationAssetFence.requireAuthoritativeNoWork(request, asset);
    fenceWatermark(outcome);
    boolean newInvocation = existingReceipt == null;
    InventoryAuthoritativeOutcomeReceipt currentReceipt = newInvocation
        ? registerReceipt(idempotencyKey, sourceId, receiptRequestSha256)
        : existingReceipt;
    if ("APPLIED".equals(outcome.getPhase())) {
      if (!newInvocation) {
        currentReceipt.complete(outcome.getResponseSnapshot());
        receipts.saveAndFlush(currentReceipt);
        return AuthoritativePreparation.replay(
            sourceId, receiptRequestSha256, outcome.getResponseSnapshot());
      }
      discoverTargets(outcome, null, null, null, false);
      return AuthoritativePreparation.pendingWithReceiptRequest(
          sourceId,
          coordinatorRequestSha256,
          receiptRequestSha256,
          outcome.getPhase(),
          null,
          null,
          true,
          false,
          false);
    }
    discoverTargets(outcome, null, null, null, false);
    return AuthoritativePreparation.pendingWithReceiptRequest(
        sourceId,
        coordinatorRequestSha256,
        receiptRequestSha256,
        outcome.getPhase(),
        null,
        null,
        false,
        false,
        false);
  }

  /** Returns all predecessor effect ledgers under a source lock. */
  List<UUID> targetIds(InventoryPublicationSourceId sourceId) {
    return targetRows(sourceId).stream().map(InventoryAuthoritativeOutcomeTarget::getId).toList();
  }

  /** Loads one target under a write lock for an external-attempt checkpoint. */
  InventoryAuthoritativeOutcomeTarget target(UUID id) {
    return targets.findByIdForUpdate(id).orElseThrow(
        () -> new IllegalStateException("Authoritative inventory target is missing"));
  }

  /** Commits a live task-board version before source-owned cancellation. */
  InventoryAuthoritativeOutcomeTarget beginTask(UUID id, long expectedVersion) {
    InventoryAuthoritativeOutcomeTarget target = target(id);
    target.refreshTaskExpectedVersion(expectedVersion);
    target.beginTaskAttempt();
    return targets.saveAndFlush(target);
  }

  /** Commits the terminal task-board truth returned by cancellation/readback. */
  void recordTask(UUID id, String outcome, long version) {
    InventoryAuthoritativeOutcomeTarget target = target(id);
    target.recordTaskOutcome(outcome, version);
    targets.saveAndFlush(target);
  }

  /** Commits a logistics attempt before its callback-capable request. */
  InventoryAuthoritativeOutcomeTarget beginDriver(UUID id) {
    InventoryAuthoritativeOutcomeTarget target = target(id);
    target.beginDriverAttempt();
    return targets.saveAndFlush(target);
  }

  /** Commits logistics-owned movement and repair-place truth. */
  void recordDriver(
      UUID id,
      String outcome,
      UUID driverTaskId,
      UUID allocationId,
      Long allocationVersion) {
    InventoryAuthoritativeOutcomeTarget target = target(id);
    target.recordDriverOutcome(outcome, driverTaskId, allocationId, allocationVersion);
    targets.saveAndFlush(target);
  }

  /** Commits an asset lease-release attempt before its remote request. */
  InventoryAuthoritativeOutcomeTarget beginLease(UUID id) {
    InventoryAuthoritativeOutcomeTarget target = target(id);
    target.beginLeaseAttempt();
    return targets.saveAndFlush(target);
  }

  /** Commits successful release of the exact captured lease. */
  void recordLeaseReleased(UUID id) {
    InventoryAuthoritativeOutcomeTarget target = target(id);
    target.markLeaseReleased();
    targets.saveAndFlush(target);
  }

  /** Moves the source to EFFECTS_SETTLED only when every remote ledger is terminal. */
  void markRemoteEffectsSettled(InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryAuthoritativeOutcome outcome = requireOutcome(sourceId, requestSha256);
    List<InventoryAuthoritativeOutcomeTarget> sourceTargets = targetRows(sourceId);
    if (sourceTargets.stream().anyMatch(value -> !value.remoteSettled())) {
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Authoritative inventory predecessor effects are not settled");
    }
    outcome.markEffectsSettled();
    outcomes.saveAndFlush(outcome);
  }

  /** Binds a newly created or recovered exact-source work target. */
  void attachWorkTarget(
      InventoryPublicationSourceId sourceId, String requestSha256, UUID repairId) {
    InventoryAuthoritativeOutcome outcome = requireOutcome(sourceId, requestSha256);
    outcome.attachTarget(repairId);
    outcomes.saveAndFlush(outcome);
  }

  /** Returns the locked source coordinator for final local application. */
  InventoryAuthoritativeOutcome outcomeForUpdate(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    return requireOutcome(sourceId, requestSha256);
  }

  /** Returns locked target ledgers for final local application. */
  List<InventoryAuthoritativeOutcomeTarget> targetsForUpdate(
      InventoryPublicationSourceId sourceId) {
    return targetRows(sourceId);
  }

  /** Marks one target historical after owning local state has been changed. */
  void markLocalSuperseded(InventoryAuthoritativeOutcomeTarget target) {
    target.markLocalSuperseded();
    targets.saveAndFlush(target);
  }

  /** Completes source and invoking receipt with one immutable response snapshot. */
  void complete(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      UUID idempotencyKey,
      String responseSnapshot) {
    complete(sourceId, requestSha256, requestSha256, idempotencyKey, responseSnapshot);
  }

  /** Completes a coordinator while retaining the invoking receipt's exact request fingerprint. */
  void complete(
      InventoryPublicationSourceId sourceId,
      String coordinatorRequestSha256,
      String receiptRequestSha256,
      UUID idempotencyKey,
      String responseSnapshot) {
    InventoryAuthoritativeOutcome outcome = requireOutcome(sourceId, coordinatorRequestSha256);
    outcome.apply(responseSnapshot);
    outcomes.saveAndFlush(outcome);
    InventoryAuthoritativeOutcomeReceipt receipt = receipts.findByIdForUpdate(idempotencyKey)
        .orElseThrow(() -> new IllegalStateException("Authoritative inventory receipt is missing"));
    receipt.requireSame(sourceId, receiptRequestSha256);
    receipt.complete(responseSnapshot);
    receipts.saveAndFlush(receipt);
  }

  /** Completes only a new invocation after reasserting an already-applied immutable outcome. */
  void completeReceipt(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      UUID idempotencyKey,
      String responseSnapshot) {
    completeReceipt(sourceId, requestSha256, requestSha256, idempotencyKey, responseSnapshot);
  }

  /** Completes a reassertion receipt whose technical asset-version fence advanced. */
  void completeReceipt(
      InventoryPublicationSourceId sourceId,
      String coordinatorRequestSha256,
      String receiptRequestSha256,
      UUID idempotencyKey,
      String responseSnapshot) {
    InventoryAuthoritativeOutcome outcome = requireOutcome(sourceId, coordinatorRequestSha256);
    if (!"APPLIED".equals(outcome.getPhase())) {
      throw new IllegalStateException("Authoritative inventory outcome is not yet applied");
    }
    InventoryAuthoritativeOutcomeReceipt receipt = receipts.findByIdForUpdate(idempotencyKey)
        .orElseThrow(() -> new IllegalStateException("Authoritative inventory receipt is missing"));
    receipt.requireSame(sourceId, receiptRequestSha256);
    receipt.complete(responseSnapshot);
    receipts.saveAndFlush(receipt);
  }

  /**
   * Returns the newest completed receipt that binds a post-legacy replacement repair. The caller
   * holds the source outcome lock, which serializes compatibility generation creation.
   */
  AuthoritativeReplacementReceipt replacementReceipt(
      InventoryAuthoritativeOutcome outcome, String requestSha256) {
    return receipts.findAll().stream()
        .filter(value -> value.getInventoryId().equals(outcome.getId().getInventoryId()))
        .filter(value -> value.getFinalPlanVersion() == outcome.getId().getFinalPlanVersion())
        .filter(value -> value.getFindingId().equals(outcome.getId().getFindingId()))
        .filter(value -> requestSha256.equals(value.getRequestSha256()))
        .filter(value -> value.getCompletedAt() != null && value.getResponseSnapshot() != null)
        .sorted(
            Comparator.comparing(InventoryAuthoritativeOutcomeReceipt::getCompletedAt)
                .reversed()
                .thenComparing(InventoryAuthoritativeOutcomeReceipt::getIdempotencyKey))
        .map(value -> replacementReceipt(outcome, value))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * Creates a non-persistent coordinator view for local supersession of a legacy APPLIED target.
   * The completed replacement receipt is the durable current-target binding.
   */
  InventoryAuthoritativeOutcome historicalReplacementView(
      InventoryAuthoritativeOutcome outcome, UUID replacementRepairId) {
    InventoryAuthoritativeOutcome view = InventoryAuthoritativeOutcome.prepare(
        outcome.getId(),
        outcome.getRequestSha256(),
        outcome.getRequestSnapshot(),
        outcome.getWarehouseId(),
        outcome.getAssetId(),
        outcome.getInventoryCompletedAt(),
        outcome.getFinalPlanSha256(),
        outcome.getFindingRevision(),
        outcome.getAuthoritativeAssetVersion(),
        outcome.getDesiredStatus(),
        outcome.getOutcomeKind());
    view.markEffectsSettled();
    view.attachTarget(replacementRepairId);
    return view;
  }

  /** Canonically serializes an immutable response for durable replay. */
  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Authoritative inventory value cannot be serialized", exception);
    }
  }

  /** Reads one durable response into its endpoint-specific transport record. */
  <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored authoritative inventory response is invalid", exception);
    }
  }

  private InventoryAuthoritativeOutcome outcome(
      InventoryAuthoritativeOutcome existing,
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      String requestSnapshot,
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime inventoryCompletedAt,
      String finalPlanSha256,
      long findingRevision,
      long authoritativeAssetVersion,
      String desiredStatus,
      String outcomeKind) {
    if (existing != null) {
      requireSameOutcome(existing, requestSha256, warehouseId, assetId, outcomeKind);
      return existing;
    }
    try {
      return outcomes.saveAndFlush(InventoryAuthoritativeOutcome.prepare(
          sourceId,
          requestSha256,
          requestSnapshot,
          warehouseId,
          assetId,
          inventoryCompletedAt,
          finalPlanSha256,
          findingRevision,
          authoritativeAssetVersion,
          desiredStatus,
          outcomeKind));
    } catch (DataIntegrityViolationException exception) {
      InventoryAuthoritativeOutcome concurrent = outcomes.findByIdForUpdate(sourceId).orElseThrow(
          () -> exception);
      requireSameOutcome(concurrent, requestSha256, warehouseId, assetId, outcomeKind);
      return concurrent;
    }
  }

  /**
   * Reuses an immutable no-work coordinator when only the asset owner's effective technical
   * version advanced. The new invocation keeps its exact fingerprint in a separate receipt while
   * the original coordinator and response remain append-only history.
   */
  private InventoryAuthoritativeOutcome noWorkOutcome(
      InventoryAuthoritativeOutcome existing,
      InventoryPublicationSourceId sourceId,
      String receiptRequestSha256,
      InventoryNoWorkOutcomeRequest request) {
    if (existing != null) {
      requireCompatibleNoWorkOutcome(existing, request);
      return existing;
    }
    try {
      return outcomes.saveAndFlush(
          InventoryAuthoritativeOutcome.prepare(
              sourceId,
              receiptRequestSha256,
              write(request),
              request.warehouseId(),
              request.assetId(),
              request.inventoryCompletedAt(),
              request.finalPlanSha256(),
              request.findingRevision(),
              request.authoritativeAssetVersion(),
              request.desiredStatus(),
              "NO_WORK"));
    } catch (DataIntegrityViolationException exception) {
      InventoryAuthoritativeOutcome concurrent =
          outcomes.findByIdForUpdate(sourceId).orElseThrow(() -> exception);
      requireCompatibleNoWorkOutcome(concurrent, request);
      return concurrent;
    }
  }

  private void fenceWatermark(InventoryAuthoritativeOutcome outcome) {
    InventoryAuthoritativeOutcomeWatermark watermark =
        watermarks.findByAssetIdForUpdate(outcome.getAssetId()).orElse(null);
    if (watermark == null) {
      watermarks.saveAndFlush(InventoryAuthoritativeOutcomeWatermark.create(outcome));
      return;
    }
    if (!watermark.getWarehouseId().equals(outcome.getWarehouseId())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Authoritative inventory watermark belongs to another warehouse");
    }
    if (watermark.getInventoryCompletedAt().isAfter(outcome.getInventoryCompletedAt())) {
      throw InventoryPublicationPlanValidation.conflict(
          "An older completed inventory cannot replace the latest maintenance outcome");
    }
    if (watermark.getInventoryCompletedAt().isEqual(outcome.getInventoryCompletedAt())) {
      if (!watermark.sameSource(outcome)) {
        throw InventoryPublicationPlanValidation.conflict(
            "Different inventory sources have the same completion time for this asset");
      }
      return;
    }
    InventoryPublicationSourceId previousId = new InventoryPublicationSourceId(
        watermark.getInventoryId(), watermark.getFinalPlanVersion(), watermark.getFindingId());
    InventoryAuthoritativeOutcome previous = outcomes.findByIdForUpdate(previousId).orElse(null);
    if (previous != null && !"APPLIED".equals(previous.getPhase())) {
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "The preceding authoritative inventory outcome is still recovering");
    }
    watermark.replaceWith(outcome);
    watermarks.saveAndFlush(watermark);
  }

  private void discoverTargets(
      InventoryAuthoritativeOutcome outcome,
      InventoryPublicationTargetKind selectedKind,
      UUID selectedId,
      UUID adoptedRepairId,
      boolean replaceCoordinatorTarget) {
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(outcome.getAssetId());
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(outcome.getAssetId());
    if (selectedKind == InventoryPublicationTargetKind.REPAIR && selectedId != null) {
      lockedRepairs.stream()
          .filter(value -> selectedId.equals(value.getId()))
          .filter(InventoryAuthoritativeOutcomeStore::terminal)
          .findFirst()
          .ifPresent(value -> {
            throw InventoryPublicationPlanValidation.conflict(
                "Terminal accepted or written-off repair cannot be superseded by inventory");
          });
    }
    List<InventoryAuthoritativeOutcomeTarget> existing = targetRows(outcome.getId());
    Set<String> identities = new HashSet<>();
    existing.forEach(value -> identities.add(value.getTargetKind() + ":" + value.getTargetId()));
    List<InventoryAuthoritativeOutcomeTarget> additions = new ArrayList<>();
    for (MaintenanceEstimate estimate : lockedEstimates) {
      if (!outcome.getWarehouseId().equals(estimate.getWarehouseId())
          || !InventoryPublicationTargetSelection.active(estimate)) {
        continue;
      }
      String identity = "ESTIMATE:" + estimate.getId();
      if (identities.add(identity)) {
        additions.add(InventoryAuthoritativeOutcomeTarget.estimate(outcome.getId(), estimate.getId()));
      }
    }
    Map<UUID, MaintenanceRepair> repairById = new LinkedHashMap<>();
    lockedRepairs.forEach(value -> repairById.put(value.getId(), value));
    if (replaceCoordinatorTarget) {
      MaintenanceRepair coordinatorTarget = repairById.get(outcome.getTargetRepairId());
      if (coordinatorTarget == null
          || !outcome.getWarehouseId().equals(coordinatorTarget.getWarehouseId())
          || !outcome.getAssetId().equals(coordinatorTarget.getRentalItemId())) {
        throw InventoryPublicationPlanValidation.conflict(
            "Legacy authoritative replay target does not match its asset and warehouse");
      }
      if (terminal(coordinatorTarget)) {
        throw InventoryPublicationPlanValidation.conflict(
            "Terminal accepted or written-off repair cannot be replaced after legacy replay");
      }
    }
    if (adoptedRepairId != null) {
      MaintenanceRepair protectedRepair = repairById.get(adoptedRepairId);
      if (protectedRepair == null
          || !outcome.getWarehouseId().equals(protectedRepair.getWarehouseId())
          || !outcome.getAssetId().equals(protectedRepair.getRentalItemId())) {
        throw InventoryPublicationPlanValidation.conflict(
            "Exact-source inventory repair does not match the authoritative asset and warehouse");
      }
      if (terminal(protectedRepair)) {
        throw InventoryPublicationPlanValidation.conflict(
            "Terminal accepted or written-off repair cannot be reasserted by inventory");
      }
    }
    for (MaintenanceRepair repair : lockedRepairs) {
      if (!outcome.getWarehouseId().equals(repair.getWarehouseId())
          || !InventoryPublicationTargetSelection.active(repair)
          || (!replaceCoordinatorTarget && repair.getId().equals(outcome.getTargetRepairId()))
          || repair.getId().equals(adoptedRepairId)) {
        continue;
      }
      List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
      boolean hasStageTask = stages.stream().anyMatch(value -> value.getExternalQueueEntryId() != null);
      if (hasStageTask && repair.getTaskBoardVersion() == null) {
        throw InventoryPublicationPlanValidation.conflict(
            "Active repair has task-board entries without a repair task version");
      }
      UUID taskExternalId = repair.getTaskBoardVersion() == null ? null : repair.getExternalTaskId();
      String driverKind = driverKind(repair);
      LeaseIdentity lease = lease(repair, repairById);
      String identity = "REPAIR:" + repair.getId();
      if (identities.add(identity)) {
        additions.add(InventoryAuthoritativeOutcomeTarget.repair(
            outcome.getId(),
            repair.getId(),
            taskExternalId,
            repair.getTaskBoardVersion(),
            driverKind,
            lease));
      }
    }
    if (!additions.isEmpty()) targets.saveAllAndFlush(additions);
  }

  private InventoryAuthoritativeOutcomeReceipt receipt(
      UUID idempotencyKey, InventoryPublicationSourceId sourceId, String requestSha256) {
    if (idempotencyKey == null) {
      throw InventoryPublicationPlanValidation.invalid("Idempotency-Key is required");
    }
    InventoryAuthoritativeOutcomeReceipt receipt =
        receipts.findByIdForUpdate(idempotencyKey).orElse(null);
    if (receipt == null) return null;
    try {
      receipt.requireSame(sourceId, requestSha256);
    } catch (IllegalArgumentException exception) {
      throw InventoryPublicationPlanValidation.conflict(
          "Idempotency-Key is already bound to another authoritative inventory input");
    }
    return receipt;
  }

  private InventoryAuthoritativeOutcomeReceipt registerReceipt(
      UUID idempotencyKey, InventoryPublicationSourceId sourceId, String requestSha256) {
    try {
      return receipts.saveAndFlush(
          InventoryAuthoritativeOutcomeReceipt.register(idempotencyKey, sourceId, requestSha256));
    } catch (DataIntegrityViolationException exception) {
      InventoryAuthoritativeOutcomeReceipt concurrent = receipts.findByIdForUpdate(idempotencyKey)
          .orElseThrow(() -> exception);
      try {
        concurrent.requireSame(sourceId, requestSha256);
      } catch (IllegalArgumentException mismatch) {
        throw InventoryPublicationPlanValidation.conflict(
            "Idempotency-Key is already bound to another authoritative inventory input");
      }
      return concurrent;
    }
  }

  private InventoryAuthoritativeOutcome requireOutcome(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryAuthoritativeOutcome outcome = outcomes.findByIdForUpdate(sourceId).orElseThrow(
        () -> new IllegalStateException("Authoritative inventory outcome is missing"));
    try {
      outcome.requireSameRequest(requestSha256);
    } catch (IllegalArgumentException exception) {
      throw InventoryPublicationPlanValidation.conflict(
          "Completed inventory source is already bound to different maintenance input");
    }
    return outcome;
  }

  private List<InventoryAuthoritativeOutcomeTarget> targetRows(
      InventoryPublicationSourceId sourceId) {
    return targets.findAllBySourceForUpdate(
        sourceId.getInventoryId(), sourceId.getFinalPlanVersion(), sourceId.getFindingId());
  }

  private RentalItemFactProjection requireAssetForUpdate(UUID assetId) {
    return rentalItems.findByIdForUpdate(assetId).orElseThrow(
        () -> new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE, "Current rental-item fact is unavailable"));
  }

  private UUID adoptedLegacySuccessor(InventoryPublicationSourceId sourceId) {
    InventoryPublicationPrestartReplacement legacy =
        legacyReplacements.findByIdForUpdate(sourceId).orElse(null);
    return legacy == null ? null : legacy.getSuccessorRepairId();
  }

  private UUID exactSourceRepairId(
      InventoryPublicationSource source, InventoryPublicationApplyResult response) {
    UUID repairId = response.repairId();
    if (repairId == null && "REPAIR".equals(source.getSelectedTargetKind())) {
      repairId = source.getSelectedTargetId();
    }
    if (repairId == null) repairId = source.getPredecessorRepairId();
    if (repairId == null
        || repairs.findAllByIdForUpdate(List.of(repairId)).stream().findFirst().isEmpty()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Exact inventory source replay has no recoverable maintenance repair target");
    }
    return repairId;
  }

  private static UUID historicalReplayRepairId(InventoryPublicationSource source) {
    UUID repairId = source.getRepairId();
    if (repairId == null && "REPAIR".equals(source.getSelectedTargetKind())) {
      repairId = source.getSelectedTargetId();
    }
    return repairId == null ? source.getPredecessorRepairId() : repairId;
  }

  private AuthoritativeReplacementReceipt replacementReceipt(
      InventoryAuthoritativeOutcome outcome, InventoryAuthoritativeOutcomeReceipt receipt) {
    InventoryPublicationApplyResult response =
        read(receipt.getResponseSnapshot(), InventoryPublicationApplyResult.class);
    if (response.source() == null
        || !outcome.getId().getInventoryId().equals(response.source().inventoryId())
        || outcome.getId().getFinalPlanVersion() != response.source().finalPlanVersion()
        || !outcome.getId().getFindingId().equals(response.source().findingId())
        || response.repairId() == null
        || response.repairId().equals(outcome.getTargetRepairId())) {
      return null;
    }
    MaintenanceRepair repair = repairs.findAllByIdForUpdate(List.of(response.repairId()))
        .stream()
        .findFirst()
        .orElseThrow(() -> InventoryPublicationPlanValidation.conflict(
            "Authoritative replacement receipt references a missing repair"));
    if (!terminal(repair) && !InventoryPublicationTargetSelection.active(repair)) {
      return null;
    }
    return new AuthoritativeReplacementReceipt(
        response.repairId(), receipt.getResponseSnapshot());
  }

  private static void requireHistoricalSourceIdentity(
      InventoryPublicationSource source,
      UUID warehouseId,
      UUID assetId,
      String finalPlanSha256,
      long findingRevision) {
    if (!warehouseId.equals(source.getWarehouseId())
        || !assetId.equals(source.getAssetId())
        || !finalPlanSha256.equals(source.getFinalPlanSha256())
        || findingRevision != source.getFindingRevision()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Historical inventory source does not match the authoritative finding");
    }
  }

  private String noWorkRequestSha256(
      UUID inventoryId, UUID findingId, InventoryNoWorkOutcomeRequest request) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("inventoryId", inventoryId);
    value.put("findingId", findingId);
    value.put("warehouseId", request.warehouseId());
    value.put("assetId", request.assetId());
    value.put("inventoryCompletedAt", request.inventoryCompletedAt());
    value.put("finalPlanVersion", request.finalPlanVersion());
    value.put("finalPlanSha256", request.finalPlanSha256());
    value.put("findingRevision", request.findingRevision());
    value.put("authoritativeAssetVersion", request.authoritativeAssetVersion());
    value.put("desiredStatus", request.desiredStatus());
    return canonicalizer.sha256(value);
  }

  private static String driverKind(MaintenanceRepair repair) {
    if (repair.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL) {
      return "CAPITAL_TO_PRODUCTION";
    }
    return repair.isMovementToRepair() && repair.getExecutionState() != RepairExecutionState.DRAFT
        ? "DELIVER_TO_REPAIR"
        : null;
  }

  /**
   * Retains exact owner/fencing proof for both a live lease and a failed prior reconciliation.
   * An already released lease needs no second owner call; every other incomplete state remains a
   * conflict.
   */
  private static LeaseIdentity lease(
      MaintenanceRepair repair, Map<UUID, MaintenanceRepair> repairById) {
    if (repair.getLeaseId() == null) {
      if (repair.getLeaseVersion() != null
          || repair.getFencingToken() != null
          || !("NOT_REQUIRED".equals(repair.getLeaseReconciliationState())
              || "RELEASED".equals(repair.getLeaseReconciliationState())
              || "NOT_ACQUIRED".equals(repair.getLeaseReconciliationState()))) {
        throw InventoryPublicationPlanValidation.conflict(
            "Active repair has an incomplete operation lease");
      }
      return null;
    }
    if ("RELEASED".equals(repair.getLeaseReconciliationState())) {
      return null;
    }
    if (repair.getLeaseVersion() == null
        || repair.getFencingToken() == null
        || !("ACTIVE".equals(repair.getLeaseReconciliationState())
            || "RECONCILIATION_REQUIRED".equals(repair.getLeaseReconciliationState()))) {
      throw InventoryPublicationPlanValidation.conflict(
          "Active repair operation lease requires reconciliation");
    }
    MaintenanceRepair owner = repair.getRootRepairId() == null
        ? repair
        : repairById.get(repair.getRootRepairId());
    if (owner == null) {
      throw InventoryPublicationPlanValidation.conflict("Repair lease owner is missing");
    }
    String ownerType = owner.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE";
    UUID ownerId = owner.getEstimateId() == null ? owner.getId() : owner.getEstimateId();
    return new LeaseIdentity(
        repair.getLeaseId(),
        repair.getLeaseVersion(),
        repair.getFencingToken(),
        ownerType,
        ownerId);
  }

  private static boolean terminal(MaintenanceRepair repair) {
    return repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF;
  }

  private static void requireSameOutcome(
      InventoryAuthoritativeOutcome outcome,
      String requestSha256,
      UUID warehouseId,
      UUID assetId,
      String outcomeKind) {
    try {
      outcome.requireSameRequest(requestSha256);
    } catch (IllegalArgumentException exception) {
      throw InventoryPublicationPlanValidation.conflict(
          "Completed inventory source is already bound to different maintenance input");
    }
    if (!warehouseId.equals(outcome.getWarehouseId())
        || !assetId.equals(outcome.getAssetId())
        || !outcomeKind.equals(outcome.getOutcomeKind())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Completed inventory source is already bound to another maintenance outcome");
    }
  }

  private static void requireCompatibleNoWorkOutcome(
      InventoryAuthoritativeOutcome outcome, InventoryNoWorkOutcomeRequest request) {
    if (!"NO_WORK".equals(outcome.getOutcomeKind())
        || !request.warehouseId().equals(outcome.getWarehouseId())
        || !request.assetId().equals(outcome.getAssetId())
        || !request.inventoryCompletedAt().equals(outcome.getInventoryCompletedAt())
        || !request.finalPlanSha256().equals(outcome.getFinalPlanSha256())
        || request.findingRevision() != outcome.getFindingRevision()
        || !request.desiredStatus().equals(outcome.getDesiredStatus())
        || request.authoritativeAssetVersion() < outcome.getAuthoritativeAssetVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Completed inventory source is already bound to different no-work input");
    }
  }
}

/** Durable preparation result passed between short local transactions and remote effects. */
record AuthoritativePreparation(
    InventoryPublicationSourceId sourceId,
    String requestSha256,
    String receiptRequestSha256,
    String phase,
    UUID targetRepairId,
    UUID adoptedRepairId,
    boolean reassertion,
    boolean historicalReplay,
    boolean replaceAppliedReplay,
    String replaySnapshot) {
  static AuthoritativePreparation pending(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      String phase,
      UUID targetRepairId,
      UUID adoptedRepairId,
      boolean reassertion,
      boolean historicalReplay,
      boolean replaceAppliedReplay) {
    return new AuthoritativePreparation(
        sourceId,
        requestSha256,
        requestSha256,
        phase,
        targetRepairId,
        adoptedRepairId,
        reassertion,
        historicalReplay,
        replaceAppliedReplay,
        null);
  }

  /** Preserves separate coordinator and invocation fingerprints for a safe reassertion. */
  static AuthoritativePreparation pendingWithReceiptRequest(
      InventoryPublicationSourceId sourceId,
      String coordinatorRequestSha256,
      String receiptRequestSha256,
      String phase,
      UUID targetRepairId,
      UUID adoptedRepairId,
      boolean reassertion,
      boolean historicalReplay,
      boolean replaceAppliedReplay) {
    return new AuthoritativePreparation(
        sourceId,
        coordinatorRequestSha256,
        receiptRequestSha256,
        phase,
        targetRepairId,
        adoptedRepairId,
        reassertion,
        historicalReplay,
        replaceAppliedReplay,
        null);
  }

  static AuthoritativePreparation replay(
      InventoryPublicationSourceId sourceId, String requestSha256, String replaySnapshot) {
    return new AuthoritativePreparation(
        sourceId,
        requestSha256,
        requestSha256,
        "APPLIED",
        null,
        null,
        false,
        false,
        false,
        replaySnapshot);
  }

  boolean replayed() {
    return replaySnapshot != null;
  }
}

/** Durable current repair binding recovered from a post-legacy same-source receipt. */
record AuthoritativeReplacementReceipt(UUID repairId, String responseSnapshot) {}
