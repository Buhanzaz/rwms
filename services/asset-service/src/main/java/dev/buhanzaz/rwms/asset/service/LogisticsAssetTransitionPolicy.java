package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction;
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
      case TRANSFER_DEPART -> require(ownerType, LogisticsLeaseOwnerType.LOGISTICS_TRANSFER,
          source, RentalItemStatus.FREE, RentalItemStatus.IN_TRANSFER);
      case TRANSFER_ARRIVE -> require(ownerType, LogisticsLeaseOwnerType.LOGISTICS_TRANSFER,
          source, RentalItemStatus.IN_TRANSFER, RentalItemStatus.FREE);
    };
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
