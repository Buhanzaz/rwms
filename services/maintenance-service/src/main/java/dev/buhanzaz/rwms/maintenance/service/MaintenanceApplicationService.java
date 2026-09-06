package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns maintenance command orchestration for catalog, estimate, repair and related recovery flows. Remote reads and effects are deliberately separated from local write transactions; versions, idempotency and durable recovery fence every mutable workflow. */
@Service
public class MaintenanceApplicationService {
  private final MaintenanceTransferUseCases transfers;
  private final MaintenanceCatalogUseCases catalogs;
  private final MaintenanceEstimateUseCases estimates;
  private final MaintenanceRepairUseCases repairs;
  private final MaintenanceInboundUseCases inbound;
  private final MaintenanceReconciliationUseCases reconciliations;

  public MaintenanceApplicationService(
      MaintenanceTransferUseCases transfers,
      MaintenanceCatalogUseCases catalogs,
      MaintenanceEstimateUseCases estimates,
      MaintenanceRepairUseCases repairs,
      MaintenanceInboundUseCases inbound,
      MaintenanceReconciliationUseCases reconciliations) {
    this.transfers = transfers;
    this.catalogs = catalogs;
    this.estimates = estimates;
    this.repairs = repairs;
    this.inbound = inbound;
    this.reconciliations = reconciliations;
  }

  /** Preserves the public replay marker used by controllers and existing tests. */
  public record CreateResult<T>(T response, boolean replayed) {}

  public CreateResult<PrepareTransferRepairResponse> prepareTransferDeparture(
      UUID transferId,
      UUID lineId,
      UUID key,
      TransferRepairRequest request) {
    return facadeResult(transfers.prepareTransferDeparture(transferId, lineId, key, request));
  }

  public TransferRepairArrivalPreflightResponse transferArrivalPreflight(
      UUID transferId, UUID lineId, TransferRepairRequest request) {
    return transfers.transferArrivalPreflight(transferId, lineId, request);
  }

