package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Resolves the explicit furniture-accounting admission rule from canonical cabin contents. */
@Service
final class MaintenanceEstimateFurnitureSupport {
  private final MaintenanceDependencyGateway dependencies;

  MaintenanceEstimateFurnitureSupport(MaintenanceDependencyGateway dependencies) {
    this.dependencies = dependencies;
  }

  FurnitureAccountingMode resolve(
      UUID rentalItemId,
      UUID warehouseId,
      boolean hasFurniture,
      boolean allowsUnaccountedFurniture) {
    if (!hasFurniture) {
      return FurnitureAccountingMode.TRACKED_CABIN_CONTENTS;
    }
    MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot =
        dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN, rentalItemId, warehouseId);
    boolean noRecordedContents = MaintenanceReconciliationSupport.hasNoRecordedCabinContents(
        rentalItemId, warehouseId, snapshot);
    if (noRecordedContents) {
      if (!allowsUnaccountedFurniture) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED",
            "Cabin has no recorded contents. Confirm completion without warehouse additional-equipment accounting.");
      }
      return FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS;
    }
    if (allowsUnaccountedFurniture) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Unaccounted furniture confirmation is allowed only when the cabin has no recorded contents");
    }
    return FurnitureAccountingMode.TRACKED_CABIN_CONTENTS;
  }
}
