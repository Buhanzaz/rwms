package dev.buhanzaz.rwms.logistics.integration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned, local transport boundary for the small Stage 8 private APIs.
 * Incoming user credentials never cross this interface.
 */
public interface LogisticsDependencyGateway {
  WarehouseIdentity readWarehouseIdentity(UUID warehouseId);

  default List<WarehouseIdentity> listWarehouseIdentities() {
    throw unavailable("Warehouse identity listing is not configured");
  }

  RentalItemSnapshot readRentalItemSnapshot(UUID assetId);

  OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId);

  default OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return acquireReturnLease(
        idempotencyKey, assetId, expectedAssetVersion, documentId, lineId);
  }

  OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId);

  default OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return acquireOperationLease(
        idempotencyKey,
        ownerType,
        assetId,
        expectedAssetVersion,
        documentId,
        lineId);
  }

  RentalItemSnapshot applyReturnIntake(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId);

  RentalItemSnapshot settleReturn(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId,
      boolean shortage);

  default ReturnEquipmentReceipt receiveReturnEquipment(
      UUID idempotencyKey,
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLine> lines) {
    throw new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION,
        "Return equipment receipts are not configured");
  }

  RentalItemSnapshot applyFencedEffect(
      UUID idempotencyKey,
      AssetEffect action,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId);

  default RentalItemSnapshot applyFencedEffect(
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
    return applyFencedEffect(
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

  default TransferRepairDeparture prepareTransferDeparture(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    throw unavailable("Maintenance transfer departure preparation is not configured");
  }

  default TransferRepairArrivalPreflight preflightTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    throw unavailable("Maintenance transfer arrival preflight is not configured");
  }

  default TransferRepairArrivalCompletion completeTransferArrival(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Integer priority,
      boolean movementToShipment) {
    throw unavailable("Maintenance transfer arrival completion is not configured");
  }

  OperationLease releaseOperationLease(
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId);

  MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references);

  MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active);

  ReturnShortageSource upsertReturnShortage(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReference> mediaReferences,
      List<EquipmentShortage> shortages);

  EquipmentHold acquireEquipmentHold(
      UUID idempotencyKey,
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion);

  EquipmentHold commandEquipmentHold(
      UUID idempotencyKey,
      EquipmentHoldAction action,
      UUID holdId,
      long expectedHoldVersion,
      UUID shipmentId,
      UUID shipmentLineId);

  EquipmentMovementReservation acquireEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil);

  EquipmentMovementReservation releaseEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID reservationId,
      long expectedReservationVersion,
      UUID movementId,
      UUID lineId);

  EquipmentMovementExecution executeEquipmentMovement(
      UUID idempotencyKey, UUID movementId, List<EquipmentMovementExecutionRequestLine> lines);

  EquipmentMovementBoardTask registerEquipmentMovementTask(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperation> operations);

  EquipmentMovementBoardTask readEquipmentMovementTask(UUID externalTaskId);

  EquipmentMovementBoardTask cancelEquipmentMovementTask(
      UUID externalTaskId, long expectedTaskVersion);

  OrderUnitCandidatePage readOrderUnitCandidates(
      UUID orderId, UUID warehouseId, int page, int size, String search);

  List<OrderUnitReservation> readOrderUnits(UUID orderId);

  OrderUnitReservation reserveOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole);

  OrderUnitReservation releaseOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID unitId,
      UUID actorSubjectId,
      String actorRole);

  List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole);

  List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderEquipmentRequirement> requirements);

  OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      List<OrderEquipmentRequirement> unitRequirements,
      List<OrderEquipmentRequirement> orderRequirements);

  CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId,
      UUID rentalItemId,
      List<CabinFurnitureRequirement> requirements);

  default CabinFacets readAvailableCabinFacets(UUID warehouseId, UUID holdScopeId) {
    throw unavailable("Cabin facets are not configured");
  }

  default CabinSearchResult searchAvailableCabins(
      UUID warehouseId,
      UUID holdScopeId,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      List<CabinSearchGroup> groups) {
    throw unavailable("Cabin search is not configured");
  }

  default List<AvailableCabin> readCabinSnapshots(
      UUID warehouseId, List<UUID> rentalItemIds) {
    throw unavailable("Cabin snapshots are not configured");
  }

  default CabinAvailability readCabinAvailability(
      UUID warehouseId, List<UUID> rentalItemIds) {
    throw unavailable("Cabin availability is not configured");
  }

  default PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole) {
    throw unavailable("Presentation holds are not configured");
  }

  default PresentationHolds readPresentationHolds(UUID presentationId) {
    throw unavailable("Presentation holds are not configured");
  }

  default PresentationHolds releasePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole) {
    throw unavailable("Presentation holds are not configured");
  }

  default ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole) {
    throw unavailable("Presentation hold conversion is not configured");
  }

  default List<CabinMediaSnapshot> readCabinMediaSnapshots(
      UUID warehouseId, List<UUID> cabinIds) {
    throw unavailable("Presentation media snapshots are not configured");
  }

  default MediaContent readCabinPresentationMedia(
      UUID warehouseId,
      UUID cabinId,
      UUID mediaId,
      long generation,
      String variant) {
    throw unavailable("Presentation media content is not configured");
  }

  record WarehouseIdentity(
      UUID id,
      long version,
      boolean active,
      String name,
      String city,
      String timeZone) {
    public WarehouseIdentity(UUID id, long version, boolean active, String timeZone) {
      this(id, version, active, "", "", timeZone);
    }
  }

  record EquipmentContent(UUID equipmentId, long quantity) {}

  record RentalItemSnapshot(
      UUID assetId,
      long version,
      UUID warehouseId,
      String status,
      List<EquipmentContent> contents) {}

  record OperationLease(
      UUID leaseId,
      long version,
      UUID rentalItemId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt) {}

  enum LogisticsOwnerType {
    LOGISTICS_RETURN,
    LOGISTICS_SHIPMENT,
    LOGISTICS_TRANSFER
  }

  enum AssetEffect {
    SHIPMENT_CONFIRM,
    TRANSFER_DEPART,
    TRANSFER_ARRIVE
  }

  record TransferRepairDeparture(
      UUID activeRepairId, Long activeRepairVersion, String assetStatus) {}

  record TransferRepairArrivalPreflight(
      UUID activeRepairId,
      boolean priorityRequired,
      boolean movementToShipmentAvailable,
      List<UUID> missingQueueDefinitionIds) {}

  record TransferRepairArrivalCompletion(
      UUID activeRepairId, Long repairVersion, UUID warehouseId) {}

  record MediaReference(UUID mediaId, long generation) {}

  record MediaValidation(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references) {}

  record MediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {}

  record EquipmentShortage(UUID equipmentId, long missingQuantity) {}

  record ReturnShortageSource(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID estimateId,
      List<EquipmentShortage> shortages,
      String snapshotSha256,
      OffsetDateTime receivedAt) {}

  record ReturnEquipmentReceiptLine(
      UUID receiptId,
      UUID equipmentId,
      long quantity,
      UUID stockBalanceId,
      long stockBalanceVersion,
      long stockQuantity) {}

  record ReturnEquipmentReceipt(
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLine> lines) {}

  enum EquipmentHoldAction {
    COMMIT,
    RELEASE
  }

  record EquipmentHold(
      UUID holdId, long version, String state, OffsetDateTime expiresAt, OffsetDateTime committedAt) {}

  record EquipmentMovementReservation(
      UUID reservationId,
      long version,
      String ownerType,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long quantity,
      String state,
      OffsetDateTime reservedUntil,
      OffsetDateTime executedAt) {}

  record EquipmentMovementExecutionRequestLine(
      UUID reservationId,
      long expectedReservationVersion,
      UUID lineId,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind) {}

  record EquipmentMovementEvent(
      UUID id,
      long version,
      UUID equipmentId,
      UUID sourceBalanceId,
      UUID targetBalanceId,
      long quantity,
      String kind,
      OffsetDateTime occurredAt) {}

  record EquipmentMovementExecutionLine(
      UUID reservationId,
      long reservationVersion,
      UUID lineId,
      EquipmentMovementEvent movement) {}

  record EquipmentMovementExecution(UUID movementId, List<EquipmentMovementExecutionLine> lines) {}

  record EquipmentMovementOperation(
      String direction, UUID equipmentId, String equipmentName, long quantity) {}

  record EquipmentMovementBoardTask(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String status,
      OffsetDateTime doneAt) {}

  record OrderEquipmentContent(
      UUID equipmentId,
      String equipmentName,
      long quantity,
      String locationKind) {}

  record OrderRentalItem(
      UUID id,
      long version,
      UUID warehouseId,
      String number,
      String status,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      List<String> tags,
      List<OrderEquipmentContent> contents,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  record OrderUnitReservation(
      UUID reservationId,
      long reservationVersion,
      UUID orderId,
      UUID unitId,
      UUID warehouseId,
      String state,
      UUID addedBySubjectId,
      String addedByRole,
      OffsetDateTime createdAt,
      OffsetDateTime releasedAt,
      boolean replayed,
      OrderRentalItem unit) {}

  record OrderUnitCandidate(
      UUID reservationId, boolean added, OrderRentalItem unit) {}

  record OrderUnitCandidatePage(
      List<OrderUnitCandidate> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  record OrderEquipmentRequirement(UUID equipmentId, long quantity) {}

  record OrderEquipmentReservation(
      UUID equipmentId,
      String equipmentName,
      long quantity,
      long availableQuantity) {}

  record OrderFurnitureMovementPlanLine(
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      long quantity) {}

  record OrderFurnitureMovementPlan(
      UUID orderId,
      UUID unitId,
      String unitNumber,
      List<OrderFurnitureMovementPlanLine> lines) {}

  record CabinFurnitureRequirement(UUID equipmentId, long quantity) {}

  record CabinFurnitureMovementPlanLine(
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      long quantity) {}

  record CabinFurnitureMovementPlan(
      UUID rentalItemId, String unitNumber, List<CabinFurnitureMovementPlanLine> lines) {}

  record CabinFacets(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories) {}

  record CabinSearchGroup(
      String cabinType,
      String finish,
      String dimensions,
      String category,
      String characteristics,
      Boolean linoleum,
      int quantity) {}

  record AvailableCabin(
      UUID id,
      long version,
      UUID warehouseId,
      String status,
      String number,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      Map<String, Object> passport,
      List<String> tags,
      OffsetDateTime updatedAt) {}

  record CabinSearchGroupResult(
      CabinSearchGroup group, List<AvailableCabin> cabins) {}

  record CabinSearchResult(
      UUID warehouseId, OffsetDateTime expiresAt, List<CabinSearchGroupResult> groups) {}

  record CabinAvailabilityItem(UUID rentalItemId, boolean available, String reason) {}

  record CabinAvailability(
      UUID warehouseId, List<CabinAvailabilityItem> items) {}

  record PresentationHold(
      UUID holdId,
      long version,
      UUID presentationId,
      UUID rentalItemId,
      UUID warehouseId,
      String state,
      OffsetDateTime expiresAt,
      UUID orderId,
      OffsetDateTime createdAt,
      OffsetDateTime endedAt) {}

  record PresentationHolds(
      UUID presentationId, OffsetDateTime expiresAt, List<PresentationHold> holds) {}

  record ConvertedPresentationHolds(
      UUID presentationId,
      UUID orderId,
      List<OrderUnitReservation> reservations,
      List<UUID> releasedRentalItemIds) {}

  record CabinMediaPhoto(
      UUID mediaId, long generation, int sortOrder, List<String> availableVariants) {}

  record CabinMediaSnapshot(UUID cabinId, List<CabinMediaPhoto> photos) {}

  record MediaContent(byte[] bytes, String contentType) {}

  private static LogisticsDependencyException unavailable(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

}
