package dev.buhanzaz.rwms.logistics.inventory.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Canonical, deterministically ordered command passed from transport to the logistics owner. */
record InventoryOutcomeCommand(
    UUID inventoryId,
    UUID warehouseId,
    OffsetDateTime inventoryCompletedAt,
    long finalPlanVersion,
    String finalPlanSha256,
    List<AssetOutcome> outcomes) {
  InventoryOutcomeCommand {
    outcomes = List.copyOf(outcomes);
  }

  /** Exact finding-to-asset resolution frozen by inventory-service. */
  record AssetOutcome(UUID findingId, UUID assetId, String desiredStatus) {}
}
