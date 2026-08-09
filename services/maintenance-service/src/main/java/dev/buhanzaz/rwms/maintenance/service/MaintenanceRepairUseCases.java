package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Owns repair planning, queueing, rework and acceptance commands while preserving aggregate fencing, idempotency and durable downstream reconciliation intent. */
@Service
public class MaintenanceRepairUseCases {
  private static final Set<String> EMPTY_DIRECT_REPAIR_SOURCE_STATUSES =
      Set.of("FREE", "WAREHOUSE", "OWN_NEEDS");
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceIdempotencyStore idempotency;
  private final MaintenanceReconciliationStore reconciliations;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateSupport estimateSupport;
  private final MaintenanceEstimateRevisionSupport estimateRevisionSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceMediaSupport mediaSupport;
  private final MaintenanceReconciliationSupport reconciliationSupport;
  private final MaintenanceRepairLifecycleSupport repairLifecycleSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;
  private final MaintenanceTaskBoardSupport taskBoardSupport;

  public MaintenanceRepairUseCases(
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceIdempotencyStore idempotency,
      MaintenanceReconciliationStore reconciliations,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateSupport estimateSupport,
      MaintenanceEstimateRevisionSupport estimateRevisionSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceMediaSupport mediaSupport,
      MaintenanceReconciliationSupport reconciliationSupport,
      MaintenanceRepairLifecycleSupport repairLifecycleSupport,
      MaintenanceRepairModelSupport repairModelSupport,
      MaintenanceTaskBoardSupport taskBoardSupport) {
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.idempotency = idempotency;
    this.reconciliations = reconciliations;
    this.warehouseLifecycle = warehouseLifecycle;
    this.commandSupport = commandSupport;
    this.estimateSupport = estimateSupport;
    this.estimateRevisionSupport = estimateRevisionSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.mediaSupport = mediaSupport;
    this.reconciliationSupport = reconciliationSupport;
    this.repairLifecycleSupport = repairLifecycleSupport;
    this.repairModelSupport = repairModelSupport;
    this.taskBoardSupport = taskBoardSupport;
  }

  public List<RepairResponse> repairs(UUID warehouseId) {
    return repairs.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream().map(repairModelSupport::repairResponse).toList();
  }

  public List<RepairResponse> activeCapitalRepairs(UUID warehouseId) {
    return repairs.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream()
        .filter(value -> value.getExecutionState() != RepairExecutionState.DRAFT)
        .filter(value -> value.getExecutionState() != RepairExecutionState.CANCELLED)
        .filter(value -> value.getAcceptanceState() != RepairAcceptanceState.ACCEPTED)
        .filter(value -> value.getAcceptanceState() != RepairAcceptanceState.WRITTEN_OFF)
        .map(repairModelSupport::repairResponse)
        .filter(value -> value.complexity().type() == RepairComplexity.CAPITAL)
        .toList();
  }

  public RepairResponse activeCapitalRepair(UUID repairId) {
    MaintenanceRepair value =
        repairs
            .findById(repairId)
            .orElseThrow(
                () -> new MaintenanceNotFoundException("Active capital repair not found"));
    if (value.getExecutionState() == RepairExecutionState.DRAFT
        || value.getExecutionState() == RepairExecutionState.CANCELLED
        || value.getAcceptanceState() == RepairAcceptanceState.ACCEPTED
        || value.getAcceptanceState() == RepairAcceptanceState.WRITTEN_OFF) {
      throw new MaintenanceNotFoundException("Active capital repair not found");
    }
    RepairResponse response = repairModelSupport.repairResponse(value);
    if (response.complexity().type() != RepairComplexity.CAPITAL) {
      throw new MaintenanceNotFoundException("Active capital repair not found");
    }
    return response;
  }

  public RepairResponse repair(UUID id) { return repairModelSupport.repairResponse(repairModelSupport.requireRepair(id)); }

