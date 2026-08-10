package dev.buhanzaz.rwms.logistics.integration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

/**
 * Direct client-credentials transport for only the approved Stage 8 private
 * boundaries. It intentionally has no access to the incoming user JWT.
 *
 * <p>This public package seam is intentionally only a facade: each delegated remote-owner client
 * owns its wire mapping and validation, while the shared leaf owns OAuth and HTTP failure policy.
 */
final class HttpLogisticsDependencyGateway implements LogisticsDependencyGateway {
  private final LogisticsWarehouseDependencyClient warehouse;
  private final LogisticsAssetOperationsDependencyClient assetOperations;
  private final LogisticsAssetOrderPresentationDependencyClient assetOrderPresentation;
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

  @Override
  public WarehouseIdentity readWarehouseIdentity(UUID warehouseId) {
    return warehouse.readWarehouseIdentity(warehouseId);
  }

  @Override
  public WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    return warehouse.warehouseAdmission(warehouseId, direction);
  }

  @Override
  public WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(UUID after, int limit) {
    return warehouse.warehouseLifecycleReadinessWork(after, limit);
  }

  @Override
  public WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    return warehouse.confirmWarehouseLifecycleReadiness(warehouseId, expectedVersion);
  }

  @Override
  public WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at) {
    return warehouse.warehouseTimeZoneAt(warehouseId, at);
  }

  @Override
  public void markWarehouseOperation(
      UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    warehouse.markWarehouseOperation(warehouseId, operationId, occurredAt);
  }

  @Override
  public List<WarehouseIdentity> listWarehouseIdentities() {
    return warehouse.listWarehouseIdentities();
  }

  @Override
  public RentalItemSnapshot readRentalItemSnapshot(UUID assetId) {
    return assetOperations.readRentalItemSnapshot(assetId);
  }

  @Override
  public OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
    return assetOperations.acquireReturnLease(
        idempotencyKey, assetId, expectedAssetVersion, documentId, lineId);
  }

  @Override
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

  @Override
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

  @Override
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

  @Override
  public RentalItemSnapshot applyReturnIntake(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId) {
    return assetOperations.applyReturnIntake(
        idempotencyKey,
        assetId,
        expectedAssetVersion,
        leaseId,
        fencingToken,
        documentId,
        lineId);
  }

  @Override
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

  @Override
  public ReturnEquipmentReceipt receiveReturnEquipment(
      UUID idempotencyKey,
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLine> lines) {
    return assetOperations.receiveReturnEquipment(
        idempotencyKey, returnId, returnLineId, warehouseId, lines);
  }

  @Override
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

  @Override
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

  @Override
  public TransferRepairDeparture prepareTransferDeparture(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    return maintenance.prepareTransferDeparture(
        idempotencyKey,
        transferId,
        lineId,
        rentalItemId,
        sourceWarehouseId,
        targetWarehouseId);
  }

  @Override
  public TransferRepairArrivalPreflight preflightTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    return maintenance.preflightTransferArrival(
        transferId, lineId, rentalItemId, sourceWarehouseId, targetWarehouseId);
  }

  @Override
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

  @Override
  public OperationLease releaseOperationLease(
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId) {
    return assetOperations.releaseOperationLease(
        idempotencyKey,
        leaseId,
        expectedLeaseVersion,
        fencingToken,
        ownerType,
        documentId,
        lineId);
  }

  @Override
  public MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references) {
    return media.validateMediaReferences(ownerType, documentId, lineId, warehouseId, references);
  }

  @Override
  public MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {
    return media.upsertMediaOwnerProof(
        ownerType,
        documentId,
        lineId,
        warehouseId,
        ownerRevision,
        aggregateVersion,
        proofEventId,
        active);
  }

  @Override
  public ReturnEstimateSource upsertReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReference> mediaReferences) {
    return maintenance.upsertReturnEstimateSource(
        returnId,
        lineId,
        warehouseId,
        rentalItemId,
        rentalItemVersion,
        dispatchDate,
        mediaReferences);
  }

  @Override
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

  @Override
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

  @Override
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

  @Override
  public EquipmentMovementReservation releaseEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID reservationId,
      long expectedReservationVersion,
      UUID movementId,
      UUID lineId) {
    return assetOperations.releaseEquipmentMovementReservation(
        idempotencyKey, reservationId, expectedReservationVersion, movementId, lineId);
  }

  @Override
  public EquipmentMovementExecution executeEquipmentMovement(
      UUID idempotencyKey, UUID movementId, List<EquipmentMovementExecutionRequestLine> lines) {
    return assetOperations.executeEquipmentMovement(idempotencyKey, movementId, lines);
  }

  @Override
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

  @Override
  public EquipmentMovementBoardTask readEquipmentMovementTask(UUID externalTaskId) {
    return taskBoard.readEquipmentMovementTask(externalTaskId);
  }

  @Override
  public EquipmentMovementBoardTask cancelEquipmentMovementTask(
      UUID externalTaskId, long expectedTaskVersion) {
    return taskBoard.cancelEquipmentMovementTask(externalTaskId, expectedTaskVersion);
  }

  @Override
  public WarehouseDriverQueue readWarehouseDriverQueue(UUID warehouseId) {
    return taskBoard.readWarehouseDriverQueue(warehouseId);
  }

  @Override
  public boolean isWarehouseDriverQueueAvailable(UUID warehouseId) {
    return taskBoard.isWarehouseDriverQueueAvailable(warehouseId);
  }

  @Override
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

  @Override
  public DriverBoardTask readDriverTask(UUID externalTaskId) {
    return taskBoard.readDriverTask(externalTaskId);
  }

  @Override
  public DriverBoardTask cancelDriverTask(UUID externalTaskId, long expectedTaskVersion) {
    return taskBoard.cancelDriverTask(externalTaskId, expectedTaskVersion);
  }

  @Override
  public DriverTaskPreStartCancellation cancelDriverTaskIfPreStart(
      UUID externalTaskId, long expectedTaskVersion, String reason) {
    return taskBoard.cancelDriverTaskIfPreStart(externalTaskId, expectedTaskVersion, reason);
  }

  @Override
  public DriverBoardTask setDriverTaskLane(
      UUID externalTaskId, long expectedTaskVersion, String lane) {
    return taskBoard.setDriverTaskLane(externalTaskId, expectedTaskVersion, lane);
  }

  @Override
  public DriverBoardSnapshot readDriverBoard(UUID warehouseId) {
    return taskBoard.readDriverBoard(warehouseId);
  }

  @Override
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

  @Override
  public DriverCompletionEvidence readDriverCompletionEvidence(UUID externalTaskId) {
    return taskBoard.readDriverCompletionEvidence(externalTaskId);
  }

  @Override
  public CapitalRepairPage readCapitalRepairs(UUID warehouseId, int page, int size) {
    return maintenance.readCapitalRepairs(warehouseId, page, size);
  }

  @Override
  public CapitalRepair readCapitalRepair(UUID repairId) {
    return maintenance.readCapitalRepair(repairId);
  }

  @Override
  public RepairPlaceProjection readRepairPlaces(UUID warehouseId) {
    return maintenance.readRepairPlaces(warehouseId);
  }

  @Override
  public RepairPlaceAllocation transitionRepairPlace(
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      String transition) {
    return maintenance.transitionRepairPlace(
        idempotencyKey, warehouseId, repairId, expectedVersion, transition);
  }

  @Override
  public CabinCoverChange setCabinCoverFromTaskEvidence(
      UUID idempotencyKey,
      UUID cabinId,
      UUID taskBoardEntryId,
      UUID evidenceMediaId) {
    return media.setCabinCoverFromTaskEvidence(
        idempotencyKey, cabinId, taskBoardEntryId, evidenceMediaId);
  }

  @Override
  public OrderUnitCandidatePage readOrderUnitCandidates(
      UUID orderId, UUID warehouseId, int page, int size, String search) {
    return assetOrderPresentation.readOrderUnitCandidates(orderId, warehouseId, page, size, search);
  }

  @Override
  public List<OrderUnitReservation> readOrderUnits(UUID orderId) {
    return assetOrderPresentation.readOrderUnits(orderId);
  }

  @Override
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

  @Override
  public OrderUnitReservation releaseOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID unitId,
      UUID actorSubjectId,
      String actorRole) {
    return assetOrderPresentation.releaseOrderUnit(
        idempotencyKey, orderId, unitId, actorSubjectId, actorRole);
  }

  @Override
  public List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole) {
    return assetOrderPresentation.releaseAllOrderUnits(
        idempotencyKey, orderId, actorSubjectId, actorRole);
  }

  @Override
  public List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderEquipmentRequirement> requirements) {
    return assetOrderPresentation.replaceOrderEquipmentReservations(
        idempotencyKey, orderId, warehouseId, actorSubjectId, actorRole, requirements);
  }

  @Override
  public OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      List<OrderEquipmentRequirement> unitRequirements,
      List<OrderEquipmentRequirement> orderRequirements) {
    return assetOrderPresentation.planOrderFurnitureMovements(
        orderId, warehouseId, unitId, unitRequirements, orderRequirements);
  }

  @Override
  public CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId,
      UUID rentalItemId,
      List<CabinFurnitureRequirement> requirements) {
    return assetOrderPresentation.planCabinFurnitureMovements(
        warehouseId, rentalItemId, requirements);
  }

  @Override
  public CabinFacets readAvailableCabinFacets(UUID warehouseId, UUID holdScopeId) {
    return assetOrderPresentation.readAvailableCabinFacets(warehouseId, holdScopeId);
  }

  @Override
  public CabinCatalogPage readCabinCatalog(
      UUID warehouseId, String query, int page, int size) {
    return assetOrderPresentation.readCabinCatalog(warehouseId, query, page, size);
  }

  @Override
  public CabinSearchResult searchAvailableCabins(
      UUID downstreamIdempotencyKey, String exactRequestBody) {
    return assetOrderPresentation.searchAvailableCabins(
        downstreamIdempotencyKey, exactRequestBody);
  }

  @Override
  public List<AvailableCabin> readCabinSnapshots(UUID warehouseId, List<UUID> rentalItemIds) {
    return assetOrderPresentation.readCabinSnapshots(warehouseId, rentalItemIds);
  }

  @Override
  public CabinAvailability readCabinAvailability(UUID warehouseId, List<UUID> rentalItemIds) {
    return assetOrderPresentation.readCabinAvailability(warehouseId, rentalItemIds);
  }

  @Override
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

  @Override
  public PresentationHolds replacePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    return assetOrderPresentation.replacePresentationHoldsExact(
        idempotencyKey, presentationId, exactRequestBody);
  }

  @Override
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

  @Override
  public PresentationHolds readPresentationHolds(UUID presentationId) {
    return assetOrderPresentation.readPresentationHolds(presentationId);
  }

  @Override
  public PresentationHolds readPresentationHolds(
      UUID presentationId, UUID actorSubjectId, String actorRole) {
    return assetOrderPresentation.readPresentationHolds(presentationId, actorSubjectId, actorRole);
  }

  @Override
  public PresentationHolds releasePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole) {
    return assetOrderPresentation.releasePresentationHolds(
        idempotencyKey, presentationId, actorSubjectId, actorRole);
  }

  @Override
  public PresentationHolds releasePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    return assetOrderPresentation.releasePresentationHoldsExact(
        idempotencyKey, presentationId, exactRequestBody);
  }

  @Override
  public ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole) {
    return assetOrderPresentation.convertPresentationHolds(
        idempotencyKey,
        presentationId,
        orderId,
        warehouseId,
        selectedRentalItemIds,
        clientId,
        tenantSnapshot,
        actorSubjectId,
        actorRole);
  }

  @Override
  public List<CabinMediaSnapshot> readCabinMediaSnapshots(
      UUID warehouseId, List<UUID> cabinIds) {
    return media.readCabinMediaSnapshots(warehouseId, cabinIds);
  }

  @Override
  public MediaContent readCabinPresentationMedia(
      UUID warehouseId,
      UUID cabinId,
      UUID mediaId,
      long generation,
      String variant) {
    return media.readCabinPresentationMedia(warehouseId, cabinId, mediaId, generation, variant);
  }

  private static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
