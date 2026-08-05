package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.EstimateRevision;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceOperation;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationPrestartReplacement;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSuccessor;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSource;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.EstimateLineRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimatePlanStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.EstimateRevisionRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSourceOperationRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationPrestartReplacementRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSuccessorRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The completed-inventory publication boundary.  It owns no browser state: every apply stores a
 * raw, versioned source snapshot and either creates an AFTER_RENT DRAFT estimate or an inventory
 * repair whose task lifecycle remains maintenance-owned.
 */
@Service
public class InventoryPublicationReconciliationService {
  private static final Set<String> REPAIR_QUEUE_SOURCES =
      Set.of("FREE", "WAREHOUSE", "OWN_NEEDS", "AFTER_RENT");
  private static final UUID INVENTORY_ACTOR =
      UUID.nameUUIDFromBytes("inventory-service".getBytes(StandardCharsets.UTF_8));

  private final InventoryPublicationSourceOperationRepository operations;
  private final InventoryPublicationSourceRepository sources;
  private final InventoryPublicationPrestartReplacementRepository prestartReplacements;
  private final InventoryPublicationSuccessorRepository successors;
  private final InventoryRepairSourceRepository legacySources;
  private final RentalItemFactProjectionRepository rentalItems;
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceEstimateRepository estimates;
  private final EstimateLineRepository estimateLines;
  private final EstimatePlanStageRepository estimatePlans;
  private final EstimateRevisionRepository estimateRevisions;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceMediaReferenceRepository mediaReferences;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final MaintenanceReconciliationStore reconciliations;
  private final InventoryRepairReconciliationWriter repairQueue;
  private final InventoryPublicationSuccessorActivator successorActivator;
  private final InventoryPublicationSourceOperationRegistrar registrar;
  private final RepairPlaceService repairPlaces;
  private final InventoryPublicationPrestartReplacementRemoteGateway prestartRemote;
  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceJsonbCanonicalizer canonicalizer;
  private final ObjectMapper mapper;
  private final TransactionTemplate requiresNew;

