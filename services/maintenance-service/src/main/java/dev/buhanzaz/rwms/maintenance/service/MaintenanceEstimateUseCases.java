package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Owns maintenance estimate creation, revision, completion and amendment workflows, including service-local idempotency and durable downstream intent. */
@Service
public class MaintenanceEstimateUseCases {
  private final MaintenanceEstimateRepository estimates;
  private final MaintenanceRepairRepository repairs;
  private final MaintenanceEventStore events;
  private final MaintenanceIdempotencyStore idempotency;
  private final MaintenanceReconciliationStore reconciliations;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceCatalogModelSupport catalogModelSupport;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateModelSupport estimateModelSupport;
  private final MaintenanceEstimateSupport estimateSupport;
  private final MaintenanceEstimateRevisionSupport estimateRevisionSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceMediaSupport mediaSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;
  private final MaintenanceTaskBoardSupport taskBoardSupport;

  public MaintenanceEstimateUseCases(
      MaintenanceEstimateRepository estimates,
      MaintenanceRepairRepository repairs,
      MaintenanceEventStore events,
      MaintenanceIdempotencyStore idempotency,
      MaintenanceReconciliationStore reconciliations,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceCatalogModelSupport catalogModelSupport,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateModelSupport estimateModelSupport,
      MaintenanceEstimateSupport estimateSupport,
      MaintenanceEstimateRevisionSupport estimateRevisionSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceMediaSupport mediaSupport,
      MaintenanceRepairModelSupport repairModelSupport,
      MaintenanceTaskBoardSupport taskBoardSupport) {
    this.estimates = estimates;
    this.repairs = repairs;
    this.events = events;
    this.idempotency = idempotency;
    this.reconciliations = reconciliations;
    this.warehouseLifecycle = warehouseLifecycle;
    this.catalogModelSupport = catalogModelSupport;
    this.commandSupport = commandSupport;
    this.estimateModelSupport = estimateModelSupport;
    this.estimateSupport = estimateSupport;
    this.estimateRevisionSupport = estimateRevisionSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.mediaSupport = mediaSupport;
    this.repairModelSupport = repairModelSupport;
    this.taskBoardSupport = taskBoardSupport;
  }

  public List<EstimateResponse> estimates(UUID warehouseId) {
    return estimates.findAllByWarehouseIdOrderByCreatedAtDesc(warehouseId).stream().map(estimateModelSupport::estimateResponse).toList();
  }

  public EstimateResponse estimate(UUID id) { return estimateModelSupport.estimateResponse(estimateModelSupport.requireEstimate(id)); }

