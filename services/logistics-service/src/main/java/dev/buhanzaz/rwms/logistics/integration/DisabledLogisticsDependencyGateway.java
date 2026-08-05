package dev.buhanzaz.rwms.logistics.integration;

import java.util.UUID;

/** Fail-closed development default until exact service credentials are configured. */
final class DisabledLogisticsDependencyGateway implements LogisticsDependencyGateway {
  private static final String MESSAGE = "Logistics private dependencies are not configured";

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
  public WarehouseTimeZone warehouseTimeZoneAt(
      UUID warehouseId, java.time.OffsetDateTime at) {
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
  public RentalItemSnapshot readRentalItemSnapshot(UUID assetId) {
    throw unavailable();
  }

  @Override
  public OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
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
      boolean shortage) {
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
  public MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {
    throw unavailable();
  }

  @Override
  public ReturnShortageSource upsertReturnShortage(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      java.time.LocalDate dispatchDate,
      java.util.List<MediaReference> mediaReferences,
      java.util.List<EquipmentShortage> shortages) {
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
      UUID idempotencyKey,
      UUID orderId,
      UUID unitId,
      UUID actorSubjectId,
      String actorRole) {
    throw unavailable();
  }

  @Override
  public java.util.List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole) {
    throw unavailable();
  }

  @Override
  public java.util.List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      java.util.List<OrderEquipmentRequirement> requirements) {
    throw unavailable();
  }

  @Override
  public OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      java.util.List<OrderEquipmentRequirement> unitRequirements,
      java.util.List<OrderEquipmentRequirement> orderRequirements) {
    throw unavailable();
  }

  @Override
  public CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId,
      UUID rentalItemId,
      java.util.List<CabinFurnitureRequirement> requirements) {
    throw unavailable();
  }

  private static LogisticsDependencyException unavailable() {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, MESSAGE);
  }
}
