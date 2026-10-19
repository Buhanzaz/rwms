package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Owns manual and trusted logistics-return estimate creation, including admission, deadline,
 * idempotency, event initialization and media-owner proof.
 */
@Service
final class MaintenanceEstimateCreationUseCases {
  private final MaintenanceEstimateRepository estimates;
  private final MaintenanceEventStore events;
  private final MaintenanceIdempotencyStore idempotency;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceCatalogModelSupport catalogModelSupport;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateModelSupport estimateModelSupport;
  private final MaintenanceEstimateSupport estimateSupport;
  private final MaintenanceEstimateRevisionSupport estimateRevisionSupport;
  private final MaintenanceEventPayloadSupport eventPayloadSupport;
  private final MaintenanceMediaSupport mediaSupport;
  private final MaintenanceRepairModelSupport repairModelSupport;
  private final EstimateCreationWindowPolicy creationWindow;

  MaintenanceEstimateCreationUseCases(
      MaintenanceEstimateRepository estimates,
      MaintenanceEventStore events,
      MaintenanceIdempotencyStore idempotency,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceCatalogModelSupport catalogModelSupport,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateModelSupport estimateModelSupport,
      MaintenanceEstimateSupport estimateSupport,
      MaintenanceEstimateRevisionSupport estimateRevisionSupport,
      MaintenanceEventPayloadSupport eventPayloadSupport,
      MaintenanceMediaSupport mediaSupport,
      MaintenanceRepairModelSupport repairModelSupport,
      EstimateCreationWindowPolicy creationWindow) {
    this.estimates = estimates;
    this.events = events;
    this.idempotency = idempotency;
    this.warehouseLifecycle = warehouseLifecycle;
    this.catalogModelSupport = catalogModelSupport;
    this.commandSupport = commandSupport;
    this.estimateModelSupport = estimateModelSupport;
    this.estimateSupport = estimateSupport;
    this.estimateRevisionSupport = estimateRevisionSupport;
    this.eventPayloadSupport = eventPayloadSupport;
    this.mediaSupport = mediaSupport;
    this.repairModelSupport = repairModelSupport;
    this.creationWindow = creationWindow;
  }

  /** Creates or replays one manual estimate after remote deadline and warehouse admission checks. */
  CreateResult<EstimateResponse> createEstimate(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    commandSupport.requireNoCallerTransaction("create an estimate");
    WarehouseAdmissionPreflight<CreateResult<EstimateResponse>> preflight =
        commandSupport.inLocalTransaction(
            "estimate create preflight", () -> createEstimatePreflight(subjectId, key, request));
    if (preflight.replay() != null) {
      return preflight.replay();
    }
    warehouseLifecycle.requireIncoming(preflight.warehouseId());
    creationWindow.requireManualCreationOpen(request.warehouseId(), request.rentalItemId());
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
    if (replay.isPresent()) {
      return new CreateResult<>(commandSupport.read(replay.get(), EstimateResponse.class), true);
    }
    RentalItemFactProjection rentalItem =
        repairModelSupport.requireRentalItemFact(
            request.rentalItemId(), request.warehouseId());
    repairModelSupport.requireEstimateSourceStatus(rentalItem);
    CatalogVersion catalog = catalogModelSupport.requireActiveCatalog(request.warehouseId());
    estimateSupport.validateEstimatePlan(request.lines(), request.plan());
    mediaSupport.validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    MaintenanceEstimate draft =
        MaintenanceEstimate.create(
            request.warehouseId(),
            request.rentalItemId(),
            rentalItem.getAggregateVersion(),
            catalog.getId(),
            request.dispatchDate(),
            request.sourceParty(),
            null,
            commandSupport.actorJson());
    draft.selectForceCapitalRepair(request.forceCapitalRepair());
    draft.replaceCoverMediaId(request.coverMediaId());
    MaintenanceEstimate estimate = estimates.saveAndFlush(draft);
    estimateRevisionSupport.replaceEstimateRevision(estimate, request.lines(), request.plan(), null);
    mediaSupport.replaceMedia(
        "ESTIMATE",
        "MAINTENANCE_ESTIMATE",
        estimate.getId(),
        estimate.getWarehouseId(),
        request.mediaReferences());
    initializeEstimate(estimate);
    warehouseLifecycle.recordOperation(
        estimate.getWarehouseId(), estimate.getId(), estimate.getCreatedAt());
    EstimateResponse response = estimateModelSupport.estimateResponse(estimate);
    idempotency.store(subjectId, "estimate.create", key, requestHash, 201, response);
    return new CreateResult<>(response, false);
  }

  /**
   * Creates the estimate paired with one immutable logistics return source. The caller owns source
   * arbitration and supplies media already validated against that exact return line.
   */
  UUID createLogisticsReturnEstimate(
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
    initializeEstimate(estimate);
    warehouseLifecycle.recordOperation(
        estimate.getWarehouseId(), estimate.getId(), estimate.getCreatedAt());
    return estimate.getId();
  }

  private void initializeEstimate(MaintenanceEstimate estimate) {
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
  }
}
