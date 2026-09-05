package dev.buhanzaz.rwms.logistics.integration;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

/**
 * Direct client-credentials transport for only the approved Stage 8 private boundaries. It
 * intentionally has no access to the incoming user JWT.
 *
 * <p>This public package seam is intentionally only a facade: each delegated remote-owner client
 * owns its wire mapping and validation, while the shared leaf owns OAuth and HTTP failure policy.
 */
final class HttpLogisticsDependencyGateway implements LogisticsDependencyGateway {
  private final LogisticsWarehouseDependencyClient warehouse;
  private final LogisticsAssetOperationsDependencyClient assetOperations;
  private final LogisticsAssetOrderPresentationDependencyClient assetOrderPresentation;
  private final LogisticsAssetPricingDependencyClient assetPricing;
  private final LogisticsMaintenanceDependencyClient maintenance;
  private final LogisticsMediaDependencyClient media;
  private final LogisticsTaskBoardDependencyClient taskBoard;

  HttpLogisticsDependencyGateway(
      RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      LogisticsDependencyProperties.Validated properties) {
    LogisticsOAuthHttpTransport transport =
        new LogisticsOAuthHttpTransport(client, authorizedClients);
    String assetBase =
        strip(properties.assetBaseUrl().toString()) + "/api/internal/asset/v1/logistics";
    String warehouseRoot = strip(properties.warehouseBaseUrl().toString());
    warehouse =
        new LogisticsWarehouseDependencyClient(
            transport,
            warehouseRoot + "/api/internal/warehouse/v1/warehouses/logistics",
            warehouseRoot + "/api/internal/warehouse/v1");
    assetOperations = new LogisticsAssetOperationsDependencyClient(transport, assetBase);
    assetOrderPresentation =
        new LogisticsAssetOrderPresentationDependencyClient(transport, assetBase);
    assetPricing = new LogisticsAssetPricingDependencyClient(transport, assetBase);
    maintenance =
        new LogisticsMaintenanceDependencyClient(
            transport,
            strip(properties.maintenanceBaseUrl().toString())
                + "/api/internal/maintenance/v1/logistics");
    media =
        new LogisticsMediaDependencyClient(
            transport, strip(properties.mediaBaseUrl().toString()) + "/api/internal/media/v1");
    taskBoard =
        new LogisticsTaskBoardDependencyClient(
            transport, strip(properties.taskBoardBaseUrl().toString()));
  }

  public WarehouseIdentity readWarehouseIdentity(UUID warehouseId) {
    return warehouse.readWarehouseIdentity(warehouseId);
  }

