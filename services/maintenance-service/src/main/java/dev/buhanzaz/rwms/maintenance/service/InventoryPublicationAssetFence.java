package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationApplyRequest;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationFindingInput;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryNoWorkOutcomeRequest;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import java.util.Set;
import java.util.UUID;

/**
 * Validates the service-local asset projection against immutable inventory evidence and the
 * effective version returned by the owning asset command.
 */
final class InventoryPublicationAssetFence {
  private static final Set<String> TERMINAL_STATUSES = Set.of("LOST", "WRITTEN_OFF");

  private InventoryPublicationAssetFence() {}

  /**
   * Requires the frozen warehouse and rejects a projection older than the completed observation.
   * Newer non-terminal versions are expected during history replay and are fenced by the later
   * authoritative asset command.
   */
  static void requireFrozen(
      UUID warehouseId, InventoryPublicationFindingInput finding, RentalItemFactProjection asset) {
    if (!warehouseId.equals(asset.getWarehouseId())
        || finding.assetVersion() == null
        || finding.assetVersion() > asset.getAggregateVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Current rental-item warehouse/version predates completed inventory evidence");
    }
    requireNonTerminal(asset);
  }

  /** Fences a no-work cleanup against the effective version returned by asset-service. */
  static void requireAuthoritativeNoWork(
      InventoryNoWorkOutcomeRequest request, RentalItemFactProjection asset) {
    if (request == null
        || !request.warehouseId().equals(asset.getWarehouseId())
        || asset.getAggregateVersion() > request.authoritativeAssetVersion()) {
      throw InventoryPublicationPlanValidation.conflict(
          "Current rental-item warehouse/version is outside the completed inventory authority "
              + "fence");
    }
    requireNonTerminal(asset);
  }

  /**
   * Allows an event projection to lag the authoritative asset command, but never to predate the
   * frozen observation or advance beyond the command result supplied by inventory-service.
   */
  static void requireAuthoritative(
      InventoryPublicationApplyRequest request, RentalItemFactProjection asset) {
    Long observedVersion = request.assetVersion();
    Long authoritativeVersion = request.authoritativeAssetVersion();
    if (observedVersion == null
        || authoritativeVersion == null
        || authoritativeVersion < observedVersion) {
      throw InventoryPublicationPlanValidation.invalid(
          "Authoritative asset version must not predate completed inventory evidence");
    }
    long projectedVersion = asset.getAggregateVersion();
    if (!request.warehouseId().equals(asset.getWarehouseId())
        || projectedVersion < observedVersion
        || projectedVersion > authoritativeVersion) {
      throw InventoryPublicationPlanValidation.conflict(
          "Current rental-item warehouse/version is outside the completed inventory authority "
              + "fence");
    }
    requireNonTerminal(asset);
  }

  private static void requireNonTerminal(RentalItemFactProjection asset) {
    if (TERMINAL_STATUSES.contains(asset.getAssetStatus())) {
      throw InventoryPublicationPlanValidation.conflict(
          "Terminal rental-item status cannot be replaced by inventory publication");
    }
  }
}