  public RepairResponse repair(UUID id, UUID warehouseId) {
    return repairModelSupport.repairResponse(repairs.findByIdAndWarehouseId(id, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found")));
  }

  public List<RepairWorkerEvidenceResponse> repairWorkerEvidence(UUID repairId) {
    return repairModelSupport.repairWorkerEvidence(repairId);
  }

  public RepairPlanResponse repairPlan(UUID id) {
    RepairResponse repair = repair(id);
    return repair.plan();
  }

  /**
   * Called by the logistics-only repair-place boundary after a driver has completed inbound
   * delivery and the place is occupied. The repair remains durable and queued while it waits,
   * but its ordinary task-board entry is intentionally absent until this point.
   *
   * <p>The stable reconciliation key makes a replayed logistics callback harmless and also
   * recovers a task registration if the first callback completed the place transition but failed
   * before it could enqueue the task-board work.</p>
   */
  public void activateQueuedRepairAfterDelivery(UUID warehouseId, UUID repairId) {
    commandSupport.requireNoCallerTransaction("activate a delivered queued repair");
    WarehouseAdmissionPreflight<Void> preflight =
        commandSupport.inLocalTransaction(
            "delivered queued repair preflight",
            () -> activateQueuedRepairAfterDeliveryPreflight(warehouseId, repairId));
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    commandSupport.inLocalTransaction(
        "delivered queued repair finalization",
        () -> {
          activateQueuedRepairAfterDeliveryInTransaction(warehouseId, repairId);
          return Boolean.TRUE;
        });
  }

  private WarehouseAdmissionPreflight<Void> activateQueuedRepairAfterDeliveryPreflight(
      UUID warehouseId, UUID repairId) {
    MaintenanceRepair repair =
        repairs
            .findByIdAndWarehouseId(repairId, warehouseId)
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    return new WarehouseAdmissionPreflight<>(repair.getWarehouseId(), null);
  }

  private void activateQueuedRepairAfterDeliveryInTransaction(UUID warehouseId, UUID repairId) {
    MaintenanceRepair repair =
        repairs
            .findByIdAndWarehouseId(repairId, warehouseId)
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    if (repairLifecycleSupport.blocksRepairExecution(repair.getId())) return;
    long expectedVersion =
        events.lockCurrentVersion(MaintenanceAggregateType.REPAIR, repair.getId());
    commandSupport.assertVersion(repair.getVersion(), expectedVersion);
    if (repair.getExecutionState() != RepairExecutionState.QUEUED
        || repair.getReclassificationState() != RepairReclassificationState.STABLE
        || repair.getTaskBoardVersion() != null
        || !taskBoardSupport.requiresDriverDeliveryToRepair(repair)
        || !repairLifecycleSupport.isRepairPlaceOccupied(warehouseId, repairId)) {
      return;
    }
    taskBoardSupport.enqueueTaskRegistration(
        repair, commandSupport.stableOperationKey("register-task", repair.getExternalTaskId(), 0));
  }

  public ReworkCandidatesResponse reworkCandidates(UUID repairId, UUID warehouseId) {
    MaintenanceRepair source = repairs.findByIdAndWarehouseId(repairId, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    return new ReworkCandidatesResponse(repairLifecycleSupport.reworkCandidateItems(source));
  }

  public CreateResult<RepairResponse> createDirectRepair(
      UUID subjectId, UUID key, CreateDirectRepairRequest request) {
    commandSupport.requireNoCallerTransaction("create a direct repair");
    WarehouseAdmissionPreflight<CreateResult<RepairResponse>> preflight =
        commandSupport.inLocalTransaction(
            "direct repair preflight", () -> createDirectRepairPreflight(subjectId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    estimateSupport.requireCustomRoutingReady(preflight.warehouseId(), request.plan());
    return commandSupport.inLocalTransaction(
        "direct repair finalization", () -> createDirectRepairInTransaction(subjectId, key, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<RepairResponse>> createDirectRepairPreflight(
      UUID subjectId, UUID key, CreateDirectRepairRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.direct", key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(commandSupport.read(replay.get(), RepairResponse.class), true));
    }
    RentalItemFactProjection rentalItem = repairModelSupport.requireRentalItemFact(
        request.rentalItemId(), request.warehouseId());
    repairModelSupport.requireDirectRepairSourceStatus(rentalItem);
    if (request.lines() != null
        && request.lines().isEmpty()
        && !EMPTY_DIRECT_REPAIR_SOURCE_STATUSES.contains(rentalItem.getAssetStatus())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "An empty direct repair requires an unoccupied rental item");
    }
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    estimateSupport.validatePlan(request.plan(), request.lines() == null || request.lines().isEmpty());
    return new WarehouseAdmissionPreflight<>(request.warehouseId(), null);
  }

  private CreateResult<RepairResponse> createDirectRepairInTransaction(
      UUID subjectId, UUID key, CreateDirectRepairRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.direct", key, requestHash);
    if (replay.isPresent()) return new CreateResult<>(commandSupport.read(replay.get(), RepairResponse.class), true);
    RentalItemFactProjection rentalItem = repairModelSupport.requireRentalItemFact(
        request.rentalItemId(), request.warehouseId());
    repairModelSupport.requireDirectRepairSourceStatus(rentalItem);
    if (request.lines() != null
        && request.lines().isEmpty()
        && !EMPTY_DIRECT_REPAIR_SOURCE_STATUSES.contains(rentalItem.getAssetStatus())) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "An empty direct repair requires an unoccupied rental item");
    }
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceRepair draft = MaintenanceRepair.primary(
        request.warehouseId(), request.rentalItemId(), rentalItem.getAggregateVersion(), null,
        RepairOrigin.DIRECT_REPAIR,
        request.dispatchDate(), request.sourceParty(), commandSupport.actorJson());
    draft.replaceCoverMediaId(request.coverMediaId());
    MaintenanceRepair repair = repairs.saveAndFlush(draft);
    List<EstimateLineResponse> canonicalLines =
        estimateSupport.canonicalRepairLines(request.warehouseId(), request.lines());
    mediaSupport.validateLineMediaReferences(
        "MAINTENANCE_REPAIR", repair.getId(), repair.getWarehouseId(), canonicalLines);
    estimateRevisionSupport.replaceRepairStages(
        repair,
        request.plan(),
        canonicalLines);
    mediaSupport.replaceMedia("REPAIR", "MAINTENANCE_REPAIR", repair.getId(), repair.getWarehouseId(),
        request.mediaReferences());
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        eventPayloadSupport.repairLocal(repair),
        eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_CREATED, repair),
        eventPayloadSupport.repairSnapshot(repair));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        repair.getId(),
        repair.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        repair.getWarehouseId(), repair.getId(), repair.getCreatedAt());
    RepairResponse response = repairModelSupport.repairResponse(repair);
    idempotency.store(subjectId, "repair.direct", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  public RepairResponse updateRepairPlan(UUID id, UpdateRepairPlanRequest request) {
    commandSupport.requireNoCallerTransaction("update a repair plan");
    WarehouseAdmissionPreflight<Void> preflight =
        commandSupport.inLocalTransaction(
            "repair plan update preflight", () -> updateRepairPlanPreflight(id, request));
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    estimateSupport.requireCustomRoutingReady(preflight.warehouseId(), request.stages());
    return commandSupport.inLocalTransaction(
        "repair plan update finalization", () -> updateRepairPlanInTransaction(id, request));
  }

  private WarehouseAdmissionPreflight<Void> updateRepairPlanPreflight(
      UUID id, UpdateRepairPlanRequest request) {
    MaintenanceRepair initial = repairModelSupport.requireRepair(id);
    commandSupport.assertVersion(initial.getVersion(), request.expectedVersion());
    initial.requirePreStartAmendment();
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    estimateSupport.validatePlan(
        request.stages(), request.lines() == null || request.lines().isEmpty());
    return new WarehouseAdmissionPreflight<>(initial.getWarehouseId(), null);
  }

  private RepairResponse updateRepairPlanInTransaction(
      UUID id, UpdateRepairPlanRequest request) {
    Map<MaintenanceEventStore.StreamRef, Long> locked =
        events.lockStreams(List.of(repairLifecycleSupport.stream(id)));
    commandSupport.assertVersion(locked.get(repairLifecycleSupport.stream(id)), request.expectedVersion());
    MaintenanceRepair repair =
        repairs.findAllByIdForUpdate(List.of(id)).stream()
            .findFirst()
            .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    repairLifecycleSupport.assertStreamParity(repair, locked);
    repair.requirePreStartAmendment();
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    boolean updateRegisteredTask =
        repair.getExecutionState() == RepairExecutionState.QUEUED
            && repair.getTaskBoardVersion() != null;
    boolean synchronizeQueuedRepairStatus =
        repair.getExecutionState() == RepairExecutionState.QUEUED;
    List<EstimateLineResponse> canonicalLines =
        estimateSupport.canonicalRepairLines(repair.getWarehouseId(), request.lines());
    mediaSupport.validateUpdatedRepairLineMediaReferences(repair, canonicalLines);
    RepairComplexitySnapshot updatedComplexity =
        repairModelSupport.repairComplexityForLines(repair.getWarehouseId(), canonicalLines);
    boolean reclassifyingCapital =
        repair.getExecutionState() == RepairExecutionState.QUEUED
            && updatedComplexity.type() == RepairComplexity.CAPITAL;
    if (repair.getExecutionState() == RepairExecutionState.QUEUED) {
      repair.amendPreStartPlan();
    } else {
      repair.touchPlan();
    }
    if (reclassifyingCapital) {
      repair.markReclassifyingCapital();
    }
    repair.replaceCoverMediaId(request.coverMediaId());
    estimateRevisionSupport.replaceRepairStages(
        repair,
        request.stages(),
        canonicalLines);
    mediaSupport.replaceMedia(
        "REPAIR",
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        request.mediaReferences());
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    if (synchronizeQueuedRepairStatus) {
      taskBoardSupport.enqueueRepairComplexityStatusSync(
          saved,
          commandSupport.stableOperationKey(
              "repair-complexity-status", saved.getId(), saved.getVersion()));
    }
    if (updateRegisteredTask && !reclassifyingCapital) {
      reconciliations.enqueue(
          saved.getId(),
          "TASK_BOARD",
          "UPDATE_TASK",
          commandSupport.stableOperationKey("update-task", saved.getId(), saved.getVersion()),
          Map.of("repairId", saved.getId().toString()));
    }
    events.append(
        MaintenanceAggregateType.REPAIR,
        id,
        request.expectedVersion(),
        MaintenanceEventType.REPAIR_PLAN_CHANGED,
        eventPayloadSupport.repairLocal(saved),
        eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
        eventPayloadSupport.repairSnapshot(saved));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
    return repairModelSupport.repairResponse(saved);
  }

  public CreateResult<RepairCommandResult> queueRepair(
      UUID subjectId, UUID key, UUID id, QueueRepairRequest request) {
    commandSupport.requireNoCallerTransaction("queue a repair");
    QueueRepairCommandPreflight preflight =
        commandSupport.inLocalTransaction(
            "repair queue preflight", () -> queueRepairPreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    LeaseRefresh leaseRefresh =
        preflight.leasePlan() == null
            ? null
            : repairLifecycleSupport.refreshLeaseForCommand(preflight.leasePlan(), key);
    return commandSupport.inLocalTransaction(
        "repair queue finalization",
        () -> queueRepairInTransaction(subjectId, key, id, request, preflight.leasePlan(), leaseRefresh));
  }

  private QueueRepairCommandPreflight queueRepairPreflight(
      UUID subjectId, UUID key, UUID id, QueueRepairRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.queue:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new QueueRepairCommandPreflight(
          new CreateResult<>(commandSupport.read(replay.get(), RepairCommandResult.class), true), null, null);
    }
    MaintenanceRepair repair = repairModelSupport.requireRepair(id);
    commandSupport.assertVersion(repair.getVersion(), request.expectedVersion());
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(id);
    if (stages.isEmpty()) {
      if (repair.getOrigin() != RepairOrigin.DIRECT_REPAIR
          || repair.getKind() != RepairKind.PRIMARY
          || repair.getExecutionState() != RepairExecutionState.DRAFT) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED",
            "Only an empty direct repair can be completed without planned stages");
      }
      return new QueueRepairCommandPreflight(null, repair.getWarehouseId(), null);
    }
    LeaseRefreshPlan leasePlan = null;
    if (repair.getKind() == RepairKind.REWORK) {
      MaintenanceRepair source =
          repairModelSupport.requireRepair(
              Objects.requireNonNull(
                  repair.getSourceRepairId(), "Rework repair source is required"));
      List<MaintenanceRepair> chain = repairLifecycleSupport.sourceChain(repair);
      MaintenanceRepair root = repairLifecycleSupport.leaseOwner(repair, chain);
      leasePlan = repairLifecycleSupport.prepareLeaseRefreshPlan(root, List.of(source));
    }
    return new QueueRepairCommandPreflight(null, repair.getWarehouseId(), leasePlan);
  }

  private CreateResult<RepairCommandResult> queueRepairInTransaction(
      UUID subjectId,
      UUID key,
      UUID id,
      QueueRepairRequest request,
      LeaseRefreshPlan expectedLeasePlan,
      LeaseRefresh remoteLeaseRefresh) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.queue:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), RepairCommandResult.class), true);
    }
    MaintenanceRepair repair = repairModelSupport.requireRepair(id);
    commandSupport.assertVersion(repair.getVersion(), request.expectedVersion());
    List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(id);
    if (stages.isEmpty()) {
      if (repair.getOrigin() != RepairOrigin.DIRECT_REPAIR
          || repair.getKind() != RepairKind.PRIMARY
          || repair.getExecutionState() != RepairExecutionState.DRAFT) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED",
            "Only an empty direct repair can be completed without planned stages");
      }
      Map<MaintenanceEventStore.StreamRef, Long> locked =
          events.lockStreams(List.of(repairLifecycleSupport.stream(id)));
      commandSupport.assertVersion(locked.get(repairLifecycleSupport.stream(id)), request.expectedVersion());
      repair =
          repairs.findAllByIdForUpdate(List.of(id)).stream()
              .findFirst()
              .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
      repairLifecycleSupport.assertStreamParity(repair, locked);
      repair.selectPriority(request.priority());
      repair.selectMovementToRepair(
          request.movementToRepair(),
          request.logisticsPlanningMode(), request.logisticsScheduledDate());
      repair.queueUnderExistingRepair();
      repair.applyExternalTaskCancellation();
      repair.markReconciliationRequired();
      MaintenanceRepair saved = repairs.saveAndFlush(repair);
      events.append(
          MaintenanceAggregateType.REPAIR,
          id,
          request.expectedVersion(),
          MaintenanceEventType.REPAIR_PLAN_CHANGED,
          eventPayloadSupport.repairLocal(saved),
          eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
          eventPayloadSupport.repairSnapshot(saved));
      taskBoardSupport.enqueueTerminalAsset(
          saved,
          "EMPTY_REPAIR_TO_FREE",
          commandSupport.stableOperationKey("complete-empty-repair", saved.getId(), 0));
      RepairCommandResult response =
          new RepairCommandResult(repairModelSupport.repairResponse(saved), List.of(), repairModelSupport.delivery(saved));
      idempotency.store(subjectId, "repair.queue:" + id, key, requestHash, 200, response);
      return new CreateResult<>(response, false);
    }
    MaintenanceRepair saved;
    if (repair.getKind() == RepairKind.REWORK) {
      LockedRework lockedRework = repairLifecycleSupport.lockAndReloadRework(repair, request.expectedVersion());
      repair = lockedRework.rework();
      MaintenanceRepair root = lockedRework.root();
      MaintenanceRepair source = lockedRework.source();
      if (expectedLeasePlan == null || remoteLeaseRefresh == null) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT",
            "Rework repair lease refresh was not prepared");
      }
      repairLifecycleSupport.requireMatchingLeaseRefreshPlan(expectedLeasePlan, root, List.of(source));
      LeaseRefresh leaseRefresh = remoteLeaseRefresh;
      if (leaseRefresh.ownerRenewed() && !root.getId().equals(source.getId())) {
        long rootExpectedVersion = lockedRework.streamVersions().get(repairLifecycleSupport.stream(root.getId()));
        repairLifecycleSupport.applyLeaseSnapshot(root, leaseRefresh.lease());
        MaintenanceRepair renewedRoot = repairs.saveAndFlush(root);
        MaintenanceEventType renewalEvent = reconciliationSupport.renewalEvent(renewedRoot);
        events.append(
            MaintenanceAggregateType.REPAIR,
            renewedRoot.getId(),
            rootExpectedVersion,
            renewalEvent,
            eventPayloadSupport.repairLocal(renewedRoot),
            eventPayloadSupport.repairFact(renewalEvent, renewedRoot),
            eventPayloadSupport.repairSnapshot(renewedRoot));
      }
      MaintenanceDependencyGateway.LeaseSnapshot lease = leaseRefresh.lease();
      long sourceExpectedVersion = lockedRework.streamVersions().get(repairLifecycleSupport.stream(source.getId()));
      repairLifecycleSupport.applyLeaseSnapshot(source, lease);
      source.enterRework();
      MaintenanceRepair sourceSaved = repairs.saveAndFlush(source);
      events.append(
          MaintenanceAggregateType.REPAIR,
          source.getId(),
          sourceExpectedVersion,
          MaintenanceEventType.REPAIR_REWORK_CREATED,
          eventPayloadSupport.repairLocal(sourceSaved),
          eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_REWORK_CREATED, sourceSaved),
          eventPayloadSupport.repairSnapshot(sourceSaved));
      stages = repairStages.findAllByRepairIdOrderByStageNo(id);
      RepairComplexitySnapshot complexity =
          repairModelSupport.repairComplexityFromStoredStages(repair.getWarehouseId(), repair.getId());
      taskBoardSupport.prepareStagesForQueue(stages, complexity.type() == RepairComplexity.CAPITAL);
      repairStages.saveAllAndFlush(stages);
      repair.selectPriority(request.priority());
      repair.selectMovementToRepair(
          request.movementToRepair(),
          request.logisticsPlanningMode(), request.logisticsScheduledDate());
      if (complexity.type() == RepairComplexity.CAPITAL) {
        repair.queueExternalCapital(
            lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
      } else {
        repair.queue(lease.leaseId(), lease.version(), lease.fencingToken(), lease.expiresAt());
      }
      saved = repairs.saveAndFlush(repair);
      if (complexity.type() != RepairComplexity.CAPITAL) {
        taskBoardSupport.enqueueOrdinaryRepairExecution(
            saved,
            commandSupport.derived(key, "task-register"),
            commandSupport.derived(key, "driver-logistics-task"));
      }
      taskBoardSupport.enqueueRepairComplexityStatusSync(
          saved, commandSupport.derived(key, "repair-complexity-status"));
      events.append(
          MaintenanceAggregateType.REPAIR,
          id,
          request.expectedVersion(),
          MaintenanceEventType.REPAIR_QUEUED,
          eventPayloadSupport.repairLocal(saved),
          eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_QUEUED, saved),
          eventPayloadSupport.repairSnapshot(saved));
    } else {
      Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(List.of(repairLifecycleSupport.stream(id)));
      commandSupport.assertVersion(locked.get(repairLifecycleSupport.stream(id)), request.expectedVersion());
      boolean priorityChanged = repair.getPriority() != request.priority();
      repair.selectPriority(request.priority());
      boolean inboundMovementChanged =
          repair.selectMovementToRepair(
              request.movementToRepair(),
              request.logisticsPlanningMode(),
              request.logisticsScheduledDate());
      saved =
          priorityChanged || inboundMovementChanged
              ? repairs.saveAndFlush(repair)
              : repair;
      if (priorityChanged || inboundMovementChanged) {
        events.append(
            MaintenanceAggregateType.REPAIR,
            id,
            request.expectedVersion(),
            MaintenanceEventType.REPAIR_PLAN_CHANGED,
            eventPayloadSupport.repairLocal(saved),
            eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
            eventPayloadSupport.repairSnapshot(saved));
      }
      UUID queueKey = commandSupport.stableOperationKey("queue-repair", saved.getId(), 0);
      reconciliations.resumeStableQuarantined(
          saved.getId(),
          "ASSET",
          "QUEUE_REPAIR",
          queueKey,
          subjectId,
          "Authenticated repair queue retry after canonical asset revalidation");
      taskBoardSupport.enqueueRepairQueue(saved, queueKey, false);
    }
    List<RepairResponse> affected = saved.getSourceRepairId() == null
        ? List.of() : List.of(repairModelSupport.repairResponse(repairModelSupport.requireRepair(saved.getSourceRepairId())));
    RepairCommandResult response = new RepairCommandResult(
        repairModelSupport.repairResponse(saved), affected, repairModelSupport.delivery(saved));
    idempotency.store(subjectId, "repair.queue:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  /**
   * Reviewed recovery for a single stable inbound logistics intent. Remote logistics truth is
   * deliberately read between two short local transactions: no HTTP call can hold the repair or
   * event-stream locks, while the second transaction fences the local state before mutation.
   */
  public CreateResult<RepairCommandResult> retryInboundDelivery(
      UUID subjectId,
      UUID key,
      UUID id,
      UUID warehouseId,
      RetryInboundDeliveryRequest request) {
    commandSupport.requireNoCallerTransaction("retry inbound delivery");
    repairLifecycleSupport.validateInboundDeliveryRetryRequest(request);
    String scope = "repair.inbound-delivery-retry:" + id;
    String requestHash = commandSupport.hash(request);

    Optional<CreateResult<RepairCommandResult>> replay = commandSupport.inLocalTransaction(
        "retry inbound delivery preflight",
        () -> {
          Optional<JsonNode> stored = idempotency.replay(subjectId, scope, key, requestHash);
          if (stored.isPresent()) {
            return Optional.of(
                new CreateResult<>(commandSupport.read(stored.get(), RepairCommandResult.class), true));
          }
          MaintenanceRepair initial =
              repairs
                  .findByIdAndWarehouseId(id, warehouseId)
                  .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
          commandSupport.assertVersion(initial.getVersion(), request.expectedVersion());
          return Optional.empty();
        });
    if (replay.isPresent()) return replay.orElseThrow();

    warehouseLifecycle.requireOutgoing(warehouseId);

    repairLifecycleSupport.requireAbsentInboundDriverTask(id);

    return commandSupport.inLocalTransaction(
        "retry inbound delivery finalization",
        () -> {
          Optional<JsonNode> stored = idempotency.replay(subjectId, scope, key, requestHash);
          if (stored.isPresent()) {
            return new CreateResult<>(commandSupport.read(stored.get(), RepairCommandResult.class), true);
          }
          Map<MaintenanceEventStore.StreamRef, Long> locked =
              events.lockStreams(List.of(repairLifecycleSupport.stream(id)));
          commandSupport.assertVersion(locked.get(repairLifecycleSupport.stream(id)), request.expectedVersion());
          MaintenanceRepair repair =
              repairs.findAllByIdForUpdate(List.of(id)).stream()
                  .findFirst()
                  .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
          if (!warehouseId.equals(repair.getWarehouseId())) {
            throw new MaintenanceNotFoundException("Repair not found");
          }
          commandSupport.assertVersion(repair.getVersion(), request.expectedVersion());
          repairLifecycleSupport.assertStreamParity(repair, locked);

          UUID driverTaskKey = commandSupport.stableOperationKey("driver-logistics-task", repair.getId(), 0);
          boolean resumed =
              reconciliations.resumeStableQuarantined(
                  repair.getId(),
                  "LOGISTICS",
                  "CREATE_DRIVER_TASK",
                  driverTaskKey,
                  subjectId,
                  request.reason());
          if (!resumed) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT",
                "The exact inbound delivery reconciliation is not quarantined");
          }
          try {
            repair.retryQuarantinedInboundDelivery(
                request.logisticsPlanningMode(), request.logisticsScheduledDate());
          } catch (IllegalStateException exception) {
            throw new MaintenanceConflictException(
                "MAINTENANCE_STATE_CONFLICT", exception.getMessage());
          }
          MaintenanceRepair saved = repairs.saveAndFlush(repair);
          events.append(
              MaintenanceAggregateType.REPAIR,
              saved.getId(),
              request.expectedVersion(),
              MaintenanceEventType.REPAIR_PLAN_CHANGED,
              eventPayloadSupport.repairLocal(saved),
              eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, saved),
              eventPayloadSupport.repairSnapshot(saved));
          RepairCommandResult response =
              new RepairCommandResult(
                  repairModelSupport.repairResponse(saved),
                  List.of(),
                  repairModelSupport.delivery(saved));
          idempotency.store(subjectId, scope, key, requestHash, 200, response);
          return new CreateResult<>(response, false);
        });
  }

  public CreateResult<RepairCommandResult> queueRepair(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    commandSupport.requireNoCallerTransaction("queue a repair");
    return queueRepair(subjectId, key, id, new QueueRepairRequest(request.expectedVersion(), 3));
  }

  public CreateResult<RepairResponse> createRework(
      UUID subjectId, UUID key, UUID sourceId, CreateReworkRequest request) {
    commandSupport.requireNoCallerTransaction("create a rework repair");
    WarehouseAdmissionPreflight<CreateResult<RepairResponse>> preflight =
        commandSupport.inLocalTransaction(
            "rework repair preflight",
            () -> createReworkPreflight(subjectId, key, sourceId, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    estimateSupport.requireCustomRoutingReady(preflight.warehouseId(), request.plan());
    return commandSupport.inLocalTransaction(
        "rework repair finalization",
        () -> createReworkInTransaction(subjectId, key, sourceId, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<RepairResponse>> createReworkPreflight(
      UUID subjectId, UUID key, UUID sourceId, CreateReworkRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.rework:" + sourceId, key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(commandSupport.read(replay.get(), RepairResponse.class), true));
    }
    MaintenanceRepair initialSource = repairModelSupport.requireRepair(sourceId);
    commandSupport.assertVersion(initialSource.getVersion(), request.expectedVersion());
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    estimateSupport.validatePlan(
        request.plan(), request.lines() == null || request.lines().isEmpty());
    return new WarehouseAdmissionPreflight<>(initialSource.getWarehouseId(), null);
  }

  private CreateResult<RepairResponse> createReworkInTransaction(
      UUID subjectId, UUID key, UUID sourceId, CreateReworkRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "repair.rework:" + sourceId, key, requestHash);
    if (replay.isPresent()) return new CreateResult<>(commandSupport.read(replay.get(), RepairResponse.class), true);
    MaintenanceRepair initialSource = repairModelSupport.requireRepair(sourceId);
    List<UUID> ids = new ArrayList<>();
    ids.add(sourceId);
    if (initialSource.getRootRepairId() != null) ids.add(initialSource.getRootRepairId());
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(
        ids.stream().map(MaintenanceRepairLifecycleSupport::stream).toList());
    commandSupport.assertVersion(locked.get(repairLifecycleSupport.stream(sourceId)), request.expectedVersion());
    Map<UUID, MaintenanceRepair> current = repairs.findAllByIdForUpdate(ids).stream()
        .collect(java.util.stream.Collectors.toMap(MaintenanceRepair::getId, value -> value));
    MaintenanceRepair source = Optional.ofNullable(current.get(sourceId))
        .orElseThrow(() -> new MaintenanceNotFoundException("Repair not found"));
    commandSupport.assertVersion(source.getVersion(), request.expectedVersion());
    repairLifecycleSupport.assertStreamParity(source, locked);
    if (source.getRootRepairId() != null) {
      MaintenanceRepair root = Optional.ofNullable(current.get(source.getRootRepairId()))
          .orElseThrow(() -> new MaintenanceConflictException(
              "MAINTENANCE_STATE_CONFLICT", "Rework root repair is missing"));
      repairLifecycleSupport.assertStreamParity(root, locked);
      repairLifecycleSupport.validateReworkOwnership(source, root);
    }
    if (repairLifecycleSupport.hasUnresolvedRework(sourceId)) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "An active sibling rework already exists");
    }
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceRepair childDraft =
        MaintenanceRepair.rework(source, request.reason(), commandSupport.actorJson());
    childDraft.replaceCoverMediaId(request.coverMediaId());
    MaintenanceRepair child = repairs.saveAndFlush(childDraft);
    List<EstimateLineResponse> canonicalLines =
        repairLifecycleSupport.canonicalReworkLines(source, request.lines());
    mediaSupport.validateLineMediaReferences(
        "MAINTENANCE_REPAIR",
        child.getId(),
        child.getWarehouseId(),
        canonicalLines.stream()
            .filter(line -> line.disposition() == ReworkLineDisposition.ADDED)
            .toList());
    estimateRevisionSupport.replaceRepairStages(
        child,
        request.plan(),
        canonicalLines);
    mediaSupport.replaceMedia("REPAIR", "MAINTENANCE_REPAIR", child.getId(), child.getWarehouseId(),
        request.mediaReferences());
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        child.getId(),
        child.getVersion(),
        MaintenanceEventType.REPAIR_REWORK_CREATED,
        eventPayloadSupport.repairLocal(child),
        eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_REWORK_CREATED, child),
        eventPayloadSupport.repairSnapshot(child));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        child.getId(),
        child.getWarehouseId(),
        child.getId(),
        child.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        child.getWarehouseId(), child.getId(), child.getCreatedAt());
    RepairResponse response = repairModelSupport.repairResponse(child);
    idempotency.store(subjectId, "repair.rework:" + sourceId, key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  public CreateResult<RepairCommandResult> accept(
      UUID subjectId, UUID key, UUID id, RepairDecisionRequest request) {
    commandSupport.requireNoCallerTransaction("accept a repair");
    AcceptanceCommandPreflight preflight =
        commandSupport.inLocalTransaction(
            "repair acceptance preflight", () -> acceptPreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    LeaseRefresh leaseRefresh = repairLifecycleSupport.refreshLeaseForCommand(preflight.leasePlan(), key);
    return commandSupport.inLocalTransaction(
        "repair acceptance finalization",
        () -> acceptInTransaction(subjectId, key, id, request, preflight.leasePlan(), leaseRefresh));
  }

  private AcceptanceCommandPreflight acceptPreflight(
      UUID subjectId, UUID key, UUID id, RepairDecisionRequest request) {
    if (request.mediaReferences() == null || request.mediaReferences().isEmpty()) {
      throw commandSupport.invalid("At least one acceptance photo is required");
    }
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "repair.accept:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new AcceptanceCommandPreflight(
          new CreateResult<>(commandSupport.read(replay.get(), RepairCommandResult.class), true), null, null);
    }
    MaintenanceRepair initial = repairModelSupport.requireRepair(id);
    commandSupport.assertVersion(initial.getVersion(), request.expectedVersion());
    List<MaintenanceRepair> sources = repairLifecycleSupport.sourceChain(initial);
    repairLifecycleSupport.requireNoActiveRework(initial);
    return new AcceptanceCommandPreflight(
        null,
        initial.getWarehouseId(),
        repairLifecycleSupport.prepareLeaseRefreshPlan(initial, sources));
  }

  private CreateResult<RepairCommandResult> acceptInTransaction(
      UUID subjectId,
      UUID key,
      UUID id,
      RepairDecisionRequest request,
      LeaseRefreshPlan expectedLeasePlan,
      LeaseRefresh leaseRefresh) {
    if (request.mediaReferences() == null || request.mediaReferences().isEmpty()) {
      throw commandSupport.invalid("At least one acceptance photo is required");
    }
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(
        subjectId, "repair.accept:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), RepairCommandResult.class), true);
    }
    MaintenanceRepair initial = repairModelSupport.requireRepair(id);
    commandSupport.assertVersion(initial.getVersion(), request.expectedVersion());
    LockedRepairChain lockedChain = repairLifecycleSupport.lockAndReloadRepairChain(initial, request.expectedVersion());
    MaintenanceRepair repair = lockedChain.repair();
    List<MaintenanceRepair> sourceChain = lockedChain.sources();
    repairLifecycleSupport.requireNoActiveRework(repair);
    repairLifecycleSupport.requireMatchingLeaseRefreshPlan(expectedLeasePlan, repair, sourceChain);
    mediaSupport.replaceMedia(
        "ACCEPTANCE",
        "MAINTENANCE_ACCEPTANCE",
        repair.getId(),
        repair.getWarehouseId(),
        request.mediaReferences());
    repairLifecycleSupport.applyLeaseSnapshot(repair, leaseRefresh.lease());
    repair.accept(request.comment(), commandSupport.actorJson());
    repair.markLeaseReconciliationRequired();
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    events.append(
        MaintenanceAggregateType.REPAIR,
        id,
        request.expectedVersion(),
        MaintenanceEventType.REPAIR_ACCEPTED,
        eventPayloadSupport.decisionLocal(id, request.comment()),
        eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_ACCEPTED, saved),
        eventPayloadSupport.repairSnapshot(saved));
    repairLifecycleSupport.releaseAfterAcceptance(saved);
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_ACCEPTANCE",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
    repairLifecycleSupport.cascadeTerminal(saved, sourceChain, true, leaseRefresh.lease());
    taskBoardSupport.enqueueAcceptedCharacteristics(saved, sourceChain);
    taskBoardSupport.enqueueTerminalAsset(saved, "ACCEPT_TO_FREE", commandSupport.stableOperationKey(
        "accept-asset", saved.getId(), saved.getVersion()));
    RepairCommandResult response = new RepairCommandResult(
        repairModelSupport.repairResponse(saved), sourceChain.stream().map(repairModelSupport::repairResponse).toList(), repairModelSupport.delivery(saved));
    idempotency.store(subjectId, "repair.accept:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  public List<AcceptanceProjection> acceptance(UUID warehouseId) {
    return repairs.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream()
        .filter(value -> value.getAcceptanceState() == RepairAcceptanceState.PENDING
            || value.getAcceptanceState() == RepairAcceptanceState.IN_REWORK)
        // A source repair is not actionable while a child rework is still being
        // planned, executed or awaiting its own acceptance. Returning both
        // chain nodes made clients offer a terminal action that must be rejected.
        .filter(value -> !repairLifecycleSupport.hasUnresolvedRework(value.getId()))
        .map(value -> new AcceptanceProjection(
            value.getId(), commandSupport.rootId(value), value.getWarehouseId(), value.getRentalItemId(),
            value.getExecutionState(), value.getAcceptanceState(), value.getVersion(), value.getUpdatedAt()))
        .toList();
  }

}