  public WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    return warehouse.warehouseAdmission(warehouseId, direction);
  }

  public WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(
      UUID after, int limit) {
    return warehouse.warehouseLifecycleReadinessWork(after, limit);
  }

  public WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    return warehouse.confirmWarehouseLifecycleReadiness(warehouseId, expectedVersion);
  }

  public WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at) {
    return warehouse.warehouseTimeZoneAt(warehouseId, at);
  }

  public void markWarehouseOperation(
      UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    warehouse.markWarehouseOperation(warehouseId, operationId, occurredAt);
  }

  public List<WarehouseIdentity> listWarehouseIdentities() {
    return warehouse.listWarehouseIdentities();
  }

  public List<WarehouseSupportLink> listWarehouseSupportLinks(
      UUID servedWarehouseId, OffsetDateTime at) {
    return warehouse.listWarehouseSupportLinks(servedWarehouseId, at);
  }

  public List<WarehouseSupportLink> listWarehouseSupportNetwork(UUID warehouseId) {
    return warehouse.listWarehouseSupportNetwork(warehouseId);
  }

  public List<WarehouseDriverIdentity> listWarehouseDrivers(UUID warehouseId) {
    return taskBoard.listWarehouseDrivers(warehouseId);
  }

  public List<WarehouseDriverIdentity> listWarehouseDrivers(
      UUID warehouseId, OffsetDateTime at, boolean includeIncoming) {
    return taskBoard.listWarehouseDrivers(warehouseId, at, includeIncoming);
  }

  public void registerDriverShiftPlan(
      UUID idempotencyKey, UUID sourceShiftId, DriverShiftPlanSnapshot plan) {
    taskBoard.registerDriverShiftPlan(idempotencyKey, sourceShiftId, plan);
  }

  public PlanningReplacementResult replacePlanningAssignments(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplacementSnapshot replacement) {
    return taskBoard.replacePlanningAssignments(sourcePlanId, idempotencyKey, replacement);
  }

  @Override
  public PlanningReplanPrepareResult preparePlanningReschedule(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplanPrepareSnapshot request) {
    return taskBoard.preparePlanningReschedule(sourcePlanId, idempotencyKey, request);
  }

  @Override
  public PlanningReplanCommitResult commitPlanningReschedule(
      UUID holdId, UUID idempotencyKey) {
    return taskBoard.commitPlanningReschedule(holdId, idempotencyKey);
  }

  @Override
  public PlanningReplanReleaseResult releasePlanningReschedule(
      UUID holdId, UUID idempotencyKey) {
    return taskBoard.releasePlanningReschedule(holdId, idempotencyKey);
  }

  public WorkerOperationalAssignment createWorkerOperationalAssignment(
      UUID transferId,
      UUID workerId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      String mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil) {
    return taskBoard.createWorkerOperationalAssignment(
        transferId,
        workerId,
        sourceWarehouseId,
        destinationWarehouseId,
        mode,
        travelStartsAt,
        effectiveFrom,
        effectiveUntil);
  }

  public WorkerOperationalAssignment transitionWorkerOperationalAssignment(
      UUID assignmentId, long expectedVersion, String targetStatus) {
    return taskBoard.transitionWorkerOperationalAssignment(
        assignmentId, expectedVersion, targetStatus);
  }

  public RentalItemSnapshot readRentalItemSnapshot(UUID assetId) {
    return assetOperations.readRentalItemSnapshot(assetId);
  }

  public CabinPhotoPresentationAssetSnapshot readCabinPhotoPresentationSnapshot(UUID assetId) {
    return assetOperations.readCabinPhotoPresentationSnapshot(assetId);
  }

  public OperationLease acquireReturnLease(
      UUID idempotencyKey, UUID assetId, long expectedAssetVersion, UUID documentId, UUID lineId) {
    return assetOperations.acquireReturnLease(
        idempotencyKey, assetId, expectedAssetVersion, documentId, lineId);
  }

  public OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return assetOperations.acquireReturnLease(
        idempotencyKey, assetId, expectedAssetVersion, documentId, lineId, rentalOrderId);
  }

  public OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
    return assetOperations.acquireOperationLease(
        idempotencyKey, ownerType, assetId, expectedAssetVersion, documentId, lineId);
  }

  public OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return assetOperations.acquireOperationLease(
        idempotencyKey,
        ownerType,
        assetId,
        expectedAssetVersion,
        documentId,
        lineId,
        rentalOrderId);
  }

  public RentalItemSnapshot applyReturnIntake(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId) {
    return assetOperations.applyReturnIntake(
        idempotencyKey, assetId, expectedAssetVersion, leaseId, fencingToken, documentId, lineId);
  }

  public RentalItemSnapshot settleReturn(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId,
      boolean estimate) {
    return assetOperations.settleReturn(
        idempotencyKey,
        assetId,
        expectedAssetVersion,
        leaseId,
        fencingToken,
        documentId,
        lineId,
        estimate);
  }

  public ReturnEquipmentReceipt receiveReturnEquipment(
      UUID idempotencyKey,
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLine> lines) {
    return assetOperations.receiveReturnEquipment(
        idempotencyKey, returnId, returnLineId, warehouseId, lines);
  }

  public RentalItemSnapshot applyFencedEffect(
      UUID idempotencyKey,
      AssetEffect action,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId) {
    return assetOperations.applyFencedEffect(
        idempotencyKey,
        action,
        assetId,
        expectedAssetVersion,
        leaseId,
        fencingToken,
        ownerType,
        documentId,
        lineId,
        destinationWarehouseId);
  }

  public RentalItemSnapshot applyFencedEffect(
      UUID idempotencyKey,
      AssetEffect action,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId,
      String transferAssetStatus) {
    return assetOperations.applyFencedEffect(
        idempotencyKey,
        action,
        assetId,
        expectedAssetVersion,
        leaseId,
        fencingToken,
        ownerType,
        documentId,
        lineId,
        destinationWarehouseId,
        transferAssetStatus);
  }

  public TransferRepairDeparture prepareTransferDeparture(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    return maintenance.prepareTransferDeparture(
        idempotencyKey, transferId, lineId, rentalItemId, sourceWarehouseId, targetWarehouseId);
  }

  public HistoricalShipmentRepairClosure closeHistoricalShipment(
      UUID idempotencyKey,
      UUID shipmentId,
      UUID warehouseId,
      UUID rentalItemId) {
    return maintenance.closeHistoricalShipment(
        idempotencyKey, shipmentId, warehouseId, rentalItemId);
  }

  public TransferRepairArrivalPreflight preflightTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    return maintenance.preflightTransferArrival(
        transferId, lineId, rentalItemId, sourceWarehouseId, targetWarehouseId);
  }

  public TransferRepairArrivalCompletion completeTransferArrival(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Integer priority) {
    return maintenance.completeTransferArrival(
        idempotencyKey,
        transferId,
        lineId,
        rentalItemId,
        rentalItemVersion,
        sourceWarehouseId,
        targetWarehouseId,
        priority);
  }

  public OperationLease releaseOperationLease(
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId) {
    return assetOperations.releaseOperationLease(
        idempotencyKey, leaseId, expectedLeaseVersion, fencingToken, ownerType, documentId, lineId);
  }

  public MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references) {
    return media.validateMediaReferences(ownerType, documentId, lineId, warehouseId, references);
  }

  public CustomerProfileMediaValidation validateCustomerProfileMediaReference(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      MediaReference reference) {
    return media.validateCustomerProfileMediaReference(
        profileId, warehouseId, authorizedSubjectId, reference);
  }

  public MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      UUID authorizedSubjectId,
      boolean active) {
    return media.upsertMediaOwnerProof(
        ownerType,
        documentId,
        lineId,
        warehouseId,
        ownerRevision,
        aggregateVersion,
        proofEventId,
        authorizedSubjectId,
        active);
  }

  public CustomerProfileMediaOwnerProof upsertCustomerProfileMediaOwnerProof(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      UUID proofEventId) {
    return media.upsertCustomerProfileMediaOwnerProof(
        profileId, warehouseId, authorizedSubjectId, proofEventId);
  }

  public ReturnEstimateSource upsertReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      OffsetDateTime arrivedAt,
      List<MediaReference> mediaReferences) {
    return maintenance.upsertReturnEstimateSource(
        returnId,
        lineId,
        warehouseId,
        rentalItemId,
        rentalItemVersion,
        dispatchDate,
        arrivedAt,
        mediaReferences);
  }

  public EquipmentHold acquireEquipmentHold(
      UUID idempotencyKey,
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion) {
    return assetOperations.acquireEquipmentHold(
        idempotencyKey,
        equipmentId,
        warehouseId,
        shipmentId,
        shipmentLineId,
        quantity,
        expectedStockVersion);
  }

  public EquipmentHold commandEquipmentHold(
      UUID idempotencyKey,
      EquipmentHoldAction action,
      UUID holdId,
      long expectedHoldVersion,
      UUID shipmentId,
      UUID shipmentLineId) {
    return assetOperations.commandEquipmentHold(
        idempotencyKey, action, holdId, expectedHoldVersion, shipmentId, shipmentLineId);
  }

  public EquipmentMovementReservation acquireEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose) {
    return assetOperations.acquireEquipmentMovementReservation(
        idempotencyKey,
        movementId,
        lineId,
        equipmentId,
        sourceWarehouseId,
        sourceRentalItemId,
        sourceLocationKind,
        expectedSourceBalanceVersion,
        quantity,
        reservedUntil,
        purpose);
  }

  public EquipmentMovementReservation acquireEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose,
      UUID orderId,
      UUID targetRentalItemId,
      List<OrderUnitEquipmentRequirements> units) {
    return assetOperations.acquireEquipmentMovementReservation(
        idempotencyKey,
        movementId,
        lineId,
        equipmentId,
        sourceWarehouseId,
        sourceRentalItemId,
        sourceLocationKind,
        expectedSourceBalanceVersion,
        quantity,
        reservedUntil,
        purpose,
        orderId,
        targetRentalItemId,
        units);
  }

  public EquipmentMovementReservation acquireEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose,
      UUID orderId,
      UUID targetRentalItemId,
      List<OrderUnitEquipmentRequirements> units,
      UUID replacementSourceReservationId) {
    return assetOperations.acquireEquipmentMovementReservation(
        idempotencyKey,
        movementId,
        lineId,
        equipmentId,
        sourceWarehouseId,
        sourceRentalItemId,
        sourceLocationKind,
        expectedSourceBalanceVersion,
        quantity,
        reservedUntil,
        purpose,
        orderId,
        targetRentalItemId,
        units,
        replacementSourceReservationId);
  }

  public EquipmentMovementReservation releaseEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID reservationId,
      long expectedReservationVersion,
      UUID movementId,
      UUID lineId) {
    return assetOperations.releaseEquipmentMovementReservation(
        idempotencyKey, reservationId, expectedReservationVersion, movementId, lineId);
  }

  public EquipmentMovementExecution executeEquipmentMovement(
      UUID idempotencyKey, UUID movementId, List<EquipmentMovementExecutionRequestLine> lines) {
    return assetOperations.executeEquipmentMovement(idempotencyKey, movementId, lines);
  }

  public TransferUnitReservationReceipt reserveTransferUnits(
      UUID idempotencyKey,
      UUID transferId,
      UUID sourceWarehouseId,
      List<TransferUnitReservationRequestLine> lines) {
    return assetOperations.reserveTransferUnits(
        idempotencyKey, transferId, sourceWarehouseId, lines);
  }

  public TransferUnitReservationReceipt releaseTransferUnits(
      UUID idempotencyKey,
      UUID transferId,
      List<TransferUnitReservationReleaseLine> lines) {
    return assetOperations.releaseTransferUnits(idempotencyKey, transferId, lines);
  }

  public EquipmentMovementBoardTask registerEquipmentMovementTask(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperation> operations) {
    return taskBoard.registerEquipmentMovementTask(
        warehouseId, externalTaskId, unitNumber, plannedDurationMinutes, deadlineAt, operations);
  }

  public EquipmentMovementBoardTask readEquipmentMovementTask(UUID externalTaskId) {
    return taskBoard.readEquipmentMovementTask(externalTaskId);
  }

  public EquipmentMovementBoardTask cancelEquipmentMovementTask(
      UUID externalTaskId, long expectedTaskVersion) {
    return taskBoard.cancelEquipmentMovementTask(externalTaskId, expectedTaskVersion);
  }

  public WarehouseDriverQueue readWarehouseDriverQueue(UUID warehouseId) {
    return taskBoard.readWarehouseDriverQueue(warehouseId);
  }

  public boolean isWarehouseDriverQueueAvailable(UUID warehouseId) {
    return taskBoard.isWarehouseDriverQueueAvailable(warehouseId);
  }

  public DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority,
      DriverTaskAudience driverAudience) {
    return taskBoard.registerDriverTask(
        warehouseId,
        externalTaskId,
        sourceId,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        scheduledDate,
        priority,
        driverAudience);
  }

  public DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority,
      DriverTaskAudience driverAudience,
      DriverTaskWorkerContent workerContent) {
    return registerDriverTask(
        warehouseId,
        externalTaskId,
        sourceId,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        scheduledDate,
        priority,
        driverAudience,
        workerContent,
        null);
  }

  public DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority,
      DriverTaskAudience driverAudience,
      DriverTaskWorkerContent workerContent,
      DriverTaskPlannerLineage plannerLineage) {
    return taskBoard.registerDriverTask(
        warehouseId,
        externalTaskId,
        sourceId,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        scheduledDate,
        priority,
        driverAudience,
        workerContent,
        plannerLineage);
  }

  public DriverBoardTask updateDriverTaskBeforeStart(
      UUID externalTaskId,
      long expectedTaskVersion,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      DriverTaskWorkerContent workerContent) {
    return taskBoard.updateDriverTaskBeforeStart(
        externalTaskId,
        expectedTaskVersion,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        workerContent);
  }

  public DriverBoardTask readDriverTask(UUID externalTaskId) {
    return taskBoard.readDriverTask(externalTaskId);
  }

  public ContractorTaskExecution readContractorTaskExecution(UUID workerId, UUID externalTaskId) {
    return taskBoard.readContractorTaskExecution(workerId, externalTaskId);
  }

  public ContractorTaskActionResult applyContractorTaskAction(
      UUID workerId,
      UUID externalTaskId,
      UUID entryId,
      UUID idempotencyKey,
      String action,
      long expectedVersion,
      UUID evidenceId) {
    return taskBoard.applyContractorTaskAction(
        workerId, externalTaskId, entryId, idempotencyKey, action, expectedVersion, evidenceId);
  }

  public ContractorEvidenceReservation reserveContractorTaskEvidence(
      UUID workerId,
      UUID externalTaskId,
      UUID entryId,
      UUID evidenceId,
      OffsetDateTime capturedAt,
      String contentType,
      long sizeBytes,
      String sha256) {
    return taskBoard.reserveContractorTaskEvidence(
        workerId, externalTaskId, entryId, evidenceId, capturedAt, contentType, sizeBytes, sha256);
  }

  public DriverBoardTask cancelDriverTask(UUID externalTaskId, long expectedTaskVersion) {
    return taskBoard.cancelDriverTask(externalTaskId, expectedTaskVersion);
  }

  public DriverBoardTask cancelDriverTask(
      UUID externalTaskId, long expectedTaskVersion, String reason) {
    return taskBoard.cancelDriverTask(externalTaskId, expectedTaskVersion, reason);
  }

  public DriverTaskPreStartCancellation cancelDriverTaskIfPreStart(
      UUID externalTaskId, long expectedTaskVersion, String reason) {
    return taskBoard.cancelDriverTaskIfPreStart(externalTaskId, expectedTaskVersion, reason);
  }

  public DriverBoardTask setDriverTaskLane(
      UUID externalTaskId, long expectedTaskVersion, String lane) {
    return taskBoard.setDriverTaskLane(externalTaskId, expectedTaskVersion, lane);
  }

  public DriverBoardSnapshot readDriverBoard(UUID warehouseId) {
    return taskBoard.readDriverBoard(warehouseId);
  }

  public DriverBoardTask moveDriverTask(
      UUID externalTaskId,
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex,
      DriverTaskAudience targetDriverAudience) {
    return taskBoard.moveDriverTask(
        externalTaskId,
        expectedTaskVersion,
        expectedEntryVersion,
        targetLane,
        targetDate,
        targetIndex,
        targetDriverAudience);
  }

  public DriverCompletionEvidence readDriverCompletionEvidence(UUID externalTaskId) {
    return taskBoard.readDriverCompletionEvidence(externalTaskId);
  }

  public CapitalRepairPage readCapitalRepairs(UUID warehouseId, int page, int size) {
    return maintenance.readCapitalRepairs(warehouseId, page, size);
  }

  public CapitalRepair readCapitalRepair(UUID repairId) {
    return maintenance.readCapitalRepair(repairId);
  }

  public RepairPlaceProjection readRepairPlaces(UUID warehouseId) {
    return maintenance.readRepairPlaces(warehouseId);
  }

  public RepairPlaceAllocation transitionRepairPlace(
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      String transition) {
    return maintenance.transitionRepairPlace(
        idempotencyKey, warehouseId, repairId, expectedVersion, transition);
  }

  public CabinCoverChange setCabinCoverFromTaskEvidence(
      UUID idempotencyKey, UUID cabinId, UUID taskBoardEntryId, UUID evidenceMediaId) {
    return media.setCabinCoverFromTaskEvidence(
        idempotencyKey, cabinId, taskBoardEntryId, evidenceMediaId);
  }

  public OrderUnitCandidatePage readOrderUnitCandidates(
      UUID orderId, UUID warehouseId, int page, int size, String search) {
    return assetOrderPresentation.readOrderUnitCandidates(orderId, warehouseId, page, size, search);
  }

  public List<OrderUnitReservation> readOrderUnits(UUID orderId) {
    return assetOrderPresentation.readOrderUnits(orderId);
  }

  public OrderUnitReservation reserveOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole) {
    return assetOrderPresentation.reserveOrderUnit(
        idempotencyKey,
        orderId,
        warehouseId,
        unitId,
        clientId,
        tenantSnapshot,
        draftReservationExpiresAt,
        actorSubjectId,
        actorRole);
  }

  public OrderUnitReservation releaseOrderUnit(
      UUID idempotencyKey, UUID orderId, UUID unitId, UUID actorSubjectId, String actorRole) {
    return assetOrderPresentation.releaseOrderUnit(
        idempotencyKey, orderId, unitId, actorSubjectId, actorRole);
  }

  public List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole) {
    return assetOrderPresentation.releaseAllOrderUnits(
        idempotencyKey, orderId, actorSubjectId, actorRole);
  }

  public List<EquipmentWarehouseAvailability> readLogisticsEquipmentAvailability(UUID warehouseId) {
    return assetOrderPresentation.readLogisticsEquipmentAvailability(warehouseId);
  }

  public List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units) {
    return assetOrderPresentation.replaceOrderEquipmentReservations(
        idempotencyKey, orderId, warehouseId, actorSubjectId, actorRole, units);
  }

  public OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID replacementForRentalItemId,
      List<OrderEquipmentRequirement> unitRequirements,
      List<OrderUnitEquipmentRequirements> units) {
    return assetOrderPresentation.planOrderFurnitureMovements(
        orderId, warehouseId, unitId, replacementForRentalItemId, unitRequirements, units);
  }

  public OrderUnitsReplacementReceipt replaceOrderUnits(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID inventorySourceWarehouseId,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units,
      List<OrderUnitReplacement> replacements) {
    return assetOrderPresentation.replaceOrderUnits(
        idempotencyKey,
        orderId,
        warehouseId,
        inventorySourceWarehouseId,
        presentationId,
        actorSubjectId,
        actorRole,
        units,
        replacements);
  }

  public CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId, UUID rentalItemId, List<CabinFurnitureRequirement> requirements) {
    return assetOrderPresentation.planCabinFurnitureMovements(
        warehouseId, rentalItemId, requirements);
  }

  public CabinFacets readAvailableCabinFacets(UUID warehouseId, UUID holdScopeId) {
    return assetOrderPresentation.readAvailableCabinFacets(warehouseId, holdScopeId);
  }

  public RentalItemReserveSnapshot readRentalItemReserves(UUID rentalItemId, UUID warehouseId) {
    return assetOrderPresentation.readRentalItemReserves(rentalItemId, warehouseId);
  }

  public CabinPricingCatalog readCabinPricingCatalog() {
    return assetPricing.readCabinPricingCatalog();
  }

  public EquipmentPricingCatalog readEquipmentPricingCatalog() {
    return assetPricing.readEquipmentPricingCatalog();
  }

  public CabinPricingReferences readCabinPricingReferences(
      UUID warehouseId, List<UUID> rentalItemIds) {
    return assetPricing.readCabinPricingReferences(warehouseId, rentalItemIds);
  }

  public CabinCatalogPage readCabinCatalog(UUID warehouseId, String query, int page, int size) {
    return assetOrderPresentation.readCabinCatalog(warehouseId, query, page, size);
  }

  public CabinCatalogPage readCustomerCabinCatalog(
      UUID warehouseId,
      UUID holdScopeId,
      String query,
      String cabinType,
      String finish,
      String dimensions,
      String category,
      Boolean linoleum,
      List<String> characteristics,
      int page,
      int size) {
    return assetOrderPresentation.readCustomerCabinCatalog(
        warehouseId,
        holdScopeId,
        query,
        cabinType,
        finish,
        dimensions,
        category,
        linoleum,
        characteristics,
        page,
        size);
  }

  public CabinSearchResult searchAvailableCabins(
      UUID downstreamIdempotencyKey, String exactRequestBody) {
    return assetOrderPresentation.searchAvailableCabins(downstreamIdempotencyKey, exactRequestBody);
  }

  public List<AvailableCabin> readCabinSnapshots(UUID warehouseId, List<UUID> rentalItemIds) {
    return assetOrderPresentation.readCabinSnapshots(warehouseId, rentalItemIds);
  }

  public CabinAvailability readCabinAvailability(UUID warehouseId, List<UUID> rentalItemIds) {
    return assetOrderPresentation.readCabinAvailability(warehouseId, rentalItemIds);
  }

  public PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole) {
    return assetOrderPresentation.replacePresentationHolds(
        idempotencyKey,
        presentationId,
        warehouseId,
        rentalItemIds,
        expiresAt,
        actorSubjectId,
        actorRole);
  }

  public PresentationHolds replacePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    return assetOrderPresentation.replacePresentationHoldsExact(
        idempotencyKey, presentationId, exactRequestBody);
  }

  public PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      UUID sourceHoldScopeId) {
    return assetOrderPresentation.replacePresentationHolds(
        idempotencyKey,
        presentationId,
        warehouseId,
        rentalItemIds,
        expiresAt,
        actorSubjectId,
        actorRole,
        sourceHoldScopeId);
  }

  public PresentationHolds readPresentationHolds(UUID presentationId) {
    return assetOrderPresentation.readPresentationHolds(presentationId);
  }

  public PresentationHolds readPresentationHolds(
      UUID presentationId, UUID actorSubjectId, String actorRole) {
    return assetOrderPresentation.readPresentationHolds(presentationId, actorSubjectId, actorRole);
  }

  public PresentationHolds releasePresentationHolds(
      UUID idempotencyKey, UUID presentationId, UUID actorSubjectId, String actorRole) {
    return assetOrderPresentation.releasePresentationHolds(
        idempotencyKey, presentationId, actorSubjectId, actorRole);
  }

  public PresentationHolds releasePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    return assetOrderPresentation.releasePresentationHoldsExact(
        idempotencyKey, presentationId, exactRequestBody);
  }

  public ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units) {
    return assetOrderPresentation.convertPresentationHolds(
        idempotencyKey,
        presentationId,
        orderId,
        warehouseId,
        selectedRentalItemIds,
        clientId,
        tenantSnapshot,
        actorSubjectId,
        actorRole,
        units);
  }

  public List<CabinMediaSnapshot> readCabinMediaSnapshots(UUID warehouseId, List<UUID> cabinIds) {
    return media.readCabinMediaSnapshots(warehouseId, cabinIds);
  }

  public MediaContent readCabinPresentationMedia(
      UUID warehouseId, UUID cabinId, UUID mediaId, long generation, String variant) {
    return media.readCabinPresentationMedia(warehouseId, cabinId, mediaId, generation, variant);
  }

  public ContractorEvidenceMediaReceipt uploadContractorTaskEvidence(
      UUID warehouseId,
      UUID workerId,
      UUID entryId,
      UUID evidenceId,
      String contentType,
      String sha256,
      byte[] bytes) {
    return media.uploadContractorTaskEvidence(
        warehouseId, workerId, entryId, evidenceId, contentType, sha256, bytes);
  }

  public MediaContent readContractorTaskMedia(
      UUID warehouseId,
      UUID workerId,
      UUID entryId,
      UUID mediaId,
      long generation,
      String variant) {
    return media.readContractorTaskMedia(
        warehouseId, workerId, entryId, mediaId, generation, variant);
  }

  private static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
