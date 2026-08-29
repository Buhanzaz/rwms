package dev.buhanzaz.rwms.logistics.integration;

import java.util.UUID;

/**
 * Fail-closed local/test dependency adapter. It never supplies warehouse admission or another
 * fabricated owner response, and production startup rejects this adapter through its readiness
 * signal.
 */
final class DisabledLogisticsDependencyGateway implements LogisticsDependencyGateway {
  private static final String MESSAGE = "Logistics private dependencies are disabled";

  @Override
  public WarehouseIdentity readWarehouseIdentity(UUID warehouseId) {
    throw unavailable();
  }

  @Override
  public WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    throw unavailable();
  }

  @Override
  public java.util.List<WarehouseSupportLink> listWarehouseSupportLinks(
      UUID servedWarehouseId, java.time.OffsetDateTime at) {
    throw unavailable();
  }

  @Override
  public WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(
      UUID after, int limit) {
    throw unavailable();
  }

  @Override
  public WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    throw unavailable();
  }

  @Override
  public WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, java.time.OffsetDateTime at) {
    throw unavailable();
  }

  @Override
  public void markWarehouseOperation(
      UUID warehouseId, UUID operationId, java.time.OffsetDateTime occurredAt) {
    throw unavailable();
  }

  @Override
  public boolean productionReady() {
    return false;
  }

  @Override
  public java.util.List<WarehouseDriverIdentity> listWarehouseDrivers(UUID warehouseId) {
    throw unavailable();
  }

  @Override
  public WorkerOperationalAssignment createWorkerOperationalAssignment(
      UUID transferId,
      UUID workerId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      String mode,
      java.time.OffsetDateTime travelStartsAt,
      java.time.OffsetDateTime effectiveFrom,
      java.time.OffsetDateTime effectiveUntil) {
    throw unavailable();
  }

  @Override
  public WorkerOperationalAssignment transitionWorkerOperationalAssignment(
      UUID assignmentId, long expectedVersion, String targetStatus) {
    throw unavailable();
  }

  @Override
  public RentalItemSnapshot readRentalItemSnapshot(UUID assetId) {
    throw unavailable();
  }

  @Override
  public CabinPhotoPresentationAssetSnapshot readCabinPhotoPresentationSnapshot(UUID assetId) {
    throw unavailable();
  }

  @Override
  public OperationLease acquireReturnLease(
      UUID idempotencyKey, UUID assetId, long expectedAssetVersion, UUID documentId, UUID lineId) {
    throw unavailable();
  }

  @Override
  public OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
    throw unavailable();
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
    throw unavailable();
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
    throw unavailable();
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
    throw unavailable();
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
    throw unavailable();
  }

  @Override
  public MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      java.util.List<MediaReference> references) {
    throw unavailable();
  }

  @Override
  public CustomerProfileMediaValidation validateCustomerProfileMediaReference(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      MediaReference reference) {
    throw unavailable();
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
      UUID authorizedSubjectId,
      boolean active) {
    throw unavailable();
  }

  @Override
  public CustomerProfileMediaOwnerProof upsertCustomerProfileMediaOwnerProof(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      UUID proofEventId) {
    throw unavailable();
  }

  @Override
  public ReturnEstimateSource upsertReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      java.time.LocalDate dispatchDate,
      java.time.OffsetDateTime arrivedAt,
      java.util.List<MediaReference> mediaReferences) {
    throw unavailable();
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
    throw unavailable();
  }

  @Override
  public EquipmentHold commandEquipmentHold(
      UUID idempotencyKey,
      EquipmentHoldAction action,
      UUID holdId,
      long expectedHoldVersion,
      UUID shipmentId,
      UUID shipmentLineId) {
    throw unavailable();
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
      java.time.OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose) {
    throw unavailable();
  }

  @Override
  public EquipmentMovementReservation releaseEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID reservationId,
      long expectedReservationVersion,
      UUID movementId,
      UUID lineId) {
    throw unavailable();
  }

  @Override
  public EquipmentMovementExecution executeEquipmentMovement(
      UUID idempotencyKey,
      UUID movementId,
      java.util.List<EquipmentMovementExecutionRequestLine> lines) {
    throw unavailable();
  }

  @Override
  public TransferUnitReservationReceipt reserveTransferUnits(
      UUID idempotencyKey,
      UUID transferId,
      UUID sourceWarehouseId,
      java.util.List<TransferUnitReservationRequestLine> lines) {
    throw unavailable();
  }

  @Override
  public TransferUnitReservationReceipt releaseTransferUnits(
      UUID idempotencyKey,
      UUID transferId,
      java.util.List<TransferUnitReservationReleaseLine> lines) {
    throw unavailable();
  }

  @Override
  public EquipmentMovementBoardTask registerEquipmentMovementTask(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      java.time.OffsetDateTime deadlineAt,
      java.util.List<EquipmentMovementOperation> operations) {
    throw unavailable();
  }

  @Override
  public EquipmentMovementBoardTask readEquipmentMovementTask(UUID externalTaskId) {
    throw unavailable();
  }

  @Override
  public EquipmentMovementBoardTask cancelEquipmentMovementTask(
      UUID externalTaskId, long expectedTaskVersion) {
    throw unavailable();
  }

  @Override
  public OrderUnitCandidatePage readOrderUnitCandidates(
      UUID orderId, UUID warehouseId, int page, int size, String search) {
    throw unavailable();
  }

  @Override
  public java.util.List<OrderUnitReservation> readOrderUnits(UUID orderId) {
    throw unavailable();
  }

  @Override
  public OrderUnitReservation reserveOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID clientId,
      String tenantSnapshot,
      java.time.OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole) {
    throw unavailable();
  }

  @Override
  public OrderUnitReservation releaseOrderUnit(
      UUID idempotencyKey, UUID orderId, UUID unitId, UUID actorSubjectId, String actorRole) {
    throw unavailable();
  }

  @Override
  public java.util.List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole) {
    throw unavailable();
  }

  @Override
  public java.util.List<EquipmentWarehouseAvailability> readLogisticsEquipmentAvailability(
      UUID warehouseId) {
    throw unavailable();
  }

  @Override
  public java.util.List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      java.util.List<OrderUnitEquipmentRequirements> units) {
    throw unavailable();
  }

  @Override
  public OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID replacementForRentalItemId,
      java.util.List<OrderEquipmentRequirement> unitRequirements,
      java.util.List<OrderUnitEquipmentRequirements> units) {
    throw unavailable();
  }

  @Override
  public OrderUnitsReplacementReceipt replaceOrderUnits(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID inventorySourceWarehouseId,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole,
      java.util.List<OrderUnitEquipmentRequirements> units,
      java.util.List<OrderUnitReplacement> replacements) {
    throw unavailable();
  }

  @Override
  public CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId, UUID rentalItemId, java.util.List<CabinFurnitureRequirement> requirements) {
    throw unavailable();
  }

  private static LogisticsDependencyException unavailable() {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, MESSAGE);
  }
}