  public CreateResult<CompleteTransferRepairResponse> completeTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID key,
      CompleteTransferRepairRequest request) {
    return facadeResult(transfers.completeTransferArrival(transferId, lineId, key, request));
  }
  @Transactional(readOnly = true)
  public List<CatalogVersionResponse> catalogVersions(UUID authorizationWarehouseId) {
    return catalogs.catalogVersions(authorizationWarehouseId);
  }

  public List<CatalogVersionResponse> catalogVersions() {
    return catalogs.catalogVersions();
  }

  @Transactional(readOnly = true)
  public CatalogVersionResponse catalogVersion(UUID id) {
    return catalogs.catalogVersion(id);
  }

  @Transactional(readOnly = true)
  public CatalogVersionResponse catalogVersion(UUID id, UUID authorizationWarehouseId) {
    return catalogs.catalogVersion(id, authorizationWarehouseId);
  }

  @Transactional(readOnly = true)
  public List<CatalogNodeResponse> catalogNodes(UUID id) {
    return catalogs.catalogNodes(id);
  }

  @Transactional(readOnly = true)
  public List<CatalogLinkResponse> catalogLinks(UUID id) {
    return catalogs.catalogLinks(id);
  }

  public CreateResult<CatalogVersionResponse> createCatalog(
      UUID subjectId, UUID key, CreateCatalogRequest request) {
    return facadeResult(catalogs.createCatalog(subjectId, key, request));
  }

  public CreateResult<CatalogVersionResponse> createGlobalCatalog(UUID subjectId, UUID key) {
    return facadeResult(catalogs.createGlobalCatalog(subjectId, key));
  }

  public CatalogVersionResponse changeCatalog(UUID id, ChangeCatalogRequest request) {
    return catalogs.changeCatalog(id, request);
  }

  public CatalogVersionResponse changeCatalog(
      UUID id, UUID routingContextWarehouseId, ChangeCatalogRequest request) {
    return catalogs.changeCatalog(id, routingContextWarehouseId, request);
  }

  public CatalogVersionResponse replaceCatalogNodes(UUID id, ReplaceCatalogNodesRequest request) {
    return catalogs.replaceCatalogNodes(id, request);
  }

  public CatalogVersionResponse replaceCatalogNodes(
      UUID id, UUID routingContextWarehouseId, ReplaceCatalogNodesRequest request) {
    return catalogs.replaceCatalogNodes(id, routingContextWarehouseId, request);
  }

  public CatalogVersionResponse replaceCatalogLinks(UUID id, ReplaceCatalogLinksRequest request) {
    return catalogs.replaceCatalogLinks(id, request);
  }

  public CatalogVersionResponse replaceCatalogLinks(
      UUID id, UUID routingContextWarehouseId, ReplaceCatalogLinksRequest request) {
    return catalogs.replaceCatalogLinks(id, routingContextWarehouseId, request);
  }

  public CreateResult<CatalogVersionResponse> forkCatalog(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    return facadeResult(catalogs.forkCatalog(subjectId, key, id, request));
  }

  public CreateResult<CatalogVersionResponse> activateCatalog(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    return facadeResult(catalogs.activateCatalog(subjectId, key, id, request));
  }

  public CreateResult<CatalogVersionResponse> activateCatalog(
      UUID subjectId,
      UUID key,
      UUID id,
      UUID routingContextWarehouseId,
      VersionCommand request) {
    return facadeResult(
        catalogs.activateCatalog(subjectId, key, id, routingContextWarehouseId, request));
  }
  @Transactional(readOnly = true)
  public PageResponse<EstimateResponse> estimates(
      UUID warehouseId, int page, int size, EstimateState lifecycle, UUID rentalItemId) {
    return estimates.estimates(warehouseId, page, size, lifecycle, rentalItemId);
  }

  @Transactional(readOnly = true)
  public EstimateResponse estimate(UUID id) {
    return estimates.estimate(id);
  }

  @Transactional(readOnly = true)
  public EstimateResponse estimate(UUID id, UUID warehouseId) {
    return estimates.estimate(id, warehouseId);
  }

  public CreateResult<EstimateResponse> createEstimate(
      UUID subjectId, UUID key, CreateEstimateRequest request) {
    return facadeResult(estimates.createEstimate(subjectId, key, request));
  }

  /**
   * Creates the maintenance-owned estimate paired with one immutable logistics return source.
   *
   * <p>The caller owns the concurrent source-key arbitration and transaction. Logistics has
   * already validated these opaque references against the exact return-line media owner before
   * invoking the maintenance boundary, so they are persisted without pretending that their source
   * media owner was already the newly generated estimate ID.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID createLogisticsReturnEstimate(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReferenceInput> sourceMediaReferences) {
    return estimates.createLogisticsReturnEstimate(warehouseId, rentalItemId, rentalItemVersion, dispatchDate, sourceMediaReferences);
  }

  public EstimateResponse updateEstimate(UUID id, UpdateEstimateRequest request) {
    return estimates.updateEstimate(id, request);
  }

  public CreateResult<EstimateCommandResult> completeEstimate(
      UUID subjectId, UUID key, UUID id, CompleteEstimateRequest request) {
    return facadeResult(estimates.completeEstimate(subjectId, key, id, request));
  }

  public CreateResult<EstimateCommandResult> amendEstimate(
      UUID subjectId, UUID key, UUID id, AmendEstimateRequest request) {
    return facadeResult(estimates.amendEstimate(subjectId, key, id, request));
  }

  /**
   * Delegates the warehouse-fenced, database-paged repair collection read, including its exact
   * estimate and bounded repair ID filters.
   */
  @Transactional(readOnly = true)
  public PageResponse<RepairResponse> repairs(
      UUID warehouseId,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      UUID rentalItemId,
      UUID estimateId,
      Set<UUID> repairIds,
      int page,
      int size) {
    return repairs.repairs(
        warehouseId,
        executionState,
        acceptanceState,
        rentalItemId,
        estimateId,
        repairIds,
        page,
        size);
  }

  @Transactional(readOnly = true)
  public List<RepairResponse> activeCapitalRepairs(UUID warehouseId) {
    return repairs.activeCapitalRepairs(warehouseId);
  }

  @Transactional(readOnly = true)
  public RepairResponse activeCapitalRepair(UUID repairId) {
    return repairs.activeCapitalRepair(repairId);
  }

  @Transactional(readOnly = true)
  public RepairResponse repair(UUID id) {
    return repairs.repair(id);
  }

  @Transactional(readOnly = true)
  public RepairResponse repair(UUID id, UUID warehouseId) {
    return repairs.repair(id, warehouseId);
  }

  @Transactional(readOnly = true)
  public List<RepairWorkerEvidenceResponse> repairWorkerEvidence(UUID repairId) {
    return repairs.repairWorkerEvidence(repairId);
  }

  @Transactional(readOnly = true)
  public RepairPlanResponse repairPlan(UUID id) {
    return repairs.repairPlan(id);
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
    repairs.activateQueuedRepairAfterDelivery(warehouseId, repairId);
  }

  @Transactional(readOnly = true)
  public ReworkCandidatesResponse reworkCandidates(UUID repairId, UUID warehouseId) {
    return repairs.reworkCandidates(repairId, warehouseId);
  }

  public CreateResult<RepairResponse> createDirectRepair(
      UUID subjectId, UUID key, CreateDirectRepairRequest request) {
    return facadeResult(repairs.createDirectRepair(subjectId, key, request));
  }

  public RepairResponse updateRepairPlan(UUID id, UpdateRepairPlanRequest request) {
    return repairs.updateRepairPlan(id, request);
  }

  public CreateResult<RepairCommandResult> queueRepair(
      UUID subjectId, UUID key, UUID id, QueueRepairRequest request) {
    return facadeResult(repairs.queueRepair(subjectId, key, id, request));
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
    return facadeResult(repairs.retryInboundDelivery(subjectId, key, id, warehouseId, request));
  }

  public CreateResult<RepairCommandResult> queueRepair(
      UUID subjectId, UUID key, UUID id, VersionCommand request) {
    return facadeResult(repairs.queueRepair(subjectId, key, id, request));
  }

  public CreateResult<RepairResponse> createRework(
      UUID subjectId, UUID key, UUID sourceId, CreateReworkRequest request) {
    return facadeResult(repairs.createRework(subjectId, key, sourceId, request));
  }

  public CreateResult<RepairCommandResult> accept(
      UUID subjectId, UUID key, UUID id, RepairDecisionRequest request) {
    return facadeResult(repairs.accept(subjectId, key, id, request));
  }

  /**
   * Delegates the warehouse-fenced actionable acceptance read; all filters and pagination remain
   * in the maintenance database.
   */
  @Transactional(readOnly = true)
  public PageResponse<AcceptanceProjection> acceptance(
      UUID warehouseId,
      RepairAcceptanceState state,
      UUID repairId,
      int page,
      int size) {
    return repairs.acceptance(warehouseId, state, repairId, page, size);
  }
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyInboundMediaFact(
      UUID mediaId,
      long generation,
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      String status,
      String safeMetadata,
      long aggregateVersion) {
    inbound.applyInboundMediaFact(mediaId, generation, ownerType, ownerId, warehouseId, status, safeMetadata, aggregateVersion);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyInboundRentalItemFact(
      UUID rentalItemId,
      UUID warehouseId,
      String status,
      long aggregateVersion) {
    inbound.applyInboundRentalItemFact(rentalItemId, warehouseId, status, aggregateVersion);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyInboundLeaseFact(
      UUID leaseId,
      UUID rentalItemId,
      long fencingToken,
      String state,
      long aggregateVersion) {
    inbound.applyInboundLeaseFact(leaseId, rentalItemId, fencingToken, state, aggregateVersion);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void applyInboundTaskOutcome(
      UUID eventId,
      String eventType,
      UUID externalTaskId,
      UUID queueEntryId,
      long queueEntryVersion,
      OffsetDateTime occurredAt) {
    inbound.applyInboundTaskOutcome(eventId, eventType, externalTaskId, queueEntryId, queueEntryVersion, occurredAt);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void applyInboundTaskSchedule(
      UUID externalTaskId, LocalDate scheduledDate, long taskBoardVersion) {
    inbound.applyInboundTaskSchedule(externalTaskId, scheduledDate, taskBoardVersion);
  }

  private static <T> CreateResult<T> facadeResult(
      dev.buhanzaz.rwms.maintenance.service.CreateResult<T> result) {
    return new CreateResult<>(result.response(), result.replayed());
  }

  public boolean reconcileOneTask() {
    return reconciliations.reconcileOneTask();
  }

  public boolean reconcileOneMediaOwnerProof() {
    return reconciliations.reconcileOneMediaOwnerProof();
  }
}
