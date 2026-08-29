package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyFailures.unavailable;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.AssetEffect;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentHold;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentHoldAction;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentMovementExecution;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentMovementPurpose;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.EquipmentMovementReservation;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.LogisticsOwnerType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OperationLease;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OrderUnitEquipmentRequirements;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.RentalItemSnapshot;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.ReturnEquipmentReceipt;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.ReturnEquipmentReceiptLine;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.TransferUnitReservationReceipt;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.TransferUnitReservationReleaseLine;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.TransferUnitReservationRequestLine;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Asset-service-owned fenced rental-item, equipment and transfer-reservation command port.
 */
interface LogisticsAssetOperationsDependencyPort {
  RentalItemSnapshot readRentalItemSnapshot(UUID assetId);

  /** Reads only the current asset fence and display fields allowed in a public photo snapshot. */
  CabinPhotoPresentationAssetSnapshot readCabinPhotoPresentationSnapshot(UUID assetId);

  OperationLease acquireReturnLease(
      UUID idempotencyKey, UUID assetId, long expectedAssetVersion, UUID documentId, UUID lineId);

  default OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return acquireReturnLease(idempotencyKey, assetId, expectedAssetVersion, documentId, lineId);
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
        idempotencyKey, ownerType, assetId, expectedAssetVersion, documentId, lineId);
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
      boolean estimate);

  default ReturnEquipmentReceipt receiveReturnEquipment(
      UUID idempotencyKey,
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLine> lines) {
    throw unavailable("Return equipment receipts are not configured");
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

  OperationLease releaseOperationLease(
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId);

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
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose);

  /**
   * Acquires one movement line with optional authoritative same-order redistribution context.
   * Legacy stock and free-cabin sources use the context-free overload.
   */
  default EquipmentMovementReservation acquireEquipmentMovementReservation(
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
    if (orderId == null && targetRentalItemId == null && units == null) {
      return acquireEquipmentMovementReservation(
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
    throw unavailable("Order-context equipment movement reservation is not configured");
  }

  /**
   * Replays a replacement-owned movement reservation pre-held by the atomic unit swap. The released
   * source reservation identifies the intentionally no-longer-active old order cabin; ordinary
   * same-order redistribution keeps using the overload without that identity.
   */
  default EquipmentMovementReservation acquireEquipmentMovementReservation(
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
    if (replacementSourceReservationId == null) {
      return acquireEquipmentMovementReservation(
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
    throw unavailable("Replacement equipment movement reservation replay is not configured");
  }

  EquipmentMovementReservation releaseEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID reservationId,
      long expectedReservationVersion,
      UUID movementId,
      UUID lineId);

  EquipmentMovementExecution executeEquipmentMovement(
      UUID idempotencyKey, UUID movementId, List<EquipmentMovementExecutionRequestLine> lines);

  /** Atomically reserves every selected cabin of one interwarehouse transfer. */
  default TransferUnitReservationReceipt reserveTransferUnits(
      UUID idempotencyKey,
      UUID transferId,
      UUID sourceWarehouseId,
      List<TransferUnitReservationRequestLine> lines) {
    throw unavailable("Transfer cabin reservation is not configured");
  }

  /** Releases the exact still-active transfer cabin reservation batch before departure. */
  default TransferUnitReservationReceipt releaseTransferUnits(
      UUID idempotencyKey,
      UUID transferId,
      List<TransferUnitReservationReleaseLine> lines) {
    throw unavailable("Transfer cabin reservation release is not configured");
  }
}