  public EstimateResponse estimate(UUID id, UUID warehouseId) {
    return estimateModelSupport.estimateResponse(estimates.findByIdAndWarehouseId(id, warehouseId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Estimate not found")));
  }

  public CreateResult<EstimateResponse> createEstimate(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    commandSupport.requireNoCallerTransaction("create an estimate");
    WarehouseAdmissionPreflight<CreateResult<EstimateResponse>> preflight =
        commandSupport.inLocalTransaction(
            "estimate create preflight", () -> createEstimatePreflight(subjectId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    estimateSupport.requireCustomRoutingReady(request.warehouseId(), request.plan());
    return commandSupport.inLocalTransaction(
        "estimate create finalization", () -> createEstimateInTransaction(subjectId, key, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<EstimateResponse>> createEstimatePreflight(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "estimate.create", key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(commandSupport.read(replay.get(), EstimateResponse.class), true));
    }
    RentalItemFactProjection rentalItem =
        repairModelSupport.requireRentalItemFact(request.rentalItemId(), request.warehouseId());
    repairModelSupport.requireEstimateSourceStatus(rentalItem);
    catalogModelSupport.requireActiveCatalog(request.warehouseId());
    estimateSupport.validateEstimatePlan(request.lines(), request.plan());
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    return new WarehouseAdmissionPreflight<>(request.warehouseId(), null);
  }

  private CreateResult<EstimateResponse> createEstimateInTransaction(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.create", key, requestHash);
    if (replay.isPresent()) return new CreateResult<>(commandSupport.read(replay.get(), EstimateResponse.class), true);
    RentalItemFactProjection rentalItem = repairModelSupport.requireRentalItemFact(
        request.rentalItemId(), request.warehouseId());
    repairModelSupport.requireEstimateSourceStatus(rentalItem);
    CatalogVersion catalog = catalogModelSupport.requireActiveCatalog(request.warehouseId());
    estimateSupport.validateEstimatePlan(request.lines(), request.plan());
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceEstimate draft = MaintenanceEstimate.create(
        request.warehouseId(), request.rentalItemId(), rentalItem.getAggregateVersion(),
        catalog.getId(), request.dispatchDate(), request.sourceParty(), null, commandSupport.actorJson());
    draft.replaceCoverMediaId(request.coverMediaId());
    MaintenanceEstimate estimate = estimates.saveAndFlush(draft);
    estimateRevisionSupport.replaceEstimateRevision(estimate, request.lines(), request.plan(), null);
    mediaSupport.replaceMedia("ESTIMATE", "MAINTENANCE_ESTIMATE", estimate.getId(), estimate.getWarehouseId(),
        request.mediaReferences());
    events.initialize(
        MaintenanceAggregateType.ESTIMATE,
        estimate.getId(),
        estimate.getVersion(),
        MaintenanceEventType.ESTIMATE_CREATED,
        eventPayloadSupport.estimateLocal(estimate),
        eventPayloadSupport.estimateFact(MaintenanceEventType.ESTIMATE_CREATED, estimate),
        eventPayloadSupport.estimateSnapshot(estimate));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        estimate.getId(),
        estimate.getWarehouseId(),
        estimate.getId(),
        estimate.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        estimate.getWarehouseId(), estimate.getId(), estimate.getCreatedAt());
    EstimateResponse response = estimateModelSupport.estimateResponse(estimate);
    idempotency.store(subjectId, "estimate.create", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  /**
   * Creates the maintenance-owned estimate paired with one immutable logistics return source.
   *
   * <p>The caller owns the concurrent source-key arbitration and transaction. Logistics has
   * already validated these opaque references against the exact return-line media owner before
   * invoking the maintenance boundary, so they are persisted without pretending that their source
   * media owner was already the newly generated estimate ID.
   */
  public UUID createLogisticsReturnEstimate(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReferenceInput> sourceMediaReferences) {
    if (warehouseId == null
        || rentalItemId == null
        || rentalItemVersion < 0
        || dispatchDate == null
        || sourceMediaReferences == null
        || sourceMediaReferences.isEmpty()) {
      throw new IllegalArgumentException("Logistics return estimate source is incomplete");
    }
    CatalogVersion catalog = catalogModelSupport.requireActiveCatalog(warehouseId);
    MaintenanceEstimate estimate =
        estimates.saveAndFlush(
            MaintenanceEstimate.create(
                warehouseId,
                rentalItemId,
                rentalItemVersion,
                catalog.getId(),
                dispatchDate,
                "Возврат из аренды",
                null,
                commandSupport.actorJson()));
    estimateRevisionSupport.replaceEstimateRevision(estimate, List.of(), List.of(), null);
    mediaSupport.replaceLogisticsReturnMedia(estimate, sourceMediaReferences);
    events.initialize(
        MaintenanceAggregateType.ESTIMATE,
        estimate.getId(),
        estimate.getVersion(),
        MaintenanceEventType.ESTIMATE_CREATED,
        eventPayloadSupport.estimateLocal(estimate),
        eventPayloadSupport.estimateFact(MaintenanceEventType.ESTIMATE_CREATED, estimate),
        eventPayloadSupport.estimateSnapshot(estimate));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        estimate.getId(),
        estimate.getWarehouseId(),
        estimate.getId(),
        estimate.getVersion(),
        true);
    warehouseLifecycle.recordOperation(
        estimate.getWarehouseId(), estimate.getId(), estimate.getCreatedAt());
    return estimate.getId();
  }

  public EstimateResponse updateEstimate(UUID id, UpdateEstimateRequest request) {
    commandSupport.requireNoCallerTransaction("update an estimate");
    WarehouseAdmissionPreflight<Void> preflight =
        commandSupport.inLocalTransaction(
            "estimate update preflight", () -> updateEstimatePreflight(id, request));
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    estimateSupport.requireCustomRoutingReady(preflight.warehouseId(), request.plan());
    return commandSupport.inLocalTransaction(
        "estimate update finalization", () -> updateEstimateInTransaction(id, request));
  }

  private WarehouseAdmissionPreflight<Void> updateEstimatePreflight(
      UUID id, UpdateEstimateRequest request) {
    MaintenanceEstimate estimate = estimateModelSupport.requireEstimate(id);
    commandSupport.assertVersion(estimate.getVersion(), request.expectedVersion());
    estimateSupport.validateEstimatePlan(request.lines(), request.plan());
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    return new WarehouseAdmissionPreflight<>(estimate.getWarehouseId(), null);
  }

  private EstimateResponse updateEstimateInTransaction(
      UUID id, UpdateEstimateRequest request) {
    MaintenanceEstimate estimate = estimateModelSupport.requireEstimate(id);
    commandSupport.assertVersion(estimate.getVersion(), request.expectedVersion());
    estimateSupport.validateEstimatePlan(request.lines(), request.plan());
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    estimate.replaceMetadata(request.dispatchDate(), request.sourceParty(), estimate.getComment());
    estimate.replaceCoverMediaId(request.coverMediaId());
    estimate.touchDraft();
    estimateRevisionSupport.replaceEstimateRevision(estimate, request.lines(), request.plan(), null);
    mediaSupport.replaceMedia("ESTIMATE", "MAINTENANCE_ESTIMATE", id, estimate.getWarehouseId(),
        request.mediaReferences());
    MaintenanceEstimate saved = estimates.saveAndFlush(estimate);
    events.append(
        MaintenanceAggregateType.ESTIMATE,
        id,
        request.expectedVersion(),
        MaintenanceEventType.ESTIMATE_DRAFT_CHANGED,
        eventPayloadSupport.estimateLocal(saved),
        eventPayloadSupport.estimateFact(MaintenanceEventType.ESTIMATE_DRAFT_CHANGED, saved),
        eventPayloadSupport.estimateSnapshot(saved));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
    return estimateModelSupport.estimateResponse(saved);
  }

  public CreateResult<EstimateCommandResult> completeEstimate(
      UUID subjectId, UUID key, UUID id, CompleteEstimateRequest request) {
    commandSupport.requireNoCallerTransaction("complete an estimate");
    EstimateCompletionPreflight preflight =
        commandSupport.inLocalTransaction(
            "estimate completion preflight",
            () -> completeEstimatePreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireOutgoing(preflight.warehouseId());
    estimateSupport.requireCustomRoutingReady(preflight.warehouseId(), preflight.plan());
    FurnitureAccountingMode furnitureAccountingMode =
        resolveEstimateFurnitureAccounting(preflight, request);
    return commandSupport.inLocalTransaction(
        "estimate completion finalization",
        () ->
            completeEstimateInTransaction(
                subjectId, key, id, request, furnitureAccountingMode));
  }

  private EstimateCompletionPreflight completeEstimatePreflight(
      UUID subjectId, UUID key, UUID id, CompleteEstimateRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.complete:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new EstimateCompletionPreflight(
          new CreateResult<>(commandSupport.read(replay.get(), EstimateCommandResult.class), true),
          null,
          List.of(),
          null,
          List.of());
    }
    MaintenanceEstimate estimate = estimateModelSupport.requireEstimate(id);
    commandSupport.assertVersion(estimate.getVersion(), request.expectedVersion());
    return new EstimateCompletionPreflight(
        null,
        estimate.getWarehouseId(),
        estimateSupport.storedPlanInputs(estimateModelSupport.currentPlan(estimate)),
        estimate.getRentalItemId(),
        estimateSupport.estimateFurnitureQuantities(estimate));
  }

  private FurnitureAccountingMode resolveEstimateFurnitureAccounting(
      EstimateCompletionPreflight preflight, CompleteEstimateRequest request) {
    return estimateSupport.resolveFurnitureAccounting(preflight, request);
  }

  private CreateResult<EstimateCommandResult> completeEstimateInTransaction(
      UUID subjectId,
      UUID key,
      UUID id,
      CompleteEstimateRequest request,
      FurnitureAccountingMode furnitureAccountingMode) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.complete:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), EstimateCommandResult.class), true);
    }
    MaintenanceEstimate estimate = estimateModelSupport.requireEstimate(id);
    commandSupport.assertVersion(estimate.getVersion(), request.expectedVersion());
    List<EstimateLine> lines = estimateModelSupport.currentLines(estimate);
    MaintenanceRepair commandRepair = null;
    if (lines.isEmpty()) {
      estimate.complete(null);
      reconciliations.enqueue(
          null,
          "ASSET",
          "COMPLETE_EMPTY_ESTIMATE",
          commandSupport.derived(key, "empty-asset"),
          Map.of("estimateId", estimate.getId().toString()));
    } else {
      commandRepair =
          estimateRevisionSupport.createEstimateRepair(
              estimate,
              estimateModelSupport.currentPlan(estimate),
              request.priority(),
              request.movementToRepair(),
              request.logisticsPlanningMode(),
              request.logisticsScheduledDate(),
              furnitureAccountingMode);
      estimate.complete(commandRepair.getId());
      taskBoardSupport.enqueueRepairQueue(commandRepair, commandSupport.derived(key, "queue-repair"), false);
    }
    MaintenanceEstimate saved = estimates.saveAndFlush(estimate);
    events.append(
        MaintenanceAggregateType.ESTIMATE,
        id,
        request.expectedVersion(),
        MaintenanceEventType.ESTIMATE_COMPLETED,
        eventPayloadSupport.estimateLocal(saved),
        eventPayloadSupport.estimateFact(MaintenanceEventType.ESTIMATE_COMPLETED, saved),
        eventPayloadSupport.estimateSnapshot(saved));
    EstimateCommandResult response = new EstimateCommandResult(
        estimateModelSupport.estimateResponse(saved), commandRepair == null ? null : repairModelSupport.repairResponse(commandRepair),
        commandRepair == null
            ? new DeliverySnapshot(DeliveryState.RETRY_PENDING, 0, saved.getUpdatedAt())
            : repairModelSupport.delivery(commandRepair));
    idempotency.store(subjectId, "estimate.complete:" + id, key, requestHash, 200, response);
    return new CreateResult<>(response, false);
  }

