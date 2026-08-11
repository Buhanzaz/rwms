package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceStatusAction;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Exact Stage 6 source/action allowlist. The maintenance HTTP contract never accepts a target status. */
final class MaintenanceAssetTransitionPolicy {
  private static final Set<RentalItemStatus> MAINTENANCE_REPAIR_QUEUE_SOURCES =
      Set.copyOf(EnumSet.complementOf(EnumSet.of(
          RentalItemStatus.RENTED,
          RentalItemStatus.IN_TRANSFER,
          RentalItemStatus.WRITTEN_OFF,
          RentalItemStatus.LOST)));
  private static final Set<RentalItemStatus> ESTIMATE_REPAIR_QUEUE_SOURCES = Set.of(
      RentalItemStatus.FREE,
      RentalItemStatus.BOOKED,
      RentalItemStatus.WAREHOUSE,
      RentalItemStatus.OWN_NEEDS,
      RentalItemStatus.AFTER_RENT,
      RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION,
      RentalItemStatus.REPAIR,
      RentalItemStatus.CAPITAL_REPAIR,
      RentalItemStatus.USED_SALE);
  private static final Set<RentalItemStatus> EMPTY_ESTIMATE_SOURCES = Set.of(
      RentalItemStatus.FREE,
      RentalItemStatus.WAREHOUSE,
      RentalItemStatus.OWN_NEEDS,
      RentalItemStatus.AFTER_RENT,
      RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION);
  private static final Set<RentalItemStatus> EMPTY_REPAIR_SOURCES = Set.of(
      RentalItemStatus.FREE,
      RentalItemStatus.WAREHOUSE,
      RentalItemStatus.OWN_NEEDS,
      RentalItemStatus.AFTER_RENT);
  private static final Set<RentalItemStatus> WRITE_OFF_SOURCES = Set.of(
      RentalItemStatus.FREE,
      RentalItemStatus.BOOKED,
      RentalItemStatus.WAREHOUSE,
      RentalItemStatus.OWN_NEEDS,
      RentalItemStatus.AFTER_RENT,
      RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION,
      RentalItemStatus.REPAIR,
      RentalItemStatus.WAITING_REPAIR_CHECK);

  private MaintenanceAssetTransitionPolicy() {}

  static RentalItemStatus target(
      RentalItemStatus source,
      MaintenanceStatusAction action,
      MaintenanceLeaseOwnerType ownerType,
      UUID ownerId,
      UUID linkedReturnEstimateId) {
    if (source == null || action == null || ownerType == null || ownerId == null) {
      throw new AssetConflictException("Maintenance status transition is incomplete");
    }
    if (source == RentalItemStatus.IN_TRANSFER || source.isTerminalDispositionStatus()) {
      throw new AssetConflictException(
          "Maintenance action is not allowed from rental-item status " + source);
    }
    if (source == RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION
        && ownerType == MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE
        && !linkedReturnProof(ownerType, ownerId, linkedReturnEstimateId)) {
      throw new AssetConflictException(
          "WAITING_ESTIMATE_CONFIRMATION requires the owning linked-return estimate proof");
    }
    if (source != RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION && linkedReturnEstimateId != null) {
      throw new AssetConflictException(
          "Linked-return estimate proof is valid only from WAITING_ESTIMATE_CONFIRMATION");
    }
    return switch (action) {
      case QUEUE_FOR_REPAIR -> requireSource(
          source,
          ownerType == MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR
              ? MAINTENANCE_REPAIR_QUEUE_SOURCES
              : ESTIMATE_REPAIR_QUEUE_SOURCES,
          RentalItemStatus.REPAIR);
      case QUEUE_FOR_CAPITAL_REPAIR -> requireSource(
          source,
          ownerType == MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR
              ? MAINTENANCE_REPAIR_QUEUE_SOURCES
              : ESTIMATE_REPAIR_QUEUE_SOURCES,
          RentalItemStatus.CAPITAL_REPAIR);
      case COMPLETE_EMPTY_ESTIMATE -> {
        if (ownerType != MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE) {
          throw new AssetConflictException("Only an estimate-owned lease may complete an empty estimate");
        }
        yield requireSource(source, EMPTY_ESTIMATE_SOURCES, RentalItemStatus.FREE);
      }
      case COMPLETE_EMPTY_REPAIR -> {
        if (ownerType != MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR) {
          throw new AssetConflictException("Only a repair-owned lease may complete an empty repair");
        }
        yield requireSource(source, EMPTY_REPAIR_SOURCES, RentalItemStatus.FREE);
      }
      case MARK_PENDING_ACCEPTANCE -> requireSource(
          source,
          Set.of(RentalItemStatus.REPAIR, RentalItemStatus.CAPITAL_REPAIR),
          RentalItemStatus.WAITING_REPAIR_CHECK);
      case ACCEPT_REPAIR -> requireExact(
          source, RentalItemStatus.WAITING_REPAIR_CHECK, RentalItemStatus.FREE);
      case WRITE_OFF -> requireSource(source, WRITE_OFF_SOURCES, RentalItemStatus.WRITTEN_OFF);
    };
  }

  private static boolean linkedReturnProof(
      MaintenanceLeaseOwnerType ownerType, UUID ownerId, UUID linkedReturnEstimateId) {
    return ownerType == MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE
        && ownerId.equals(linkedReturnEstimateId);
  }

  private static RentalItemStatus requireSource(
      RentalItemStatus source, Set<RentalItemStatus> allowed, RentalItemStatus target) {
    if (!allowed.contains(source)) {
      throw new AssetConflictException(
          "Maintenance action is not allowed from rental-item status " + source);
    }
    return target;
  }

  private static RentalItemStatus requireExact(
      RentalItemStatus source, RentalItemStatus allowed, RentalItemStatus target) {
    return requireSource(source, Set.of(allowed), target);
  }
}
