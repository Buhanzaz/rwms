package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferAssetStatus;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import java.util.Set;
import java.util.UUID;

/**
 * Closed Stage 8 canonical effects. A logistics caller supplies an action,
 * never a raw target status or a free-form owner.
 */
final class LogisticsAssetTransitionPolicy {
  private LogisticsAssetTransitionPolicy() {}

  static RentalItemStatus target(
      RentalItemStatus source,
      LogisticsRentalItemAction action,
      LogisticsLeaseOwnerType ownerType,
      UUID destinationWarehouseId) {
    return target(
        source,
        action,
        ownerType,
        destinationWarehouseId,
        null,
        null);
  }

  static RentalItemStatus target(
      RentalItemStatus source,
      LogisticsRentalItemAction action,
      LogisticsLeaseOwnerType ownerType,
      UUID destinationWarehouseId,
      TransferAssetStatus requestedTransferStatus,
      RentalItemStatus persistedTransferOriginStatus) {
    if (source == null || action == null || ownerType == null) {
      throw new AssetConflictException("Logistics canonical effect is incomplete");
    }
    if (action == LogisticsRentalItemAction.TRANSFER_ARRIVE) {
      if (destinationWarehouseId == null) {
        throw new AssetConflictException("Transfer arrival requires a destination warehouse");
      }
    } else if (destinationWarehouseId != null) {
      throw new AssetConflictException("Only transfer arrival may change the canonical warehouse");
    }
    if (action != LogisticsRentalItemAction.TRANSFER_DEPART
        && action != LogisticsRentalItemAction.TRANSFER_ARRIVE
        && requestedTransferStatus != null) {
      throw new AssetConflictException(
          "Only transfer actions may declare a transfer asset status");
    }
    return switch (action) {
      case RETURN_INTAKE -> require(ownerType, LogisticsLeaseOwnerType.LOGISTICS_RETURN,
          source, RentalItemStatus.RENTED, RentalItemStatus.AFTER_RENT);
      case RETURN_SETTLE_FREE -> require(ownerType, LogisticsLeaseOwnerType.LOGISTICS_RETURN,
          source, RentalItemStatus.AFTER_RENT, RentalItemStatus.FREE);
      case RETURN_SETTLE_SHORTAGE -> require(ownerType, LogisticsLeaseOwnerType.LOGISTICS_RETURN,
          source, RentalItemStatus.AFTER_RENT, RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION);
      case SHIPMENT_CONFIRM -> requireAny(
          ownerType,
          LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT,
          source,
          Set.of(RentalItemStatus.FREE, RentalItemStatus.BOOKED),
          RentalItemStatus.RENTED);
      case TRANSFER_DEPART ->
          transferDepart(
              ownerType,
              source,
              requestedTransferStatus,
              persistedTransferOriginStatus);
      case TRANSFER_ARRIVE ->
          transferArrive(
              ownerType,
              source,
              requestedTransferStatus,
              persistedTransferOriginStatus);
    };
  }

  private static RentalItemStatus transferDepart(
      LogisticsLeaseOwnerType ownerType,
      RentalItemStatus source,
      TransferAssetStatus requested,
      RentalItemStatus persistedOrigin) {
    requireTransferOwner(ownerType);
    if (requested == null) {
      throw new AssetConflictException("Transfer departure requires transferAssetStatus");
    }
    RentalItemStatus requestedStatus = RentalItemStatus.valueOf(requested.name());
    boolean matchingFree =
        requestedStatus == RentalItemStatus.FREE
            && source == RentalItemStatus.FREE;
    boolean matchingRepair =
        requestedStatus == RentalItemStatus.REPAIR
            && (source == RentalItemStatus.REPAIR
                || source == RentalItemStatus.CAPITAL_REPAIR);
    if (!matchingFree && !matchingRepair) {
      throw new AssetConflictException(
          "Transfer departure status must match the current rental-item status");
    }
    if (persistedOrigin != null) {
      throw new AssetConflictException("Rental item already has a transfer origin status");
    }
    return RentalItemStatus.IN_TRANSFER;
  }

  private static RentalItemStatus transferArrive(
      LogisticsLeaseOwnerType ownerType,
      RentalItemStatus source,
      TransferAssetStatus requested,
      RentalItemStatus persistedOrigin) {
    requireTransferOwner(ownerType);
    if (source != RentalItemStatus.IN_TRANSFER) {
      throw new AssetConflictException(
          "Logistics action is not allowed from rental-item status " + source);
    }
    if (requested == null) {
      throw new AssetConflictException("Transfer arrival requires transferAssetStatus");
    }
    RentalItemStatus requestedStatus = RentalItemStatus.valueOf(requested.name());
    if (persistedOrigin == null || persistedOrigin != requestedStatus) {
      throw new AssetConflictException(
          "Transfer arrival status does not match the persisted departure status");
    }
    return persistedOrigin;
  }

  private static void requireTransferOwner(LogisticsLeaseOwnerType ownerType) {
    if (ownerType != LogisticsLeaseOwnerType.LOGISTICS_TRANSFER) {
      throw new AssetConflictException(
          "Logistics action does not match the operation-lease owner");
    }
  }

  private static RentalItemStatus require(
      LogisticsLeaseOwnerType actualOwner,
      LogisticsLeaseOwnerType expectedOwner,
      RentalItemStatus source,
      RentalItemStatus expectedSource,
      RentalItemStatus target) {
    if (actualOwner != expectedOwner) {
      throw new AssetConflictException("Logistics action does not match the operation-lease owner");
    }
    if (source != expectedSource) {
      throw new AssetConflictException(
          "Logistics action is not allowed from rental-item status " + source);
    }
    return target;
  }

  private static RentalItemStatus requireAny(
      LogisticsLeaseOwnerType actualOwner,
      LogisticsLeaseOwnerType expectedOwner,
      RentalItemStatus source,
      Set<RentalItemStatus> expectedSources,
      RentalItemStatus target) {
    if (actualOwner != expectedOwner) {
      throw new AssetConflictException("Logistics action does not match the operation-lease owner");
    }
    if (!expectedSources.contains(source)) {
      throw new AssetConflictException(
          "Logistics action is not allowed from rental-item status " + source);
    }
    return target;
  }
}