  public InventoryPublicationReconciliationService(
      InventoryPublicationSourceOperationRepository operations,
      InventoryPublicationSourceRepository sources,
      InventoryPublicationPrestartReplacementRepository prestartReplacements,
      InventoryPublicationSuccessorRepository successors,
      InventoryRepairSourceRepository legacySources,
      RentalItemFactProjectionRepository rentalItems,
      MediaFactProjectionRepository mediaFacts,
      MaintenanceEstimateRepository estimates,
      EstimateLineRepository estimateLines,
      EstimatePlanStageRepository estimatePlans,
      EstimateRevisionRepository estimateRevisions,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceMediaReferenceRepository mediaReferences,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      MaintenanceReconciliationStore reconciliations,
      InventoryRepairReconciliationWriter repairQueue,
      InventoryPublicationSuccessorActivator successorActivator,
      InventoryPublicationSourceOperationRegistrar registrar,
      RepairPlaceService repairPlaces,
      InventoryPublicationPrestartReplacementRemoteGateway prestartRemote,
      MaintenanceDependencyGateway dependencies,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceJsonbCanonicalizer canonicalizer,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.sources = sources;
    this.prestartReplacements = prestartReplacements;
    this.successors = successors;
    this.legacySources = legacySources;
    this.rentalItems = rentalItems;
    this.mediaFacts = mediaFacts;
    this.estimates = estimates;
    this.estimateLines = estimateLines;
    this.estimatePlans = estimatePlans;
    this.estimateRevisions = estimateRevisions;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.mediaReferences = mediaReferences;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.reconciliations = reconciliations;
    this.repairQueue = repairQueue;
    this.successorActivator = successorActivator;
    this.registrar = registrar;
    this.repairPlaces = repairPlaces;
    this.prestartRemote = prestartRemote;
    this.dependencies = dependencies;
    this.warehouseLifecycle = warehouseLifecycle;
    this.canonicalizer = canonicalizer;
    this.mapper = mapper;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(readOnly = true)
  public InventoryPublicationPreflightResponse preflight(
      InventoryPublicationPreflightRequest request) {
    requireUniqueFindings(request.findings());
    List<InventoryPublicationPreflightFinding> findings = new ArrayList<>();
    for (InventoryPublicationFindingInput finding : request.findings()) {
      validatePublication(request.warehouseId(), finding);
      RentalItemFactProjection asset = requireAsset(finding.assetId());
      assertCurrentAsset(request.warehouseId(), finding, asset);
      InventoryPublicationTargetKind targetKind = targetKind(asset);
      findings.add(
          new InventoryPublicationPreflightFinding(
              finding.findingId(), targetKind, candidates(finding.assetId(), request.warehouseId())));
    }
    return new InventoryPublicationPreflightResponse(
        request.inventoryId(), request.finalPlanVersion(), request.finalPlanSha256(), List.copyOf(findings));
  }

  public PublicationResult apply(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request) {
    requireNoCallerTransaction("apply an inventory publication");
    if (prestartReplacementRequest(request)) {
      return applyPrestartReplacement(inventoryId, findingId, idempotencyKey, request);
    }
    return applyLocallyWithRemotePreflight(inventoryId, findingId, idempotencyKey, request);
  }

  /**
   * The first local attempt deliberately rolls back as soon as it discovers a required remote
   * admission/routing answer. That releases every source/asset lock before the network call. The
   * final short transaction reruns the complete idempotency, version and state validation.
   */
  private PublicationResult applyLocallyWithRemotePreflight(
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
        return inNewTransaction(
            () -> applyLocally(
                inventoryId,
                findingId,
                idempotencyKey,
                request,
                admittedWarehouseId,
                preflightedStages));
      } catch (PublicationRemotePreflightRequired requirement) {
        switch (requirement.kind()) {
          case INCOMING -> {
            if (requirement.warehouseId().equals(incomingAdmissionWarehouseId)) {
              throw new IllegalStateException(
                  "Inventory publication repeated warehouse admission");
            }
            warehouseLifecycle.requireIncoming(requirement.warehouseId());
            incomingAdmissionWarehouseId = requirement.warehouseId();
          }
          case ROUTING -> {
            if (requirement.stages().equals(routingPreflightStages)) {
              throw new IllegalStateException(
                  "Inventory publication repeated routing preflight");
            }
            requireWarehouseRoutingReady(requirement.warehouseId(), requirement.stages());
            routingPreflightStages = requirement.stages();
          }
        }
      }
    }
  }

  private PublicationResult applyLocally(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      UUID incomingAdmissionWarehouseId,
      List<InventoryPlanStageSnapshot> routingPreflightStages) {
    if (inventoryId == null || findingId == null || idempotencyKey == null) {
      throw invalid("Inventory publication identity and idempotency key are required");
    }
    InventoryPublicationFindingInput finding = request.finding(findingId);
    ValidatedPublication publication = validatePublication(request.warehouseId(), finding);
    String requestSha256 = requestSha256(inventoryId, findingId, request);
    InventoryPublicationSourceId sourceId =
        new InventoryPublicationSourceId(inventoryId, request.finalPlanVersion(), findingId);

    registerConcurrentSafe(() -> registrar.register(sourceId, requestSha256));
    discardUnpublishedOperationOnRollback(sourceId, requestSha256);
    InventoryPublicationSourceOperation operation =
        operations
            .findByIdForUpdate(sourceId)
            .orElseThrow(
                () -> new IllegalStateException("Inventory publication source registration failed"));
    if (!requestSha256.equals(operation.getRequestSha256())) {
      throw conflict("Completed inventory source is already bound to different publication input");
    }
    InventoryPublicationSource replay = sources.findByIdForUpdate(sourceId).orElse(null);
    if (replay != null) {
      if (!requestSha256.equals(replay.getRequestSha256())) {
        throw conflict("Completed inventory source is already bound to different publication input");
      }
      return new PublicationResult(result(replay), true);
    }

    // The fact-row lock serializes publication decisions for one asset even when there is no
    // previous maintenance target to lock yet.
    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    assertCurrentAsset(request.warehouseId(), finding, asset);
    InventoryPublicationTargetKind targetKind = targetKind(asset);

    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(finding.assetId());
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    StrategyResolution strategy = applyStrategy(
        request, finding, lockedEstimates, lockedRepairs, request.warehouseId());
    boolean successorMayUseActiveRepairAsset = strategy.predecessor() != null
        && Set.of("REPAIR", "CAPITAL_REPAIR").contains(asset.getAssetStatus());
    if (targetKind == InventoryPublicationTargetKind.REPAIR
        && !REPAIR_QUEUE_SOURCES.contains(asset.getAssetStatus())
        && !successorMayUseActiveRepairAsset) {
      throw conflict("Current rental-item status is unsafe for maintenance queueing");
    }

    InventoryPublicationOutcome outcome;
    InventoryPublicationDelta delta;
    TargetCreated created;
    if (strategy.predecessor() == null) {
      outcome = InventoryPublicationOutcome.CREATED;
      delta = fullDelta(publication.snapshot());
      created = targetKind == InventoryPublicationTargetKind.ESTIMATE
          ? createEstimate(
              sourceId, request.warehouseId(), finding, publication, incomingAdmissionWarehouseId)
          : createRepair(
              sourceId,
              request.warehouseId(),
              finding,
              publication,
              fullRepairPlan(sourceId, publication.snapshot(), publication.sourceMedia()),
              true,
              incomingAdmissionWarehouseId,
              routingPreflightStages);
    } else {
      if (targetKind != InventoryPublicationTargetKind.REPAIR) {
        throw conflict(
            "A started maintenance repair can receive only a repair successor while it remains active");
      }
      DeltaRepairPlan successorPlan = successorPlan(
          sourceId, publication.snapshot(), publication.sourceMedia(), strategy.predecessor());
      delta = successorPlan.delta();
      if (successorPlan.plan().lines().isEmpty()) {
        outcome = InventoryPublicationOutcome.MATCHED;
        created = TargetCreated.none();
      } else {
        outcome = InventoryPublicationOutcome.SUCCESSOR;
        created = createRepair(
            sourceId,
            request.warehouseId(),
            finding,
            publication,
            successorPlan.plan(),
            false,
            incomingAdmissionWarehouseId,
            routingPreflightStages);
      }
    }
    InventoryPublicationSource source = sources.saveAndFlush(InventoryPublicationSource.create(
        sourceId,
        request.warehouseId(),
        finding.findingRevision(),
        finding.assetId(),
        finding.assetVersion(),
        request.finalPlanSha256(),
        finding.planFingerprintSha256(),
        finding.snapshotSchemaVersion(),
        publication.rawSnapshot(),
        publication.rawMedia(),
        finding.priority(),
        finding.movementToRepair(),
        finding.movementScheduledDate(),
        finding.repairScheduledDate(),
        request.strategy().name(),
        enumName(request.selectedTargetKind()),
        request.selectedTargetId(),
        strategy.superseded() == null ? null : strategy.superseded().kind().name(),
        strategy.superseded() == null ? null : strategy.superseded().id(),
        outcome.name(),
        strategy.predecessor() == null ? null : strategy.predecessor().getId(),
        write(delta),
        created.kind() == null ? null : created.kind().name(),
        created.id(),
        created.estimateId(),
        created.repairId(),
        requestSha256,
        idempotencyKey));
    if (outcome == InventoryPublicationOutcome.SUCCESSOR) {
      successors.saveAndFlush(InventoryPublicationSuccessor.waiting(
          sourceId, strategy.predecessor().getId(), created.repairId()));
      if (strategy.terminalProof() != null) {
        successorActivator.releaseAfterTaskBoardCompletion(
            strategy.predecessor(),
            strategy.terminalProof().eventId(),
            strategy.terminalProof().occurredAt());
      }
    }
    return new PublicationResult(result(source), false);
  }

  /**
   * A queued repair can have a callback-capable driver cancellation. Keep every local mutation in
   * short REQUIRES_NEW segments and make the remote effects replayable from the durable intent.
   */
  private PublicationResult applyPrestartReplacement(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request) {
    if (inventoryId == null || findingId == null || idempotencyKey == null) {
      throw invalid("Inventory publication identity and idempotency key are required");
    }
    InventoryPublicationFindingInput finding = request.finding(findingId);
    ValidatedPublication publication = validatePublication(request.warehouseId(), finding);
    String requestSha256 = requestSha256(inventoryId, findingId, request);
    InventoryPublicationSourceId sourceId =
        new InventoryPublicationSourceId(inventoryId, request.finalPlanVersion(), findingId);
    String requestSnapshot = write(request);

    PrestartPreparation preparation;
    try {
      preparation =
          inNewTransaction(
              () ->
                  preparePrestartReplacement(
                      sourceId,
                      requestSha256,
                      idempotencyKey,
                      requestSnapshot,
                      request,
                      finding));
    } catch (MaintenanceConflictException exception) {
      if (inNewTransaction(() -> prestartSourceBoundToDifferentRequest(sourceId, requestSha256))) {
        throw exception;
      }
      if (inNewTransaction(() -> abortUnattemptedPrestartReplacement(sourceId, requestSha256))) {
        throw exception;
      }
      throw retryablePrestartConflict(exception);
    } catch (RuntimeException exception) {
      if (inNewTransaction(() -> abortUnattemptedPrestartReplacement(sourceId, requestSha256))) {
        throw exception;
      }
      throw retryablePrestartFailure(exception);
    }
    if (preparation.replay() != null) {
      return new PublicationResult(preparation.replay(), true);
    }
    if (!preparation.applicable()) {
      return applyLocallyWithRemotePreflight(inventoryId, findingId, idempotencyKey, request);
    }

    InventoryPublicationPrestartReplacement intent = preparation.intent();
    if ("APPLIED".equals(intent.getPhase())) {
      return inNewTransaction(() -> replayPrestartReplacement(sourceId, requestSha256));
    }
    if ("PREPARED".equals(intent.getPhase())) {
      // A zero-attempt intent has not made any callback-capable cancellation yet. Reject an
      // inactive/draining warehouse before touching task-board or logistics, so a denied
      // successor admission cannot strand a newly cancelled predecessor. Once an attempt was
      // durably recorded, the response may have been lost or the phase may have been reopened
      // after partial compensation; it must remain replayable even if admission later changes.
      if (intent.getRemoteAttemptCount() == 0) {
        warehouseLifecycle.requireIncoming(request.warehouseId());
      }
      RemoteAttempt remoteAttempt =
          inNewTransaction(() -> beginPrestartRemoteAttempt(sourceId, requestSha256));
      InventoryPublicationPrestartReplacementRemoteGateway.RemoteCompensation remote;
      try {
        remote =
            prestartRemote.compensate(
                intent,
                stableKey("inventory-publication-cancel-driver", sourceId),
                stableKey("inventory-publication-cancel-repair-task", sourceId));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
      if (remote.disposition()
          == InventoryPublicationPrestartReplacementRemoteGateway.Disposition
              .NO_EFFECT_VERSION_CONFLICT) {
        if (remoteAttempt.firstAttempt()
            && inNewTransaction(
                () -> abortFirstNoEffectPrestartReplacement(sourceId, requestSha256))) {
          throw conflict(
              "Task-board version changed before inventory could prove pre-start cancellation; "
                  + "refresh the publication target and retry");
        }
        throw new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board version conflict followed an earlier or concurrent pre-start remote "
                + "attempt; reconcile and retry the same inventory publication");
      }
      if (remote.disposition()
          == InventoryPublicationPrestartReplacementRemoteGateway.Disposition.WAIT_FOR_INBOUND_COMPLETION) {
        // This is a recoverable physical-progress wait, not contradictory inventory source truth.
        // inventory-service maps a 409 to BLOCKED/SOURCE_PRECONDITION_CONFLICT, which would
        // incorrectly require a new manual reconciliation proof. Keep the durable PREPARED
        // intent and let the panel retry the same source/key after logistics reports COMPLETED.
        throw new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Inbound delivery has started after repair-task cancellation; wait for completed "
                + "occupied-place truth before retrying this inventory publication");
      }
      if (remote.disposition()
          == InventoryPublicationPrestartReplacementRemoteGateway.Disposition.REPAIR_WORK_STARTED) {
        // Preflight can only describe the then-current state. If task-board atomically proves
        // the work started between preflight and apply, the immutable completed inventory plan
        // must still be published as the calculated residual successor (or MATCHED), even when
        // the operator had selected REPLACE. Preserve that requested strategy in the immutable
        // source audit row; the effective outcome is the safe successor path below.
        try {
          return applyStartedPrestartReplacementWithRemotePreflight(
              sourceId,
              requestSha256,
              idempotencyKey,
              request,
              finding,
              publication,
              remote);
        } catch (MaintenanceConflictException exception) {
          throw retryablePrestartConflict(exception);
        } catch (MaintenanceDependencyException exception) {
          throw retryablePrestartDependency(exception);
        }
      }
      try {
        intent =
            inNewTransaction(
                () ->
                    recordPrestartCompensation(
                        sourceId, requestSha256, remote));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
      if ("PREPARED".equals(intent.getPhase())) {
        return applyPrestartReplacement(inventoryId, findingId, idempotencyKey, request);
      }
    }

    if ("COMPENSATED".equals(intent.getPhase())) {
      try {
        RepairPublicationPlan plan =
            fullRepairPlan(sourceId, publication.snapshot(), publication.sourceMedia());
        warehouseLifecycle.requireIncoming(request.warehouseId());
        requireWarehouseRoutingReady(
            request.warehouseId(),
            plan.allocations().stream()
                .filter(allocation -> !allocation.lines().isEmpty())
                .map(PublishedStage::stage)
                .toList());
        intent =
            inNewTransaction(
                () ->
                    createPrestartReplacementSuccessor(
                        sourceId,
                        requestSha256,
                        request,
                        finding,
                        publication,
                        plan));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
      if ("PREPARED".equals(intent.getPhase())) {
        return applyPrestartReplacement(inventoryId, findingId, idempotencyKey, request);
      }
    }

    if ("SUCCESSOR_CREATED".equals(intent.getPhase())) {
      try {
        prestartRemote.releaseLease(
            intent, stableKey("inventory-publication-release-lease", sourceId));
        intent =
            inNewTransaction(
                () -> markPrestartReplacementLeaseReleased(sourceId, requestSha256));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
    }

    if ("LEASE_RELEASED".equals(intent.getPhase())) {
      try {
        return inNewTransaction(
            () ->
                finalizePrestartReplacement(
                    sourceId,
                    requestSha256,
                    request,
                    finding,
                    publication));
      } catch (MaintenanceConflictException exception) {
        throw retryablePrestartConflict(exception);
      } catch (MaintenanceDependencyException exception) {
        throw retryablePrestartDependency(exception);
      }
    }
    if ("APPLIED".equals(intent.getPhase())) {
      return inNewTransaction(() -> replayPrestartReplacement(sourceId, requestSha256));
    }
    throw new IllegalStateException("Inventory pre-start replacement has an unsupported phase");
  }

  private PrestartPreparation preparePrestartReplacement(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      UUID idempotencyKey,
      String requestSnapshot,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding) {
    registerConcurrentSafe(() -> registrar.register(sourceId, requestSha256));
    InventoryPublicationSourceOperation operation =
        operations
            .findByIdForUpdate(sourceId)
            .orElseThrow(
                () -> new IllegalStateException("Inventory publication source registration failed"));
    if (!requestSha256.equals(operation.getRequestSha256())) {
      throw conflict("Completed inventory source is already bound to different publication input");
    }
    InventoryPublicationSource replay = sources.findByIdForUpdate(sourceId).orElse(null);
    if (replay != null) {
      if (!requestSha256.equals(replay.getRequestSha256())) {
        throw conflict("Completed inventory source is already bound to different publication input");
      }
      return PrestartPreparation.replay(result(replay));
    }
    InventoryPublicationPrestartReplacement existing =
        prestartReplacements.findByIdForUpdate(sourceId).orElse(null);
    if (existing != null) {
      try {
        existing.requireSameRequest(requestSha256);
      } catch (IllegalArgumentException exception) {
        throw conflict("Completed inventory source is already bound to different publication input");
      }
      return PrestartPreparation.intent(existing);
    }

    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    assertCurrentAsset(request.warehouseId(), finding, asset);
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(finding.assetId());
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    MaintenanceRepair repair =
        lockedRepairs.stream()
            .filter(value -> request.selectedTargetId().equals(value.getId()))
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Selected repair not found"));
    if (!finding.assetId().equals(repair.getRentalItemId())
        || !request.warehouseId().equals(repair.getWarehouseId())) {
      throw conflict("Selected repair does not belong to this inventory asset and warehouse");
    }
    PrestartCandidate candidate = prestartCandidate(repair);
    if (candidate == null) return PrestartPreparation.notApplicable();
    requireNoOtherActiveTarget(
        InventoryPublicationTargetKind.REPAIR,
        repair.getId(),
        lockedEstimates,
        lockedRepairs,
        request.warehouseId());
    if (!"REPAIR".equals(asset.getAssetStatus())
        && !"CAPITAL_REPAIR".equals(asset.getAssetStatus())) {
      throw conflict("Current rental-item status is unsafe for pre-start maintenance replacement");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    boolean stageTaskEffect = stages.stream().anyMatch(stage -> stage.getExternalQueueEntryId() != null);
    boolean taskGuardRequired = repair.getTaskBoardVersion() != null;
    if (!taskGuardRequired && stageTaskEffect) {
      throw conflict(
          "Queued repair has task-board state without a repair task version for atomic cancellation");
    }
    InventoryPublicationPrestartReplacement.LeaseIdentity lease = prestartLease(repair);
    InventoryPublicationPrestartReplacement intent =
        InventoryPublicationPrestartReplacement.prepare(
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
    return PrestartPreparation.intent(prestartReplacements.saveAndFlush(intent));
  }

  private RemoteAttempt beginPrestartRemoteAttempt(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    boolean firstAttempt = intent.beginRemoteAttempt();
    InventoryPublicationPrestartReplacement saved = prestartReplacements.saveAndFlush(intent);
    return new RemoteAttempt(firstAttempt, saved.getRemoteAttemptCount());
  }

  /**
   * A locally discovered conflict before the first remote attempt can release its unpublished
   * source key. Once a remote attempt has been durably recorded, deletion would strand the
   * predecessor guard after a caller classifies the response as terminal.
   */
  private boolean abortUnattemptedPrestartReplacement(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    return abortPrestartReplacement(sourceId, requestSha256, false);
  }

  private boolean prestartSourceBoundToDifferentRequest(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationSourceOperation operation = operations.findByIdForUpdate(sourceId).orElse(null);
    if (operation != null && !requestSha256.equals(operation.getRequestSha256())) return true;
    InventoryPublicationSource source = sources.findByIdForUpdate(sourceId).orElse(null);
    return source != null && !requestSha256.equals(source.getRequestSha256());
  }

  /** Task-board VERSION_CONFLICT is the one remote result that proves a first attempt was inert. */
  private boolean abortFirstNoEffectPrestartReplacement(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    return abortPrestartReplacement(sourceId, requestSha256, true);
  }

  private boolean abortPrestartReplacement(
      InventoryPublicationSourceId sourceId, String requestSha256, boolean requireFirstNoEffectAttempt) {
    InventoryPublicationSourceOperation operation = operations.findByIdForUpdate(sourceId).orElse(null);
    if (operation == null) return true;
    if (!requestSha256.equals(operation.getRequestSha256())) return false;
    if (sources.findByIdForUpdate(sourceId).isPresent()) return false;
    InventoryPublicationPrestartReplacement intent =
        prestartReplacements.findByIdForUpdate(sourceId).orElse(null);
    if (intent != null) {
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
    }
    operations.delete(operation);
    operations.flush();
    return true;
  }

  private InventoryPublicationPrestartReplacement recordPrestartCompensation(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      InventoryPublicationPrestartReplacementRemoteGateway.RemoteCompensation remote) {
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    MaintenanceRepair predecessor =
        repairs
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
        throw conflict(
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
      ValidatedPublication publication,
      RepairPublicationPlan plan) {
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    if (!"COMPENSATED".equals(intent.getPhase())) {
      return intent;
    }
    if (!request.warehouseId().equals(intent.getWarehouseId())
        || !finding.assetId().equals(intent.getAssetId())) {
      throw conflict("Stored pre-start replacement intent does not match publication ownership");
    }
    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    assertCurrentAsset(request.warehouseId(), finding, asset);
    if (!"REPAIR".equals(asset.getAssetStatus())
        && !"CAPITAL_REPAIR".equals(asset.getAssetStatus())) {
      throw conflict("Current rental-item status is unsafe for pre-start maintenance replacement");
    }
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(finding.assetId());
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    MaintenanceRepair predecessor =
        lockedRepairs.stream()
            .filter(value -> intent.getPredecessorRepairId().equals(value.getId()))
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start predecessor repair not found"));
    requirePrestartCandidate(predecessor, intent);
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
        throw conflict(
            "Queued repair gained task-board state without a repair task version for atomic cancellation");
      }
    }
    requireNoOtherActiveTarget(
        InventoryPublicationTargetKind.REPAIR,
        predecessor.getId(),
        lockedEstimates,
        lockedRepairs,
        request.warehouseId());
    TargetCreated created =
        createRepair(
            sourceId,
            request.warehouseId(),
            finding,
            publication,
            plan,
            false,
            request.warehouseId(),
            plan.allocations().stream()
                .filter(allocation -> !allocation.lines().isEmpty())
                .map(PublishedStage::stage)
                .toList());
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

  private PublicationResult finalizePrestartReplacement(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      ValidatedPublication publication) {
    InventoryPublicationSourceOperation operation =
        operations
            .findByIdForUpdate(sourceId)
            .orElseThrow(
                () -> new IllegalStateException("Inventory publication source registration failed"));
    if (!requestSha256.equals(operation.getRequestSha256())) {
      throw conflict("Completed inventory source is already bound to different publication input");
    }
    InventoryPublicationSource replay = sources.findByIdForUpdate(sourceId).orElse(null);
    if (replay != null) return new PublicationResult(result(replay), true);
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    if (!"LEASE_RELEASED".equals(intent.getPhase())) {
      throw new IllegalStateException("Inventory pre-start replacement is not ready to finalize");
    }
    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    assertCurrentAsset(request.warehouseId(), finding, asset);
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    MaintenanceRepair predecessor =
        lockedRepairs.stream()
            .filter(value -> intent.getPredecessorRepairId().equals(value.getId()))
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start predecessor repair not found"));
    MaintenanceRepair successor =
        lockedRepairs.stream()
            .filter(value -> intent.getSuccessorRepairId().equals(value.getId()))
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start successor repair not found"));
    requirePrestartCandidate(predecessor, intent);
    if (successor.getExecutionState() != RepairExecutionState.DRAFT
        || !predecessor.getWarehouseId().equals(successor.getWarehouseId())
        || !predecessor.getRentalItemId().equals(successor.getRentalItemId())) {
      throw conflict("Pre-start replacement successor is no longer safe to queue");
    }
    if (intent.isOccupancyReassignmentRequired()) {
      if (!repairPlaces.isOccupied(request.warehouseId(), successor.getId())) {
        throw conflict("Delivered inventory replacement lost its occupied repair-place allocation");
      }
      repairPlaces.requireNoActiveAllocationForInventoryReplacement(
          request.warehouseId(),
          predecessor.getId(),
          null,
          null);
    } else {
      repairPlaces.requireNoActiveAllocationForInventoryReplacement(
          request.warehouseId(),
          predecessor.getId(),
          intent.getRepairPlaceAllocationId(),
          intent.getRepairPlaceAllocationVersion());
    }
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, predecessor.getId());
    if (expectedVersion != predecessor.getVersion()) {
      throw conflict("Pre-start predecessor event stream does not match its current version");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(predecessor.getId());
    if ("ORDINARY".equals(intent.getPredecessorMode())) {
      stages.forEach(RepairStage::supersedeQueuedForInventoryPublication);
      predecessor.supersedeQueuedForInventoryPublication();
    } else {
      stages.forEach(RepairStage::supersedeExternalCapitalForInventoryPublication);
      predecessor.supersedeExternalCapitalForInventoryPublication();
    }
    if (intent.getLeaseId() != null) {
      predecessor.releaseLease();
    }
    repairStages.saveAllAndFlush(stages);
    MaintenanceRepair savedPredecessor = repairs.saveAndFlush(predecessor);
    reconciliations.cancelPendingForRepair(savedPredecessor.getId());
    Map<String, Object> state = projectionSnapshots.repair(savedPredecessor);
    events.append(
        MaintenanceAggregateType.REPAIR,
        savedPredecessor.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        state,
        eventFacts.repairPayload(
            MaintenanceEventType.REPAIR_PLAN_CHANGED, savedPredecessor, stages),
        state);
    InventoryPublicationSource source = sources.saveAndFlush(InventoryPublicationSource.create(
        sourceId,
        request.warehouseId(),
        finding.findingRevision(),
        finding.assetId(),
        finding.assetVersion(),
        request.finalPlanSha256(),
        finding.planFingerprintSha256(),
        finding.snapshotSchemaVersion(),
        publication.rawSnapshot(),
        publication.rawMedia(),
        finding.priority(),
        finding.movementToRepair(),
        finding.movementScheduledDate(),
        finding.repairScheduledDate(),
        request.strategy().name(),
        enumName(request.selectedTargetKind()),
        request.selectedTargetId(),
        InventoryPublicationTargetKind.REPAIR.name(),
        savedPredecessor.getId(),
        InventoryPublicationOutcome.CREATED.name(),
        null,
        write(fullDelta(publication.snapshot())),
        InventoryPublicationTargetKind.REPAIR.name(),
        successor.getId(),
        null,
        successor.getId(),
        requestSha256,
        intent.getRequestIdempotencyKey()));
    repairQueue.enqueue(
        successor.getId(), stableKey("inventory-publication-queue-repair", sourceId));
    intent.markApplied();
    prestartReplacements.saveAndFlush(intent);
    return new PublicationResult(result(source), false);
  }

  private PublicationResult applyStartedPrestartReplacementWithRemotePreflight(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      ValidatedPublication publication,
      InventoryPublicationPrestartReplacementRemoteGateway.RemoteCompensation remote) {
    UUID incomingAdmissionWarehouseId = null;
    List<InventoryPlanStageSnapshot> routingPreflightStages = null;
    while (true) {
      UUID admittedWarehouseId = incomingAdmissionWarehouseId;
      List<InventoryPlanStageSnapshot> preflightedStages = routingPreflightStages;
      try {
        return inNewTransaction(
            () ->
                applyStartedPrestartReplacement(
                    sourceId,
                    requestSha256,
                    idempotencyKey,
                    request,
                    finding,
                    publication,
                    remote,
                    admittedWarehouseId,
                    preflightedStages));
      } catch (PublicationRemotePreflightRequired requirement) {
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
              throw new IllegalStateException(
                  "Started inventory publication repeated routing preflight");
            }
            requireWarehouseRoutingReady(requirement.warehouseId(), requirement.stages());
            routingPreflightStages = requirement.stages();
          }
        }
      }
    }
  }

  private PublicationResult applyStartedPrestartReplacement(
      InventoryPublicationSourceId sourceId,
      String requestSha256,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      ValidatedPublication publication,
      InventoryPublicationPrestartReplacementRemoteGateway.RemoteCompensation remote,
      UUID incomingAdmissionWarehouseId,
      List<InventoryPlanStageSnapshot> routingPreflightStages) {
    InventoryPublicationSourceOperation operation =
        operations
            .findByIdForUpdate(sourceId)
            .orElseThrow(
                () -> new IllegalStateException("Inventory publication source registration failed"));
    if (!requestSha256.equals(operation.getRequestSha256())) {
      throw conflict("Completed inventory source is already bound to different publication input");
    }
    InventoryPublicationSource replay = sources.findByIdForUpdate(sourceId).orElse(null);
    if (replay != null) return new PublicationResult(result(replay), true);
    InventoryPublicationPrestartReplacement intent = requirePrestartIntent(sourceId, requestSha256);
    RentalItemFactProjection asset = requireAssetForUpdate(finding.assetId());
    assertCurrentAsset(request.warehouseId(), finding, asset);
    if (!"REPAIR".equals(asset.getAssetStatus())
        && !"CAPITAL_REPAIR".equals(asset.getAssetStatus())) {
      throw conflict("Current rental-item status is unsafe for a started repair successor");
    }
    List<MaintenanceEstimate> lockedEstimates =
        estimates.findAllByRentalItemIdForUpdate(finding.assetId());
    List<MaintenanceRepair> lockedRepairs =
        repairs.findAllByRentalItemIdForUpdate(finding.assetId());
    MaintenanceRepair predecessor =
        lockedRepairs.stream()
            .filter(value -> intent.getPredecessorRepairId().equals(value.getId()))
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Pre-start predecessor repair not found"));
    requireNoOtherActiveTarget(
        InventoryPublicationTargetKind.REPAIR,
        predecessor.getId(),
        lockedEstimates,
        lockedRepairs,
        request.warehouseId());
    DeltaRepairPlan successorPlan =
        successorPlan(sourceId, publication.snapshot(), publication.sourceMedia(), predecessor);
    InventoryPublicationOutcome outcome;
    TargetCreated created;
    if (successorPlan.plan().lines().isEmpty()) {
      outcome = InventoryPublicationOutcome.MATCHED;
      created = TargetCreated.none();
    } else {
      outcome = InventoryPublicationOutcome.SUCCESSOR;
      created =
          createRepair(
              sourceId,
              request.warehouseId(),
              finding,
              publication,
              successorPlan.plan(),
              false,
              incomingAdmissionWarehouseId,
              routingPreflightStages);
    }
    InventoryPublicationSource source = sources.saveAndFlush(InventoryPublicationSource.create(
        sourceId,
        request.warehouseId(),
        finding.findingRevision(),
        finding.assetId(),
        finding.assetVersion(),
        request.finalPlanSha256(),
        finding.planFingerprintSha256(),
        finding.snapshotSchemaVersion(),
        publication.rawSnapshot(),
        publication.rawMedia(),
        finding.priority(),
        finding.movementToRepair(),
        finding.movementScheduledDate(),
        finding.repairScheduledDate(),
        request.strategy().name(),
        enumName(request.selectedTargetKind()),
        request.selectedTargetId(),
        null,
        null,
        outcome.name(),
        predecessor.getId(),
        write(successorPlan.delta()),
        created.kind() == null ? null : created.kind().name(),
        created.id(),
        created.estimateId(),
        created.repairId(),
        requestSha256,
        idempotencyKey));
    if (outcome == InventoryPublicationOutcome.SUCCESSOR) {
      successors.saveAndFlush(
          InventoryPublicationSuccessor.waiting(
              sourceId, predecessor.getId(), created.repairId()));
      // A task-board completion can win immediately after its atomic STARTED answer and before
      // this local successor row is inserted. The event handler then has no relation to release,
      // so inspect the locked local terminal fact here and release idempotently when it already
      // exists. External-capital intentionally has no ordinary completion proof and still waits
      // for acceptance.
      TerminalProof terminalProof = terminalProof(predecessor);
      if (terminalProof != null) {
        successorActivator.releaseAfterTaskBoardCompletion(
            predecessor, terminalProof.eventId(), terminalProof.occurredAt());
      }
      TerminalProof acceptanceProof = acceptanceProof(predecessor);
      if (acceptanceProof != null) {
        successorActivator.releaseAfterAcceptance(
            predecessor, acceptanceProof.eventId(), acceptanceProof.occurredAt());
      }
    }
    intent.markAppliedAfterStartedTruth(remote.driverOutcome(), remote.taskOutcome());
    prestartReplacements.saveAndFlush(intent);
    return new PublicationResult(result(source), false);
  }

  private PublicationResult replayPrestartReplacement(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationSource source =
        sources
            .findByIdForUpdate(sourceId)
            .orElseThrow(
                () -> new IllegalStateException("Applied pre-start replacement has no source row"));
    if (!requestSha256.equals(source.getRequestSha256())) {
      throw conflict("Completed inventory source is already bound to different publication input");
    }
    return new PublicationResult(result(source), true);
  }

  private InventoryPublicationPrestartReplacement requirePrestartIntent(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    InventoryPublicationPrestartReplacement intent =
        prestartReplacements
            .findByIdForUpdate(sourceId)
            .orElseThrow(() -> new IllegalStateException("Inventory pre-start replacement intent is absent"));
    try {
      intent.requireSameRequest(requestSha256);
    } catch (IllegalArgumentException exception) {
      throw conflict("Completed inventory source is already bound to different publication input");
    }
    return intent;
  }

  private PrestartCandidate prestartCandidate(MaintenanceRepair repair) {
    if (repair.getExecutionState() == RepairExecutionState.QUEUED
        && repair.getAcceptanceState() == RepairAcceptanceState.NOT_READY
        && repair.getReclassificationState()
            == dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState.STABLE) {
      return new PrestartCandidate("ORDINARY", "DELIVER_TO_REPAIR");
    }
    if (repair.getExecutionState() == RepairExecutionState.COMPLETED
        && repair.getAcceptanceState() == RepairAcceptanceState.PENDING
        && repair.getReclassificationState()
            == dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState.EXTERNAL_CAPITAL) {
      return new PrestartCandidate("EXTERNAL_CAPITAL", "CAPITAL_TO_PRODUCTION");
    }
    return null;
  }

  private void requirePrestartCandidate(
      MaintenanceRepair repair, InventoryPublicationPrestartReplacement intent) {
    PrestartCandidate current = prestartCandidate(repair);
    if (current == null
        || !current.mode().equals(intent.getPredecessorMode())
        || !current.driverKind().equals(intent.getDriverKind())) {
      throw conflict("Pre-start predecessor repair changed before replacement could finish");
    }
  }

  private InventoryPublicationPrestartReplacement.LeaseIdentity prestartLease(
      MaintenanceRepair repair) {
    if (repair.getLeaseId() == null) {
      if (repair.getLeaseVersion() != null
          || repair.getFencingToken() != null
          || repair.getLeaseExpiresAt() != null
          || !("NOT_REQUIRED".equals(repair.getLeaseReconciliationState())
              || "RELEASED".equals(repair.getLeaseReconciliationState()))) {
        throw conflict("Pre-start predecessor has an incomplete operation lease");
      }
      return null;
    }
    if (repair.getLeaseVersion() == null
        || repair.getFencingToken() == null
        || repair.getLeaseExpiresAt() == null
        || !"ACTIVE".equals(repair.getLeaseReconciliationState())) {
      throw conflict("Pre-start predecessor operation lease requires reconciliation");
    }
    MaintenanceRepair owner = repair.getRootRepairId() == null
        ? repair
        : repairs
            .findAllByIdForUpdate(List.of(repair.getRootRepairId()))
            .stream()
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair lease owner not found"));
    String ownerType = owner.getEstimateId() == null ? "MAINTENANCE_REPAIR" : "MAINTENANCE_ESTIMATE";
    UUID ownerId = owner.getEstimateId() == null ? owner.getId() : owner.getEstimateId();
    return new InventoryPublicationPrestartReplacement.LeaseIdentity(
        repair.getLeaseId(),
        repair.getLeaseVersion(),
        repair.getFencingToken(),
        ownerType,
        ownerId);
  }

  private static boolean prestartReplacementRequest(InventoryPublicationApplyRequest request) {
    return request != null
        && request.selectedTargetKind() == InventoryPublicationTargetKind.REPAIR
        && (request.strategy() == InventoryPublicationStrategy.REPLACE
            || request.strategy() == InventoryPublicationStrategy.MERGE);
  }

  private StrategyResolution applyStrategy(
      InventoryPublicationApplyRequest request,
      InventoryPublicationFindingInput finding,
      List<MaintenanceEstimate> lockedEstimates,
      List<MaintenanceRepair> lockedRepairs,
      UUID warehouseId) {
    if (request.strategy() == InventoryPublicationStrategy.CREATE) {
      if (request.selectedTargetKind() != null || request.selectedTargetId() != null) {
        throw invalid("CREATE inventory publication cannot select an existing target");
      }
      boolean active = lockedEstimates.stream()
              .filter(value -> warehouseId.equals(value.getWarehouseId()))
              .anyMatch(value -> active(value))
          || lockedRepairs.stream()
              .filter(value -> warehouseId.equals(value.getWarehouseId()))
              .anyMatch(value -> active(value));
      if (active) {
        throw conflict("CREATE inventory publication requires resolution of the active maintenance target");
      }
      return StrategyResolution.none();
    }
    if (request.selectedTargetKind() == null || request.selectedTargetId() == null) {
      throw invalid("REPLACE or MERGE inventory publication requires one selected target");
    }
    requireNoOtherActiveTarget(
        request.selectedTargetKind(), request.selectedTargetId(), lockedEstimates, lockedRepairs, warehouseId);
    return switch (request.selectedTargetKind()) {
      case ESTIMATE -> StrategyResolution.superseded(
          supersedeEstimate(request.selectedTargetId(), finding.assetId(), warehouseId));
      case REPAIR -> resolveRepairStrategy(
          request.strategy(), request.selectedTargetId(), finding.assetId(), warehouseId);
    };
  }

  /**
   * A publication command names one target, but a cabin may have been left with more than one
   * independently active draft/repair by older direct-maintenance flows.  Replacing the selected
   * one while silently retaining another would create a second active maintenance target.  There
   * is no approved multi-target resolution command on this boundary, so fail closed before making
   * any selected-target transition.
   */
  private void requireNoOtherActiveTarget(
      InventoryPublicationTargetKind selectedKind,
      UUID selectedId,
      List<MaintenanceEstimate> lockedEstimates,
      List<MaintenanceRepair> lockedRepairs,
      UUID warehouseId) {
    boolean otherEstimate = lockedEstimates.stream()
        .filter(value -> warehouseId.equals(value.getWarehouseId()))
        .filter(value -> active(value))
        .anyMatch(value -> selectedKind != InventoryPublicationTargetKind.ESTIMATE
            || !selectedId.equals(value.getId()));
    boolean otherRepair = lockedRepairs.stream()
        .filter(value -> warehouseId.equals(value.getWarehouseId()))
        .filter(InventoryPublicationReconciliationService::active)
        .anyMatch(value -> selectedKind != InventoryPublicationTargetKind.REPAIR
            || !selectedId.equals(value.getId()));
    if (otherEstimate || otherRepair) {
      throw conflict(
          "REPLACE or MERGE requires the selected target to be the only active maintenance target");
    }
  }

  private SupersededTarget supersedeEstimate(UUID estimateId, UUID assetId, UUID warehouseId) {
    MaintenanceEstimate estimate = estimates.findByIdForUpdate(estimateId).orElseThrow(
        () -> new MaintenanceNotFoundException("Selected estimate not found"));
    if (!assetId.equals(estimate.getRentalItemId()) || !warehouseId.equals(estimate.getWarehouseId())) {
      throw conflict("Selected estimate does not belong to this inventory asset and warehouse");
    }
    if (estimate.getState() != EstimateState.DRAFT || estimate.getInventorySupersededAt() != null) {
      throw conflict("Selected estimate is no longer an active unstarted draft");
    }
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.ESTIMATE, estimateId);
    if (expectedVersion != estimate.getVersion()) {
      throw conflict("Selected estimate event stream does not match its current version");
    }
    estimate.supersedeForInventoryPublication();
    MaintenanceEstimate saved = estimates.saveAndFlush(estimate);
    Map<String, Object> state = projectionSnapshots.estimate(saved);
    events.append(
        MaintenanceAggregateType.ESTIMATE,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.ESTIMATE_DRAFT_CHANGED,
        state,
        eventFacts.estimatePayload(
            MaintenanceEventType.ESTIMATE_DRAFT_CHANGED,
            saved,
            Math.toIntExact(estimateLines.countByEstimateIdAndEstimateRevision(
                saved.getId(), saved.getRevision()))),
        state);
    return new SupersededTarget(InventoryPublicationTargetKind.ESTIMATE, saved.getId());
  }

  private StrategyResolution resolveRepairStrategy(
      InventoryPublicationStrategy strategy,
      UUID repairId,
      UUID assetId,
      UUID warehouseId) {
    MaintenanceRepair repair = repairs.findAllByIdForUpdate(List.of(repairId)).stream()
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Selected repair not found"));
    if (!assetId.equals(repair.getRentalItemId()) || !warehouseId.equals(repair.getWarehouseId())) {
      throw conflict("Selected repair does not belong to this inventory asset and warehouse");
    }
    if (repair.getExecutionState() == RepairExecutionState.DRAFT) {
      return StrategyResolution.superseded(supersedeDraftRepair(repair));
    }
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      throw queuedReplacementConflict(repair);
    }
    if (repair.getExecutionState() != RepairExecutionState.IN_PROGRESS
        && repair.getExecutionState() != RepairExecutionState.COMPLETED) {
      throw conflict("Selected repair is not an active maintenance predecessor");
    }
    if (repair.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || repair.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw conflict("Selected repair already has a terminal acceptance decision");
    }
    if (strategy != InventoryPublicationStrategy.MERGE) {
      throw conflict(
          "REPLACE cannot alter started repair work; use MERGE to create a successor after it");
    }
    return StrategyResolution.predecessor(repair, terminalProof(repair));
  }

  private MaintenanceConflictException queuedReplacementConflict(MaintenanceRepair repair) {
    if (repair.isMovementToRepair()) {
      return conflict(
          "Queued repair has an inbound delivery whose pending/completed movement is not "
              + "cancellable or queryable through the maintenance logistics contract");
    }
    if (repair.getReclassificationState()
        == dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState.EXTERNAL_CAPITAL) {
      return conflict(
          "Queued external-capital handoff cannot be replaced before an acceptance fact");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    boolean taskBoardEffect = repair.getTaskBoardVersion() != null
        || stages.stream().anyMatch(stage -> stage.getExternalQueueEntryId() != null);
    if (taskBoardEffect) {
      return conflict(
          "Queued repair has task-board state; the current maintenance contract cannot "
              + "atomically prove a pre-start cancellation");
    }
    return conflict(
        "Queued repair owns a pending asset/lease lifecycle; no approved maintenance "
            + "pre-start compensation is available");
  }

  private TerminalProof terminalProof(MaintenanceRepair repair) {
    if (repair.getExecutionState() != RepairExecutionState.COMPLETED
        || repair.getReclassificationState()
            == dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState.EXTERNAL_CAPITAL) {
      return null;
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repair.getId());
    RepairStage terminal = stages.stream()
        .filter(stage -> stage.getState() == dev.buhanzaz.rwms.maintenance.domain.RepairStageState.DONE)
        .filter(stage -> stage.getCompletedEventId() != null)
        .max(Comparator.comparing(RepairStage::getCompletedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(RepairStage::getId))
        .orElseThrow(() -> conflict(
            "Completed repair has no task-board completion fact for successor ordering"));
    if (stages.isEmpty()
        || stages.stream().anyMatch(stage -> stage.getState()
            != dev.buhanzaz.rwms.maintenance.domain.RepairStageState.DONE)) {
      throw conflict("Completed repair has incomplete task-board stage truth for successor ordering");
    }
    return new TerminalProof(terminal.getCompletedEventId(), terminal.getCompletedAt());
  }

  /**
   * An external-capital acceptance may commit after logistics has answered STARTED but before
   * the successor relation is inserted. Its ordinary callback would see no relation, so recover
   * the exact accepted event from the local authoritative event store in this locked transaction.
   */
  private TerminalProof acceptanceProof(MaintenanceRepair repair) {
    if (repair.getReclassificationState()
            != dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState.EXTERNAL_CAPITAL
        || repair.getAcceptanceState() != RepairAcceptanceState.ACCEPTED) {
      return null;
    }
    return events
        .latestFact(
            MaintenanceAggregateType.REPAIR, repair.getId(), MaintenanceEventType.REPAIR_ACCEPTED)
        .map(value -> new TerminalProof(value.eventId(), value.occurredAt()))
        .orElseThrow(
            () ->
                conflict(
                    "Accepted external-capital predecessor has no persisted acceptance event "
                        + "for inventory successor ordering"));
  }

  private SupersededTarget supersedeDraftRepair(MaintenanceRepair repair) {
    UUID repairId = repair.getId();
    long expectedVersion = events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repairId);
    if (expectedVersion != repair.getVersion()) {
      throw conflict("Selected repair event stream does not match its current version");
    }
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repairId);
    stages.forEach(RepairStage::supersedeForInventoryPublication);
    repair.supersedeForInventoryPublication();
    repairStages.saveAllAndFlush(stages);
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    reconciliations.cancelPendingForRepair(saved.getId());
    Map<String, Object> state = projectionSnapshots.repair(saved);
    events.append(
        MaintenanceAggregateType.REPAIR,
        saved.getId(),
        expectedVersion,
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        state,
        eventFacts.repairPayload(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved, stages),
        state);
    return new SupersededTarget(InventoryPublicationTargetKind.REPAIR, saved.getId());
  }

  private TargetCreated createEstimate(
      InventoryPublicationSourceId sourceId,
      UUID warehouseId,
      InventoryPublicationFindingInput finding,
      ValidatedPublication publication,
      UUID incomingAdmissionWarehouseId) {
    if (!warehouseId.equals(incomingAdmissionWarehouseId)) {
      throw PublicationRemotePreflightRequired.incoming(warehouseId);
    }
    FrozenInventoryPlanSnapshot snapshot = publication.snapshot();
    MaintenanceEstimate draft = MaintenanceEstimate.create(
        warehouseId,
        finding.assetId(),
        finding.assetVersion(),
        snapshot.catalogVersionId(),
        finding.repairScheduledDate(),
        "Инвентаризация",
        null,
        inventoryActorJson());
    draft.selectInventoryPublication(
        finding.priority(), finding.movementToRepair(), finding.movementScheduledDate());
    draft.replaceCoverMediaId(snapshot.coverMediaId());
    MaintenanceEstimate estimate = estimates.saveAndFlush(draft);
    List<PublishedLine> lines = publicationLines(sourceId, snapshot);
    List<PublishedStage> stages = allocateStages(snapshot, lines);
    saveEstimatePlan(estimate, lines, stages);
    attachMedia(
        "ESTIMATE",
        "MAINTENANCE_ESTIMATE",
        estimate.getId(),
        warehouseId,
        publication.sourceMedia());
    Map<String, Object> state = projectionSnapshots.estimate(estimate);
    events.initialize(
        MaintenanceAggregateType.ESTIMATE,
        estimate.getId(),
        estimate.getVersion(),
        MaintenanceEventType.ESTIMATE_CREATED,
        state,
        eventFacts.estimatePayload(
            MaintenanceEventType.ESTIMATE_CREATED, estimate, lines.size()),
        state);
    reconciliations.enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        estimate.getId(),
        warehouseId,
        estimate.getId(),
        estimate.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        estimate.getWarehouseId(), estimate.getId(), estimate.getCreatedAt());
    return new TargetCreated(
        InventoryPublicationTargetKind.ESTIMATE, estimate.getId(), estimate.getId(), null);
  }

  private TargetCreated createRepair(
      InventoryPublicationSourceId sourceId,
      UUID warehouseId,
      InventoryPublicationFindingInput finding,
      ValidatedPublication publication,
      RepairPublicationPlan plan,
      boolean enqueueImmediately,
      UUID incomingAdmissionWarehouseId,
      List<InventoryPlanStageSnapshot> routingPreflightStages) {
    if (!warehouseId.equals(incomingAdmissionWarehouseId)) {
      throw PublicationRemotePreflightRequired.incoming(warehouseId);
    }
    FrozenInventoryPlanSnapshot snapshot = publication.snapshot();
    List<InventoryPlanStageSnapshot> requiredRoutingStages = plan.allocations().stream()
        .filter(allocation -> !allocation.lines().isEmpty())
        .map(PublishedStage::stage)
        .toList();
    if (!requiredRoutingStages.equals(routingPreflightStages)) {
      throw PublicationRemotePreflightRequired.routing(
          warehouseId, requiredRoutingStages);
    }
    RepairLogisticsPlanningMode planningMode = finding.movementToRepair()
        ? finding.movementScheduledDate() == null
            ? RepairLogisticsPlanningMode.AUTO
            : RepairLogisticsPlanningMode.FIXED_DATE
        : null;
    MaintenanceRepair draft = MaintenanceRepair.primary(
        warehouseId,
        finding.assetId(),
        finding.assetVersion(),
        null,
        RepairOrigin.INVENTORY,
        finding.repairScheduledDate(),
        "Инвентаризация",
        inventoryActorJson());
    draft.selectPriority(finding.priority());
    draft.selectMovementToRepair(
        finding.movementToRepair(), planningMode, finding.movementScheduledDate());
    draft.replaceCoverMediaId(plan.coverMediaId());
    MaintenanceRepair repair = repairs.saveAndFlush(draft);
    warehouseLifecycle.recordOperation(
        repair.getWarehouseId(), repair.getId(), repair.getCreatedAt());
    List<RepairStage> stages = plan.allocations().stream()
        .filter(allocation -> !allocation.lines().isEmpty())
        .map(
            allocation ->
                new RepairStage(
                    allocation.stage().id(),
                    repair.getId(),
                    allocation.stage().order(),
                    allocation.stage().kind(),
                    allocation.stage().routing().queueId(),
                    allocation.stage().routing().queueName(),
                    allocation.stage().routing().queueType(),
                    write(allocation.workLines()),
                    write(allocation.materialLines()),
                    allocation.primaryLineId(),
                    "",
                    null))
        .toList();
    repairStages.saveAllAndFlush(stages);
    attachMedia(
        "REPAIR",
        "MAINTENANCE_REPAIR",
        repair.getId(),
        warehouseId,
        plan.sourceMedia());
    Map<String, Object> state = projectionSnapshots.repair(repair);
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        state,
        eventFacts.repairPayload(MaintenanceEventType.REPAIR_CREATED, repair, stages),
        state);
    reconciliations.enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        warehouseId,
        repair.getId(),
        repair.getVersion(),
        true);
    if (enqueueImmediately) {
      repairQueue.enqueue(repair.getId(), stableKey("inventory-publication-queue-repair", sourceId));
    }
    return new TargetCreated(InventoryPublicationTargetKind.REPAIR, repair.getId(), null, repair.getId());
  }

  private void saveEstimatePlan(
      MaintenanceEstimate estimate,
      List<PublishedLine> lines,
      List<PublishedStage> stages) {
    List<EstimateLine> storedLines = lines.stream()
        .map(
            line ->
                new EstimateLine(
                    line.response().id(),
                    estimate.getId(),
                    estimate.getRevision(),
                    line.sourceIndex(),
                    line.catalogNodeId(),
                    line.response().lineType().name(),
                    line.response().description(),
                    line.response().unit(),
                    line.quantity(),
                    line.unitPriceMinor(),
                    line.response().normativeMinutes(),
                    line.queueRef(),
                    line.catalogSnapshot() == null ? null : write(line.catalogSnapshot()),
                    line.response().comment(),
                    write(line.response().mediaReferences())))
        .toList();
    List<EstimatePlanStage> storedStages = stages.stream()
        .map(
            stage ->
                new EstimatePlanStage(
                    stage.stage().id(),
                    estimate.getId(),
                    estimate.getRevision(),
                    stage.stage().order(),
                    stage.stage().kind(),
                    stage.stage().routing().queueId(),
                    stage.stage().routing().queueName(),
                    stage.stage().routing().queueType(),
                    write(stage.lines().stream().map(line -> line.response().id()).toList()),
                    stage.primaryLineId(),
                    "",
                    null))
        .toList();
    estimateLines.saveAll(storedLines);
    estimatePlans.saveAll(storedStages);
    long totalMinor = storedLines.stream()
        .map(line -> line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
    estimateRevisions.saveAndFlush(new EstimateRevision(
        estimate.getId(),
        estimate.getRevision(),
        estimate.getDispatchDate(),
        estimate.getSourceParty(),
        null,
        totalMinor,
        inventoryActorJson()));
  }

  private void attachMedia(
      String aggregateType,
      String ownerType,
      UUID aggregateId,
      UUID warehouseId,
      List<MediaReferenceInput> references) {
    if (references.isEmpty()) return;
    List<MaintenanceMediaReference> values = references.stream()
        .map(
            reference ->
                new MaintenanceMediaReference(
                    aggregateType,
                    aggregateId,
                    reference.mediaId(),
                    reference.generation(),
                    ownerType,
                    warehouseId,
                    mediaFacts.findById(reference.mediaId())
                        .map(MediaFactProjection::getSafeMetadata)
                        .orElse("{}")))
        .toList();
    mediaReferences.saveAll(values);
  }

  private List<InventoryPublicationCandidate> candidates(UUID assetId, UUID warehouseId) {
    List<InventoryPublicationCandidate> result = new ArrayList<>();
    for (MaintenanceEstimate estimate : estimates.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(assetId)) {
      if (!warehouseId.equals(estimate.getWarehouseId())) continue;
      InventoryPublicationPlanSummary summary = estimateSummary(estimate);
      InventoryPublicationSource source = sources.findByEstimateId(estimate.getId()).orElse(null);
      boolean superseded = estimate.getInventorySupersededAt() != null;
      result.add(
          new InventoryPublicationCandidate(
              InventoryPublicationTargetKind.ESTIMATE,
              estimate.getId(),
              estimate.getId(),
              null,
              estimate.getVersion(),
              superseded ? "SUPERSEDED" : estimate.getState().name(),
              estimate.getState() != EstimateState.DRAFT,
              active(estimate),
              estimate.getPriority(),
              estimate.getSourceParty(),
              source == null ? null : source.getPlanFingerprintSha256(),
              summary));
    }
    for (MaintenanceRepair repair : repairs.findAllByRentalItemIdOrderByCreatedAtAscIdAsc(assetId)) {
      if (!warehouseId.equals(repair.getWarehouseId())) continue;
      InventoryPublicationPlanSummary summary = repairSummary(repair);
      String fingerprint = sourceFingerprint(repair.getId());
      result.add(
          new InventoryPublicationCandidate(
              InventoryPublicationTargetKind.REPAIR,
              repair.getId(),
              null,
              repair.getId(),
              repair.getVersion(),
              repair.getExecutionState().name(),
              prestartCandidate(repair) == null
                  && (repair.getExecutionState() == RepairExecutionState.IN_PROGRESS
                      || repair.getExecutionState() == RepairExecutionState.COMPLETED),
              active(repair),
              repair.getPriority(),
              repair.getSourceParty(),
              fingerprint,
              summary));
    }
    result.sort(
        Comparator.comparing((InventoryPublicationCandidate value) -> value.targetKind().name())
            .thenComparing(value -> value.targetId().toString()));
    return List.copyOf(result);
  }

  private String sourceFingerprint(UUID repairId) {
    InventoryPublicationSource publication = sources.findByRepairId(repairId).orElse(null);
    if (publication != null) return publication.getPlanFingerprintSha256();
    InventoryRepairSource legacy = legacySources.findByRepairId(repairId).orElse(null);
    return legacy == null ? null : legacy.getPlanFingerprint();
  }

  private InventoryPublicationPlanSummary estimateSummary(MaintenanceEstimate estimate) {
    return summary(estimateLines.findAllByEstimateIdAndEstimateRevisionOrderByLineNo(
        estimate.getId(), estimate.getRevision()).stream()
        .map(
            line ->
                new LineAmount(
                    "WORK".equals(line.getLineType()),
                    line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor()))))
        .toList());
  }

  private InventoryPublicationPlanSummary repairSummary(MaintenanceRepair repair) {
    Map<UUID, EstimateLineResponse> lines = new LinkedHashMap<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      for (EstimateLineResponse line : readList(stage.getWorkLines(), EstimateLineResponse.class)) {
        lines.putIfAbsent(line.id(), line);
      }
      for (EstimateLineResponse line : readList(stage.getMaterialLines(), EstimateLineResponse.class)) {
        lines.putIfAbsent(line.id(), line);
      }
    }
    return summary(lines.values().stream()
        .map(
            line ->
                new LineAmount(
                    line.lineType() == EstimateLineType.WORK,
                    new BigDecimal(line.quantity()).multiply(
                        moneyToMinor(line.unitPrice()))))
        .toList());
  }

  private static InventoryPublicationPlanSummary summary(List<LineAmount> values) {
    int work = 0;
    int material = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (LineAmount value : values) {
      if (value.work()) work++;
      else material++;
      total = total.add(value.totalMinor());
    }
    return new InventoryPublicationPlanSummary(
        work, material, total.setScale(0, RoundingMode.HALF_UP).longValueExact());
  }

  private boolean active(MaintenanceEstimate estimate) {
    return estimate.getState() == EstimateState.DRAFT && estimate.getInventorySupersededAt() == null;
  }

  private static boolean active(MaintenanceRepair repair) {
    return repair.getExecutionState() != RepairExecutionState.CANCELLED
        && repair.getAcceptanceState() != RepairAcceptanceState.ACCEPTED
        && repair.getAcceptanceState() != RepairAcceptanceState.WRITTEN_OFF;
  }

  private ValidatedPublication validatePublication(
      UUID warehouseId, InventoryPublicationFindingInput finding) {
    if (warehouseId == null || finding == null) {
      throw invalid("Inventory publication warehouse and finding are required");
    }
    if (!finding.movementToRepair() && finding.movementScheduledDate() != null) {
      throw invalid("Inbound movement date must be absent when movement to repair is disabled");
    }
    if (finding.snapshot() == null || !finding.snapshot().isObject()) {
      throw invalid("Inventory publication snapshot must be a JSON object");
    }
    String rawFingerprint = canonicalizer.sha256(finding.snapshot());
    if (!rawFingerprint.equals(finding.planFingerprintSha256())) {
      throw conflict("Inventory publication plan fingerprint does not match the raw frozen snapshot");
    }
    FrozenInventoryPlanSnapshot snapshot = adaptSnapshot(
        finding.snapshotSchemaVersion(), finding.snapshot());
    validateSnapshot(snapshot);
    List<MediaReferenceInput> sourceMedia = sourceMedia(snapshot);
    if (sourceMedia.size() > 100 || !sourceMedia.equals(finding.media())) {
      throw conflict("Inventory publication media must equal the deterministic frozen plan media union");
    }
    validateEvidenceMedia(finding.findingId(), warehouseId, sourceMedia);
    return new ValidatedPublication(snapshot, sourceMedia, write(finding.snapshot()), write(finding.media()));
  }

  private FrozenInventoryPlanSnapshot adaptSnapshot(int schemaVersion, JsonNode raw) {
    if (schemaVersion != 1 && schemaVersion != 2) {
      throw invalid("Inventory publication snapshot schema version must be 1 or 2");
    }
    ObjectNode executable = ((ObjectNode) raw).deepCopy();
    JsonNode legacyOutbound = executable.get("movementToShipment");
    if (schemaVersion == 1) {
      if (legacyOutbound != null && !legacyOutbound.isBoolean()) {
        throw invalid("Inventory snapshot schema version 1 has an invalid movementToShipment marker");
      }
      executable.remove("movementToShipment");
    } else if (legacyOutbound != null) {
      throw invalid("Inventory snapshot schema version 2 must not contain movementToShipment");
    }
    if (schemaVersion == 2) {
      adaptLegacyManualLineRouting(executable);
    }
    try {
      return mapper.treeToValue(executable, FrozenInventoryPlanSnapshot.class);
    } catch (JacksonException exception) {
      throw invalid("Inventory publication snapshot is not a valid frozen maintenance plan");
    }
  }

  /**
   * Schema-v2 snapshots predate explicit manual-line routing. The raw snapshot remains the
   * fingerprinted audit fact; this only supplies an executable route after that fingerprint has
   * already been verified by {@link #validatePublication(UUID, InventoryPublicationFindingInput)}.
   */
  private void adaptLegacyManualLineRouting(ObjectNode executable) {
    JsonNode rawLines = executable.get("lines");
    if (rawLines == null || !rawLines.isArray()) {
      return;
    }
    List<ObjectNode> missingRoutes = new ArrayList<>();
    for (JsonNode rawLine : rawLines) {
      if (rawLine.isObject()
          && "MANUAL".equals(rawLine.path("aggregationKind").asText())
          && rawLine.has("routing")
          && rawLine.get("routing").isNull()) {
        missingRoutes.add((ObjectNode) rawLine);
      }
    }
    if (missingRoutes.isEmpty()) {
      return;
    }

    JsonNode rawStages = executable.get("stages");
    if (rawStages == null || !rawStages.isArray()) {
      throw invalid("Inventory publication legacy manual routing has no valid selected stage route");
    }
    Set<RoutingSnapshot> routes = new LinkedHashSet<>();
    for (JsonNode rawStage : rawStages) {
      if (!rawStage.isObject()
          || !RepairStageKind.REPAIR_WORK.name().equals(rawStage.path("kind").asText())) {
        throw invalid("Inventory publication legacy manual routing has no valid selected stage route");
      }
      try {
        RoutingSnapshot routing = mapper.treeToValue(rawStage.get("routing"), RoutingSnapshot.class);
        if (routing == null) {
          throw invalid("Inventory publication legacy manual routing has no valid selected stage route");
        }
        routes.add(routing);
      } catch (JacksonException | IllegalArgumentException exception) {
        throw invalid("Inventory publication legacy manual routing has no valid selected stage route");
      }
    }
    if (routes.size() != 1) {
      throw invalid("Inventory publication legacy manual routing is ambiguous");
    }
    JsonNode routing = mapper.valueToTree(routes.iterator().next());
    for (ObjectNode line : missingRoutes) {
      line.set("routing", routing.deepCopy());
    }
  }

  private void validateSnapshot(FrozenInventoryPlanSnapshot snapshot) {
    if (snapshot.catalogVersionId() == null
        || snapshot.lines() == null
        || snapshot.lines().isEmpty()
        || snapshot.stages() == null
        || snapshot.stages().isEmpty()
        || snapshot.mediaReferences() == null
        || snapshot.priority() == null
        || snapshot.priority() < 1
        || snapshot.priority() > 5
        || !snapshot.isLogisticsPlanningValid()) {
      throw invalid("Inventory publication frozen plan is invalid");
    }
    if (snapshot.lines().stream().noneMatch(line -> line.type() == InventoryPlanLineType.WORK)) {
      throw invalid("Completed inventory publication requires a nonempty frozen work plan");
    }
    validateCover(snapshot.mediaReferences(), snapshot.coverMediaId());
    validateWorkLineMediaIsolation(snapshot.lines());
    for (InventoryPlanStageSnapshot stage : snapshot.stages()) {
      if (stage.id() == null
          || stage.routing() == null
          || stage.kind() != RepairStageKind.REPAIR_WORK) {
        throw invalid("Inventory publication stage is incomplete");
      }
    }
    for (InventoryPlanLineSnapshot line : snapshot.lines()) {
      if (line.mediaReferences() == null) {
        throw invalid("Inventory publication line media are required");
      }
      if (line.routing() == null) {
        throw invalid("Every inventory plan line requires a frozen routing snapshot");
      }
      if (snapshot.stages().stream()
          .noneMatch(stage -> sameRoute(line.routing(), stage.routing()))) {
        throw invalid("Every inventory line route must match a selected repair-work stage");
      }
    }
  }

  private static boolean sameRoute(RoutingSnapshot first, RoutingSnapshot second) {
    return first.queueId().equals(second.queueId())
        && first.queueType().equals(second.queueType());
  }

  private void validateEvidenceMedia(
      UUID findingId, UUID warehouseId, List<MediaReferenceInput> references) {
    Set<UUID> unique = new HashSet<>();
    for (MediaReferenceInput reference : references) {
      if (reference == null || reference.mediaId() == null || reference.generation() == null
          || !unique.add(reference.mediaId())) {
        throw invalid("Inventory publication media references must be unique and complete");
      }
      MediaFactProjection fact = mediaFacts.findById(reference.mediaId()).orElseThrow(
          () -> new MaintenanceValidationException(
              "MAINTENANCE_MEDIA_NOT_READY", "Inventory media fact is not known"));
      if (fact.getGeneration() != reference.generation()
          || !"READY".equals(fact.getMediaStatus())
          || !"INVENTORY_FINDING".equals(fact.getOwnerType())
          || !findingId.equals(fact.getOwnerId())
          || !warehouseId.equals(fact.getWarehouseId())) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_MEDIA_NOT_READY",
            "Inventory media owner, generation, warehouse or status does not match");
      }
    }
  }

  private void requireWarehouseRoutingReady(
      UUID warehouseId, List<InventoryPlanStageSnapshot> stages) {
    Map<UUID, MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        new LinkedHashMap<>();
    for (InventoryPlanStageSnapshot stage : stages) {
      String type = stage.routing().queueType().trim().toUpperCase(java.util.Locale.ROOT);
      MaintenanceDependencyGateway.RoutingQueueRequirement requirement =
          new MaintenanceDependencyGateway.RoutingQueueRequirement(stage.routing().queueId(), type);
      MaintenanceDependencyGateway.RoutingQueueRequirement previous =
          requirements.putIfAbsent(requirement.queueDefinitionId(), requirement);
      if (previous != null && !previous.equals(requirement)) {
        throw invalid("One inventory queue definition has conflicting routing snapshots");
      }
    }
    if (requirements.isEmpty()) {
      throw invalid("Inventory repair plan requires at least one queue definition");
    }
    MaintenanceDependencyGateway.RoutingPreflight preflight =
        dependencies.preflightMaintenanceRouting(warehouseId, List.copyOf(requirements.values()));
    if (preflight == null || !warehouseId.equals(preflight.warehouseId())) {
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board omitted warehouse routing truth for the inventory repair");
    }
    if (!preflight.ready()) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_ROUTING_INVALID",
          "A required global queue is not connected to this warehouse");
    }
    Map<UUID, String> resolved = preflight.queues().stream().collect(
        Collectors.toMap(
            MaintenanceDependencyGateway.RoutingQueueSnapshot::queueDefinitionId,
            queue -> queue.type().trim().toUpperCase(java.util.Locale.ROOT)));
    boolean complete = resolved.size() == requirements.size()
        && requirements.entrySet().stream().allMatch(
            entry -> entry.getValue().type().equals(resolved.get(entry.getKey())));
    if (!complete) {
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned incomplete warehouse routing truth for the inventory repair");
    }
  }

  private List<PublishedLine> publicationLines(
      InventoryPublicationSourceId sourceId, FrozenInventoryPlanSnapshot snapshot) {
    List<PublishedLine> result = new ArrayList<>();
    for (int index = 0; index < snapshot.lines().size(); index++) {
      InventoryPlanLineSnapshot line = snapshot.lines().get(index);
      UUID lineId = stableId(sourceId, "line", index);
      BigDecimal quantity = new BigDecimal(line.quantity());
      long totalMinor = quantity.multiply(BigDecimal.valueOf(line.unitPriceMinor()))
          .setScale(0, RoundingMode.HALF_UP)
          .longValueExact();
      int duration = new BigDecimal(line.normativeMinutes())
          .setScale(0, RoundingMode.CEILING)
          .intValueExact();
      CatalogNodeSnapshot catalog = line.aggregationKind() == InventoryPlanLineKind.CATALOG
          ? new CatalogNodeSnapshot(
              line.catalogVersionId(),
              line.catalogNodeId(),
              line.type() == InventoryPlanLineType.WORK
                  ? CatalogNodeType.WORK
                  : CatalogNodeType.MATERIAL,
              line.catalogNodeName(),
              line.unit(),
              money(line.unitPriceMinor()),
              duration,
              line.routing(),
              null,
              line.forcesCapitalRepair(),
              line.characteristic())
          : null;
      EstimateLineResponse response = new EstimateLineResponse(
          lineId,
          catalog,
          line.type() == InventoryPlanLineType.WORK ? EstimateLineType.WORK : EstimateLineType.MATERIAL,
          line.description(),
          line.unit(),
          quantity.stripTrailingZeros().toPlainString(),
          money(line.unitPriceMinor()),
          money(totalMinor),
          duration,
          line.type() == InventoryPlanLineType.WORK ? line.groupComment() : null,
          line.mediaReferences());
      result.add(
          new PublishedLine(
              index,
              line,
              response,
              quantity,
              line.unitPriceMinor(),
              catalog,
              line.catalogNodeId(),
              line.routing() == null ? null : line.routing().queueId().toString()));
    }
    return List.copyOf(result);
  }

  private RepairPublicationPlan fullRepairPlan(
      InventoryPublicationSourceId sourceId,
      FrozenInventoryPlanSnapshot snapshot,
      List<MediaReferenceInput> sourceMedia) {
    List<PublishedLine> lines = publicationLines(sourceId, snapshot);
    return new RepairPublicationPlan(
        lines, allocateStages(snapshot, lines), sourceMedia, snapshot.coverMediaId());
  }

  private static InventoryPublicationDelta fullDelta(FrozenInventoryPlanSnapshot snapshot) {
    List<InventoryPublicationDeltaLine> lines = new ArrayList<>();
    for (int index = 0; index < snapshot.lines().size(); index++) {
      InventoryPlanLineSnapshot line = snapshot.lines().get(index);
      lines.add(new InventoryPublicationDeltaLine(
          index,
          InventoryPublicationDeltaDisposition.RETAINED,
          line.type(),
          line.aggregationKind() == InventoryPlanLineKind.CATALOG ? line.catalogNodeId() : null,
          quantityText(positiveQuantity(line.quantity(), "Frozen inventory line quantity")),
          quantityText(positiveQuantity(line.quantity(), "Frozen inventory line quantity"))));
    }
    return new InventoryPublicationDelta(List.copyOf(lines));
  }

  /**
   * Builds the exact successor plan without relying on a task-board read. The predecessor's
   * persisted task assignment is authoritative for duplicate prevention: catalog entries use the
   * stable (node, type) identity, while manual entries deduct only after an exact canonical
   * semantic match. Similar but not identical manual lines are deliberately retained.
   */
  private DeltaRepairPlan successorPlan(
      InventoryPublicationSourceId sourceId,
      FrozenInventoryPlanSnapshot snapshot,
      List<MediaReferenceInput> sourceMedia,
      MaintenanceRepair predecessor) {
    RepairPublicationPlan full = fullRepairPlan(sourceId, snapshot, sourceMedia);
    ExistingAssignments assigned = predecessorAssignments(predecessor);
    Map<Integer, UUID> routes = new LinkedHashMap<>();
    for (PublishedStage allocation : full.allocations()) {
      for (PublishedLine line : allocation.lines()) {
        routes.put(line.sourceIndex(), allocation.stage().routing().queueId());
      }
    }

    List<PublishedLine> retained = new ArrayList<>();
    List<InventoryPublicationDeltaLine> decisions = new ArrayList<>();
    for (PublishedLine line : full.lines()) {
      UUID routeId = routes.get(line.sourceIndex());
      if (routeId == null) {
        throw new IllegalStateException("Inventory successor line has no allocated repair route");
      }
      SemanticLineKey key = semanticKey(line, routeId);
      BigDecimal requested = line.quantity();
      BigDecimal assignedQuantity = assigned.quantities().getOrDefault(key, BigDecimal.ZERO);
      BigDecimal deducted = requested.min(assignedQuantity);
      BigDecimal remaining = requested.subtract(deducted);
      if (deducted.signum() > 0) {
        BigDecimal available = assignedQuantity.subtract(deducted);
        if (available.signum() == 0) {
          assigned.quantities().remove(key);
        } else {
          assigned.quantities().put(key, available);
        }
      }

      InventoryPublicationDeltaDisposition disposition;
      if (remaining.signum() == 0) {
        disposition = InventoryPublicationDeltaDisposition.REMOVED_AS_ALREADY_PRESENT;
      } else if (deducted.signum() > 0) {
        disposition = InventoryPublicationDeltaDisposition.RETAINED_AFTER_DEDUCTION;
        retained.add(withQuantity(line, remaining));
      } else if (line.source().aggregationKind() == InventoryPlanLineKind.MANUAL
          && assigned.manualComparable().contains(manualComparableKey(line.source()))) {
        disposition = InventoryPublicationDeltaDisposition.RETAINED_AMBIGUOUS;
        retained.add(line);
      } else {
        disposition = InventoryPublicationDeltaDisposition.RETAINED;
        retained.add(line);
      }
      decisions.add(new InventoryPublicationDeltaLine(
          line.sourceIndex(),
          disposition,
          line.source().type(),
          line.source().aggregationKind() == InventoryPlanLineKind.CATALOG
              ? line.source().catalogNodeId() : null,
          quantityText(requested),
          quantityText(remaining)));
    }

    if (retained.isEmpty()) {
      return new DeltaRepairPlan(
          new RepairPublicationPlan(List.of(), List.of(), List.of(), null),
          new InventoryPublicationDelta(List.copyOf(decisions)));
    }
    List<PublishedStage> allocations = allocateStages(snapshot, retained).stream()
        .filter(allocation -> !allocation.lines().isEmpty())
        .toList();
    List<MediaReferenceInput> retainedMedia = retainedMedia(snapshot, retained);
    UUID requestedCoverMediaId = snapshot.coverMediaId();
    UUID coverMediaId = requestedCoverMediaId != null
            && retainedMedia.stream()
                .map(MediaReferenceInput::mediaId)
                .anyMatch(requestedCoverMediaId::equals)
        ? requestedCoverMediaId : null;
    return new DeltaRepairPlan(
        new RepairPublicationPlan(
            List.copyOf(retained), allocations, retainedMedia, coverMediaId),
        new InventoryPublicationDelta(List.copyOf(decisions)));
  }

  private ExistingAssignments predecessorAssignments(MaintenanceRepair predecessor) {
    Map<SemanticLineKey, BigDecimal> quantities = new LinkedHashMap<>();
    Set<ManualComparableKey> manualComparable = new HashSet<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(predecessor.getId())) {
      addPredecessorAssignments(
          quantities,
          manualComparable,
          stage,
          readList(stage.getWorkLines(), EstimateLineResponse.class));
      addPredecessorAssignments(
          quantities,
          manualComparable,
          stage,
          readList(stage.getMaterialLines(), EstimateLineResponse.class));
    }
    return new ExistingAssignments(quantities, manualComparable);
  }

  private static void addPredecessorAssignments(
      Map<SemanticLineKey, BigDecimal> quantities,
      Set<ManualComparableKey> manualComparable,
      RepairStage stage,
      List<EstimateLineResponse> lines) {
    for (EstimateLineResponse line : lines) {
      InventoryPlanLineType type = line.lineType() == EstimateLineType.WORK
          ? InventoryPlanLineType.WORK : InventoryPlanLineType.MATERIAL;
      BigDecimal quantity = positiveQuantity(line.quantity(), "Stored predecessor line quantity");
      SemanticLineKey key;
      if (line.catalogSnapshot() != null) {
        key = SemanticLineKey.catalog(type, line.catalogSnapshot().nodeId());
      } else {
        key = SemanticLineKey.manual(
            type,
            canonicalManualSignature(
                line.description(),
                line.unit(),
                moneyToMinor(line.unitPrice()).longValueExact(),
                line.normativeMinutes(),
                line.comment(),
                stage.getRoutingQueueId()));
        manualComparable.add(manualComparableKey(type, line.description(), line.unit()));
      }
      quantities.merge(key, quantity, BigDecimal::add);
    }
  }

  private static SemanticLineKey semanticKey(PublishedLine line, UUID routeId) {
    if (line.source().aggregationKind() == InventoryPlanLineKind.CATALOG) {
      if (line.source().catalogNodeId() == null) {
        throw new IllegalStateException("Catalog inventory successor line has no catalog node");
      }
      return SemanticLineKey.catalog(line.source().type(), line.source().catalogNodeId());
    }
    return SemanticLineKey.manual(
        line.source().type(),
        canonicalManualSignature(
            line.source().description(),
            line.source().unit(),
            line.source().unitPriceMinor(),
            new BigDecimal(line.source().normativeMinutes())
                .setScale(0, RoundingMode.CEILING)
                .intValueExact(),
            line.source().groupComment(),
            routeId));
  }

  private static ManualComparableKey manualComparableKey(InventoryPlanLineSnapshot line) {
    return manualComparableKey(line.type(), line.description(), line.unit());
  }

  private static ManualComparableKey manualComparableKey(
      InventoryPlanLineType type, String description, String unit) {
    return new ManualComparableKey(type, canonicalText(description), canonicalText(unit));
  }

  private static String canonicalManualSignature(
      String description,
      String unit,
      long unitPriceMinor,
      int normativeMinutes,
      String groupComment,
      UUID routeId) {
    if (routeId == null || unitPriceMinor < 0 || normativeMinutes < 0) {
      throw new IllegalStateException("Manual inventory successor signature is incomplete");
    }
    return canonicalText(description)
        + "\u001f"
        + canonicalText(unit)
        + "\u001f"
        + unitPriceMinor
        + "\u001f"
        + normativeMinutes
        + "\u001f"
        + canonicalText(groupComment)
        + "\u001f"
        + routeId;
  }

  private static String canonicalText(String value) {
    if (value == null || value.isBlank()) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  private static BigDecimal positiveQuantity(String value, String subject) {
    try {
      BigDecimal parsed = new BigDecimal(value);
      if (parsed.signum() <= 0) {
        throw new IllegalArgumentException(subject + " must be positive");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalStateException(subject + " is invalid", exception);
    }
  }

  private static String quantityText(BigDecimal quantity) {
    return quantity.stripTrailingZeros().toPlainString();
  }

  private static PublishedLine withQuantity(PublishedLine line, BigDecimal quantity) {
    if (quantity.signum() <= 0) {
      throw new IllegalArgumentException("Retained inventory successor quantity must be positive");
    }
    EstimateLineResponse response = line.response();
    long totalMinor = quantity.multiply(BigDecimal.valueOf(line.unitPriceMinor()))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
    EstimateLineResponse adjusted = new EstimateLineResponse(
        response.id(),
        response.catalogSnapshot(),
        response.lineType(),
        response.description(),
        response.unit(),
        quantityText(quantity),
        money(line.unitPriceMinor()),
        money(totalMinor),
        response.normativeMinutes(),
        response.comment(),
        response.mediaReferences());
    return new PublishedLine(
        line.sourceIndex(),
        line.source(),
        adjusted,
        quantity,
        line.unitPriceMinor(),
        line.catalogSnapshot(),
        line.catalogNodeId(),
        line.queueRef());
  }

  private static List<MediaReferenceInput> retainedMedia(
      FrozenInventoryPlanSnapshot snapshot, List<PublishedLine> retained) {
    Map<UUID, MediaReferenceInput> values = new LinkedHashMap<>();
    snapshot.mediaReferences().forEach(reference -> values.put(reference.mediaId(), reference));
    retained.forEach(line -> line.source().mediaReferences().forEach(
        reference -> values.put(reference.mediaId(), reference)));
    return List.copyOf(values.values());
  }

  private List<PublishedStage> allocateStages(
      FrozenInventoryPlanSnapshot snapshot, List<PublishedLine> lines) {
    List<PublishedStage> allocations = snapshot.stages().stream()
        .sorted(Comparator.comparingInt(InventoryPlanStageSnapshot::order)
            .thenComparing(InventoryPlanStageSnapshot::id))
        .map(PublishedStage::new)
        .toList();
    Set<Integer> allocated = new HashSet<>();
    for (PublishedStage allocation : allocations) {
      PublishedLine primary = firstAvailable(
          lines,
          allocated,
          candidate -> candidate.work() && matchesCatalogNode(candidate, allocation.stage()));
      if (primary == null) {
        primary = firstAvailable(
            lines, allocated, candidate -> matchesCatalogNode(candidate, allocation.stage()));
      }
      if (primary == null) {
        primary = firstAvailable(
            lines,
            allocated,
            candidate -> candidate.work() && matchesRoute(candidate, allocation.stage()));
      }
      if (primary != null) {
        allocation.add(primary);
        allocated.add(primary.sourceIndex());
      }
    }
    for (PublishedLine line : lines) {
      if (!line.work() || allocated.contains(line.sourceIndex())) continue;
      PublishedStage target = routeStageFor(allocations, line);
      if (target == null) throw invalid("Inventory work line has no selected repair-work route");
      target.add(line);
      allocated.add(line.sourceIndex());
    }
    for (PublishedLine line : lines) {
      if (line.work() || allocated.contains(line.sourceIndex())) continue;
      PublishedStage target = directCatalogStageFor(allocations, line);
      if (target == null) target = routeStageFor(allocations, line);
      if (target == null) throw invalid("Inventory material line has no selected repair-work route");
      target.add(line);
      allocated.add(line.sourceIndex());
    }
    if (allocated.size() != lines.size()) {
      throw invalid("Inventory publication plan did not allocate every line exactly once");
    }
    return allocations;
  }

  private static PublishedLine firstAvailable(
      List<PublishedLine> lines, Set<Integer> allocated, Predicate<PublishedLine> predicate) {
    return lines.stream()
        .filter(line -> !allocated.contains(line.sourceIndex()))
        .filter(predicate)
        .findFirst()
        .orElse(null);
  }

  private static boolean matchesCatalogNode(PublishedLine line, InventoryPlanStageSnapshot stage) {
    return line.source().catalogNodeId() != null
        && line.source().catalogNodeId().equals(stage.catalogNodeId());
  }

  private static boolean matchesRoute(PublishedLine line, InventoryPlanStageSnapshot stage) {
    return line.source().routing() != null
        && line.source().routing().queueId().equals(stage.routing().queueId());
  }

  private static PublishedStage directCatalogStageFor(
      List<PublishedStage> allocations, PublishedLine line) {
    return allocations.stream()
        .filter(allocation -> matchesCatalogNode(line, allocation.stage()))
        .filter(allocation -> matchesRoute(line, allocation.stage()))
        .findFirst()
        .orElse(null);
  }

  private static PublishedStage routeStageFor(List<PublishedStage> allocations, PublishedLine line) {
    List<PublishedStage> matching = allocations.stream()
        .filter(allocation -> matchesRoute(line, allocation.stage()))
        .toList();
    if (matching.isEmpty()) return null;
    return matching.stream()
        .filter(allocation -> allocation.lastSourceIndex() < line.sourceIndex())
        .max(Comparator.comparingInt(PublishedStage::lastSourceIndex))
        .orElse(matching.getFirst());
  }

  private static List<MediaReferenceInput> sourceMedia(FrozenInventoryPlanSnapshot snapshot) {
    Map<UUID, MediaReferenceInput> values = new LinkedHashMap<>();
    List<MediaReferenceInput> all = new ArrayList<>(snapshot.mediaReferences());
    snapshot.lines().forEach(line -> all.addAll(line.mediaReferences()));
    for (MediaReferenceInput reference : all) {
      MediaReferenceInput previous = values.putIfAbsent(reference.mediaId(), reference);
      if (previous != null && !previous.generation().equals(reference.generation())) {
        throw invalid("One inventory media identity cannot reference multiple generations");
      }
    }
    return List.copyOf(values.values());
  }

  private static void validateWorkLineMediaIsolation(List<InventoryPlanLineSnapshot> lines) {
    Set<UUID> assigned = new HashSet<>();
    for (InventoryPlanLineSnapshot line : lines) {
      if (line.type() != InventoryPlanLineType.WORK && !line.mediaReferences().isEmpty()) {
        throw invalid("Inventory photos can only be assigned to work lines");
      }
      if (line.type() != InventoryPlanLineType.WORK) continue;
      for (MediaReferenceInput reference : line.mediaReferences()) {
        if (!assigned.add(reference.mediaId())) {
          throw invalid("One inventory photo cannot be assigned to multiple work lines");
        }
      }
    }
  }

  private static void validateCover(List<MediaReferenceInput> aggregate, UUID coverMediaId) {
    if (aggregate.isEmpty()) {
      if (coverMediaId != null) {
        throw invalid("Inventory cover photo must be null when aggregate media is empty");
      }
      return;
    }
    if (coverMediaId == null
        || aggregate.stream().noneMatch(reference -> coverMediaId.equals(reference.mediaId()))) {
      throw invalid("Inventory cover photo must reference aggregate media");
    }
  }

  private RentalItemFactProjection requireAsset(UUID assetId) {
    return rentalItems.findById(assetId).orElseThrow(
        () -> new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE, "Current rental-item fact is unavailable"));
  }

  private RentalItemFactProjection requireAssetForUpdate(UUID assetId) {
    return rentalItems.findByIdForUpdate(assetId).orElseThrow(
        () -> new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE, "Current rental-item fact is unavailable"));
  }

  private static void assertCurrentAsset(
      UUID warehouseId,
      InventoryPublicationFindingInput finding,
      RentalItemFactProjection asset) {
    if (!warehouseId.equals(asset.getWarehouseId())
        || finding.assetVersion() != asset.getAggregateVersion()) {
      throw conflict("Current rental-item warehouse/version differs from completed inventory evidence");
    }
  }

  private static InventoryPublicationTargetKind targetKind(RentalItemFactProjection asset) {
    return "AFTER_RENT".equals(asset.getAssetStatus())
        ? InventoryPublicationTargetKind.ESTIMATE
        : InventoryPublicationTargetKind.REPAIR;
  }

  private static void requireUniqueFindings(List<InventoryPublicationFindingInput> findings) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (InventoryPublicationFindingInput finding : findings) {
      if (finding == null || finding.findingId() == null || !ids.add(finding.findingId())) {
        throw invalid("Inventory publication finding IDs must be unique");
      }
    }
  }

  private String requestSha256(
      UUID inventoryId,
      UUID findingId,
      InventoryPublicationApplyRequest request) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("inventoryId", inventoryId);
    value.put("findingId", findingId);
    value.put("warehouseId", request.warehouseId());
    value.put("finalPlanVersion", request.finalPlanVersion());
    value.put("finalPlanSha256", request.finalPlanSha256());
    value.put("findingRevision", request.findingRevision());
    value.put("assetId", request.assetId());
    value.put("assetVersion", request.assetVersion());
    value.put("planFingerprintSha256", request.planFingerprintSha256());
    value.put("priority", request.priority());
    value.put("movementToRepair", request.movementToRepair());
    value.put("movementScheduledDate", request.movementScheduledDate());
    value.put("repairScheduledDate", request.repairScheduledDate());
    value.put("snapshot", request.snapshot());
    value.put("media", request.media());
    value.put("snapshotSchemaVersion", request.snapshotSchemaVersion());
    value.put("strategy", request.strategy());
    value.put("selectedTargetKind", request.selectedTargetKind());
    value.put("selectedTargetId", request.selectedTargetId());
    return canonicalizer.sha256(value);
  }

  private InventoryPublicationApplyResult result(InventoryPublicationSource source) {
    InventoryPublicationSourceReference reference = new InventoryPublicationSourceReference(
        source.getId().getInventoryId(),
        source.getId().getFinalPlanVersion(),
        source.getId().getFindingId(),
        source.getFindingRevision(),
        source.getFinalPlanSha256(),
        source.getPlanFingerprintSha256(),
        InventoryPublicationStrategy.valueOf(source.getStrategy()),
        enumValue(source.getSelectedTargetKind()),
        source.getSelectedTargetId(),
        enumValue(source.getSupersededTargetKind()),
        source.getSupersededTargetId());
    InventoryPublicationSuccessorStatus successor = null;
    if ("SUCCESSOR".equals(source.getPublicationOutcome())) {
      InventoryPublicationSuccessor relation = successors.findById(source.getId()).orElseThrow(
          () -> new IllegalStateException(
              "Inventory publication successor source has no durable relation"));
      successor = new InventoryPublicationSuccessorStatus(
          relation.getPredecessorRepairId(),
          InventoryPublicationSuccessorState.valueOf(relation.getState()),
          relation.getTerminalFact() == null
              ? null : InventoryPublicationTerminalFact.valueOf(relation.getTerminalFact()),
          relation.getTerminalFactEventId(),
          relation.getTerminalFactOccurredAt(),
          relation.getReleasedAt());
    }
    return new InventoryPublicationApplyResult(
        reference,
        InventoryPublicationOutcome.valueOf(source.getPublicationOutcome()),
        enumValue(source.getTargetKind()),
        source.getTargetId(),
        source.getEstimateId(),
        source.getRepairId(),
        successor,
        readDelta(source));
  }

  private static String enumName(InventoryPublicationTargetKind value) {
    return value == null ? null : value.name();
  }

  private static InventoryPublicationTargetKind enumValue(String value) {
    return value == null ? null : InventoryPublicationTargetKind.valueOf(value);
  }

  private InventoryPublicationDelta readDelta(InventoryPublicationSource source) {
    try {
      return mapper.readValue(source.getDeltaSnapshot(), InventoryPublicationDelta.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory publication delta is invalid", exception);
    }
  }

  private String inventoryActorJson() {
    return write(Map.of("subjectId", INVENTORY_ACTOR.toString(), "principalType", "SERVICE"));
  }

  private UUID stableKey(String operation, InventoryPublicationSourceId source) {
    return UUID.nameUUIDFromBytes(
        (operation
                + ":"
                + source.getInventoryId()
                + ":"
                + source.getFinalPlanVersion()
                + ":"
                + source.getFindingId())
            .getBytes(StandardCharsets.UTF_8));
  }

  private static UUID stableId(InventoryPublicationSourceId source, String type, int index) {
    return UUID.nameUUIDFromBytes(
        (source.getInventoryId()
                + ":"
                + source.getFinalPlanVersion()
                + ":"
                + source.getFindingId()
                + ":"
                + type
                + ":"
                + index)
            .getBytes(StandardCharsets.UTF_8));
  }

  private <T> List<T> readList(String value, Class<T> type) {
    try {
      return mapper.readValue(
          value,
          mapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance plan content is invalid", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory publication value cannot be serialized", exception);
    }
  }

  private static BigDecimal moneyToMinor(String value) {
    try {
      return new BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalStateException("Stored maintenance money value is invalid", exception);
    }
  }

  private static String money(long value) {
    return BigDecimal.valueOf(value, 2).setScale(2).toPlainString();
  }

  private static void registerConcurrentSafe(Runnable registration) {
    try {
      registration.run();
    } catch (DataIntegrityViolationException ignored) {
      // Another transaction registered the immutable source key; lock and validate it above.
    }
  }

  private void discardUnpublishedOperationOnRollback(
      InventoryPublicationSourceId sourceId, String requestSha256) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status != STATUS_COMMITTED) {
          registrar.discardIfUnpublished(sourceId, requestSha256);
        }
      }
    });
  }

  private <T> T inNewTransaction(Supplier<T> action) {
    T result = requiresNew.execute(status -> action.get());
    if (result == null) {
      throw new IllegalStateException("Inventory publication transaction returned no result");
    }
    return result;
  }

  /**
   * Suspending an ambient transaction leaves its locks and connection open while a remote call
   * waits. This boundary owns its short transactions, so reject caller-owned transactions before
   * reading local state or contacting a dependency.
   */
  private static void requireNoCallerTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Inventory publication cannot " + operation + " inside a caller transaction");
    }
  }

  private static MaintenanceValidationException invalid(String detail) {
    return new MaintenanceValidationException("MAINTENANCE_VALIDATION_FAILED", detail);
  }

  private static MaintenanceConflictException conflict(String detail) {
    return new MaintenanceConflictException("MAINTENANCE_IDEMPOTENCY_CONFLICT", detail);
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

  private static MaintenanceDependencyException retryablePrestartFailure(
      RuntimeException exception) {
    if (exception instanceof MaintenanceDependencyException dependency) {
      return retryablePrestartDependency(dependency);
    }
    return new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Pre-start inventory replacement has a possible remote effect and must be reconciled "
            + "before retry",
        exception);
  }

  private enum PublicationRemotePreflightKind { INCOMING, ROUTING }

  /**
   * Rolls back a prepare-only local transaction so that the caller can obtain a remote answer
   * without holding inventory-source, asset or repair locks. The final attempt repeats the full
   * local validation before it writes events, reconciliations and the operation mark.
   */
  private static final class PublicationRemotePreflightRequired extends RuntimeException {
    private final PublicationRemotePreflightKind kind;
    private final UUID warehouseId;
    private final List<InventoryPlanStageSnapshot> stages;

    private PublicationRemotePreflightRequired(
        PublicationRemotePreflightKind kind,
        UUID warehouseId,
        List<InventoryPlanStageSnapshot> stages) {
      super(null, null, false, false);
      this.kind = kind;
      this.warehouseId = warehouseId;
      this.stages = stages;
    }

    static PublicationRemotePreflightRequired incoming(UUID warehouseId) {
      return new PublicationRemotePreflightRequired(
          PublicationRemotePreflightKind.INCOMING, warehouseId, List.of());
    }

    static PublicationRemotePreflightRequired routing(
        UUID warehouseId, List<InventoryPlanStageSnapshot> stages) {
      return new PublicationRemotePreflightRequired(
          PublicationRemotePreflightKind.ROUTING, warehouseId, List.copyOf(stages));
    }

    PublicationRemotePreflightKind kind() {
      return kind;
    }

    UUID warehouseId() {
      return warehouseId;
    }

    List<InventoryPlanStageSnapshot> stages() {
      return stages;
    }
  }

  public record PublicationResult(InventoryPublicationApplyResult response, boolean replayed) {}

  private record ValidatedPublication(
      FrozenInventoryPlanSnapshot snapshot,
      List<MediaReferenceInput> sourceMedia,
      String rawSnapshot,
      String rawMedia) {}

  private record TargetCreated(
      InventoryPublicationTargetKind kind, UUID id, UUID estimateId, UUID repairId) {
    static TargetCreated none() {
      return new TargetCreated(null, null, null, null);
    }
  }

  private record SupersededTarget(InventoryPublicationTargetKind kind, UUID id) {}

  private record TerminalProof(UUID eventId, java.time.OffsetDateTime occurredAt) {}

  private record RemoteAttempt(boolean firstAttempt, long attemptCount) {}

  private record StrategyResolution(
      SupersededTarget superseded,
      MaintenanceRepair predecessor,
      TerminalProof terminalProof) {
    static StrategyResolution none() {
      return new StrategyResolution(null, null, null);
    }

    static StrategyResolution superseded(SupersededTarget value) {
      return new StrategyResolution(value, null, null);
    }

    static StrategyResolution predecessor(MaintenanceRepair value, TerminalProof terminalProof) {
      return new StrategyResolution(null, value, terminalProof);
    }
  }

  private record PrestartPreparation(
      InventoryPublicationApplyResult replay,
      InventoryPublicationPrestartReplacement intent) {
    static PrestartPreparation replay(InventoryPublicationApplyResult value) {
      return new PrestartPreparation(value, null);
    }

    static PrestartPreparation intent(InventoryPublicationPrestartReplacement value) {
      return new PrestartPreparation(null, value);
    }

    static PrestartPreparation notApplicable() {
      return new PrestartPreparation(null, null);
    }

    boolean applicable() {
      return intent != null;
    }
  }

  private record PrestartCandidate(String mode, String driverKind) {}

  private record RepairPublicationPlan(
      List<PublishedLine> lines,
      List<PublishedStage> allocations,
      List<MediaReferenceInput> sourceMedia,
      UUID coverMediaId) {}

  private record DeltaRepairPlan(
      RepairPublicationPlan plan, InventoryPublicationDelta delta) {}

  private record SemanticLineKey(
      InventoryPlanLineType type, UUID catalogNodeId, String manualSignature) {
    static SemanticLineKey catalog(InventoryPlanLineType type, UUID catalogNodeId) {
      if (type == null || catalogNodeId == null) {
        throw new IllegalArgumentException("Catalog inventory successor identity is incomplete");
      }
      return new SemanticLineKey(type, catalogNodeId, null);
    }

    static SemanticLineKey manual(InventoryPlanLineType type, String manualSignature) {
      if (type == null || manualSignature == null || manualSignature.isBlank()) {
        throw new IllegalArgumentException("Manual inventory successor identity is incomplete");
      }
      return new SemanticLineKey(type, null, manualSignature);
    }
  }

  private record ManualComparableKey(
      InventoryPlanLineType type, String description, String unit) {}

  private record ExistingAssignments(
      Map<SemanticLineKey, BigDecimal> quantities,
      Set<ManualComparableKey> manualComparable) {}

  private record LineAmount(boolean work, BigDecimal totalMinor) {}

  private record PublishedLine(
      int sourceIndex,
      InventoryPlanLineSnapshot source,
      EstimateLineResponse response,
      BigDecimal quantity,
      long unitPriceMinor,
      CatalogNodeSnapshot catalogSnapshot,
      UUID catalogNodeId,
      String queueRef) {
    boolean work() {
      return response.lineType() == EstimateLineType.WORK;
    }
  }

  private static final class PublishedStage {
    private final InventoryPlanStageSnapshot stage;
    private final List<PublishedLine> lines = new ArrayList<>();

    private PublishedStage(InventoryPlanStageSnapshot stage) {
      this.stage = stage;
    }

    private InventoryPlanStageSnapshot stage() {
      return stage;
    }

    private List<PublishedLine> lines() {
      return List.copyOf(lines);
    }

    private List<EstimateLineResponse> workLines() {
      return lines.stream().filter(PublishedLine::work).map(PublishedLine::response).toList();
    }

    private List<EstimateLineResponse> materialLines() {
      return lines.stream().filter(line -> !line.work()).map(PublishedLine::response).toList();
    }

    private UUID primaryLineId() {
      return workLines().stream().map(EstimateLineResponse::id).findFirst().orElse(null);
    }

    private void add(PublishedLine line) {
      lines.add(line);
    }

    private int lastSourceIndex() {
      return lines.stream().mapToInt(PublishedLine::sourceIndex).max().orElse(Integer.MIN_VALUE);
    }
  }
}
