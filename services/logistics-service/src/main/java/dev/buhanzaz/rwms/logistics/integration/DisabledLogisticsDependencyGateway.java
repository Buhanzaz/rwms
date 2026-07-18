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
  public ReturnShortageSource upsertReturnShortage(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
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
  public PreparationTask registerPreparationTask(
      UUID warehouseId, UUID externalTaskId, Integer plannedDurationMinutes, java.time.OffsetDateTime deadlineAt) {
    throw unavailable();
  }

  @Override
  public PreparationTask readPreparationTask(UUID externalTaskId) {
    throw unavailable();
  }

  @Override
  public PreparationTask cancelPreparationTask(UUID externalTaskId, long expectedTaskVersion) {
    throw unavailable();
  }

  private static LogisticsDependencyException unavailable() {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, MESSAGE);
  }
}