  public CreateResult<EstimateCommandResult> amendEstimate(
      UUID subjectId, UUID key, UUID id, AmendEstimateRequest request) {
    commandSupport.requireNoCallerTransaction("amend an estimate");
    WarehouseAdmissionPreflight<CreateResult<EstimateCommandResult>> preflight =
        commandSupport.inLocalTransaction(
            "estimate amendment preflight",
            () -> amendEstimatePreflight(subjectId, key, id, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    estimateSupport.requireCustomRoutingReady(preflight.warehouseId(), request.plan());
    return commandSupport.inLocalTransaction(
        "estimate amendment finalization",
        () -> amendEstimateInTransaction(subjectId, key, id, request));
  }

  private WarehouseAdmissionPreflight<CreateResult<EstimateCommandResult>> amendEstimatePreflight(
      UUID subjectId, UUID key, UUID id, AmendEstimateRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.amend:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new WarehouseAdmissionPreflight<>(
          null, new CreateResult<>(commandSupport.read(replay.get(), EstimateCommandResult.class), true));
    }
    MaintenanceEstimate estimate = estimateModelSupport.requireEstimate(id);
    commandSupport.assertVersion(estimate.getVersion(), request.expectedVersion());
    estimateSupport.validateEstimatePlan(request.lines(), request.plan());
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    return new WarehouseAdmissionPreflight<>(estimate.getWarehouseId(), null);
  }

  private CreateResult<EstimateCommandResult> amendEstimateInTransaction(
      UUID subjectId, UUID key, UUID id, AmendEstimateRequest request) {
    String requestHash = commandSupport.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "estimate.amend:" + id, key, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), EstimateCommandResult.class), true);
    }
    MaintenanceEstimate estimate = estimateModelSupport.requireEstimate(id);
    commandSupport.assertVersion(estimate.getVersion(), request.expectedVersion());
    estimateSupport.validateEstimatePlan(request.lines(), request.plan());
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceRepair repair = estimate.getRepairId() == null ? null : repairModelSupport.requireRepair(estimate.getRepairId());
    if (repair != null
        && !estimateSupport.estimateFurnitureQuantities(estimate)
            .equals(estimateSupport.estimateFurnitureQuantities(estimate, request.lines()))) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Furniture quantities cannot change after an estimate has created its repair");
    }
    List<MaintenanceEventStore.StreamRef> streams = new ArrayList<>();
    streams.add(new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.ESTIMATE, id));
    MaintenanceRepair linkedRepair = repair;
    if (repair != null) {
      streams.add(new MaintenanceEventStore.StreamRef(MaintenanceAggregateType.REPAIR, repair.getId()));
    }
    Map<MaintenanceEventStore.StreamRef, Long> locked = events.lockStreams(streams);
    commandSupport.assertVersion(locked.get(new MaintenanceEventStore.StreamRef(
        MaintenanceAggregateType.ESTIMATE, id)), request.expectedVersion());
    if (repair != null) {
      if (request.expectedLinkedRepairVersion() == null) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED", "repairExpectedVersion is required for a linked repair amendment");
      }
      commandSupport.assertVersion(locked.get(new MaintenanceEventStore.StreamRef(
          MaintenanceAggregateType.REPAIR, repair.getId())), request.expectedLinkedRepairVersion());
      commandSupport.assertVersion(repair.getVersion(), request.expectedLinkedRepairVersion());
      repair.requirePreStartAmendment();
      if (request.lines().isEmpty()) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT", "An estimate linked to a repair cannot be amended to zero lines");
      }
      List<EstimateLineResponse> canonicalLines =
          estimateSupport.canonicalEstimateLines(estimate, request.lines());
      RepairComplexitySnapshot amendedComplexity =
          repairModelSupport.repairComplexityForLines(repair.getWarehouseId(), canonicalLines);
      boolean reclassifyingCapital =
          repair.getExecutionState() == RepairExecutionState.QUEUED
              && amendedComplexity.type() == RepairComplexity.CAPITAL;
      repair.amendPreStartPlan();
      if (reclassifyingCapital) {
        repair.markReclassifyingCapital();
      }
      estimateRevisionSupport.replaceRepairStages(
          repair, request.plan(), canonicalLines);
      MaintenanceRepair repairSaved = repairs.saveAndFlush(repair);
      linkedRepair = repairSaved;
      if (repairSaved.getExecutionState() == RepairExecutionState.QUEUED) {
        taskBoardSupport.enqueueRepairComplexityStatusSync(
            repairSaved, commandSupport.derived(key, "repair-complexity-status"));
        if (repairSaved.getTaskBoardVersion() != null && !reclassifyingCapital) {
          reconciliations.enqueue(
              repairSaved.getId(),
              "TASK_BOARD",
              "UPDATE_TASK",
              commandSupport.derived(key, "task-update"),
              Map.of("repairId", repairSaved.getId().toString()));
        }
      }
      events.append(
          MaintenanceAggregateType.REPAIR,
          repair.getId(),
          request.expectedLinkedRepairVersion(),
          MaintenanceEventType.REPAIR_PLAN_CHANGED,
          eventPayloadSupport.repairLocal(repairSaved),
          eventPayloadSupport.repairFact(MaintenanceEventType.REPAIR_PLAN_CHANGED, repairSaved),
          eventPayloadSupport.repairSnapshot(repairSaved));
    } else {
      if (request.expectedLinkedRepairVersion() != null) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_VERSION_CONFLICT",
            "An estimate without a linked repair cannot accept a repair version");
      }
      if (request.lines().isEmpty()) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED", "An empty completed estimate needs no empty amendment revision");
      }
    }
    UUID linkedRepairId = linkedRepair == null ? null : linkedRepair.getId();
    estimate.replaceCompletedMetadata(
        request.dispatchDate(), request.sourceParty(), estimate.getComment(), linkedRepairId);
    estimate.replaceCoverMediaId(request.coverMediaId());
    estimateRevisionSupport.replaceEstimateRevision(estimate, request.lines(), request.plan(), request.reason());
    if (linkedRepair == null) {
      linkedRepair = estimateRevisionSupport.createEstimateRepairFromPlan(estimate, request.plan());
      estimate.linkCompletedRepair(linkedRepair.getId());
      taskBoardSupport.enqueueRepairQueue(linkedRepair, commandSupport.derived(key, "queue-first-repair"), false);
    }
    mediaSupport.replaceMedia("ESTIMATE", "MAINTENANCE_ESTIMATE", id, estimate.getWarehouseId(),
        request.mediaReferences());
    MaintenanceEstimate saved = estimates.saveAndFlush(estimate);
    events.append(
        MaintenanceAggregateType.ESTIMATE,
        id,
        request.expectedVersion(),
        MaintenanceEventType.ESTIMATE_AMENDED,
        eventPayloadSupport.estimateLocal(saved),
        eventPayloadSupport.estimateFact(MaintenanceEventType.ESTIMATE_AMENDED, saved),
        eventPayloadSupport.estimateSnapshot(saved));
    mediaSupport.enqueueMediaOwnerProof(
        "MAINTENANCE_ESTIMATE",
        saved.getId(),
        saved.getWarehouseId(),
        saved.getId(),
        saved.getVersion(),
        true);
    MaintenanceRepair linked = saved.getRepairId() == null ? null : repairModelSupport.requireRepair(saved.getRepairId());
    EstimateCommandResult response = new EstimateCommandResult(
        estimateModelSupport.estimateResponse(saved), linked == null ? null : repairModelSupport.repairResponse(linked),
        linked == null ? new DeliverySnapshot(DeliveryState.DELIVERED, 0, saved.getUpdatedAt()) : repairModelSupport.delivery(linked));
    idempotency.store(subjectId, "estimate.amend:" + id, key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

}
