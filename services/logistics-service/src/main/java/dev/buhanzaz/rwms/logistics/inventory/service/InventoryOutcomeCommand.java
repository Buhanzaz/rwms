package dev.buhanzaz.rwms.logistics.inventory.service;

import java.time.LocalDate;
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
  record AssetOutcome(
      UUID findingId,
      UUID assetId,
      String dispositionKind,
      String desiredStatus,
      FormerRental formerRental,
      Shipment shipment) {}

  /** Canonical former-rental data used to create a historical return. */
  record FormerRental(LocalDate returnedOn, UUID clientId, String clientSnapshot) {}

  /** Canonical historical shipment data used to create a completed shipment fact. */
  record Shipment(
      LocalDate departedOn,
      UUID clientId,
      String clientSnapshot,
      List<ShipmentFurniture> furniture) {
    Shipment {
      furniture = List.copyOf(furniture);
    }
  }

  /** Canonical furniture quantity carried by an inventory-created shipment. */
  record ShipmentFurniture(UUID equipmentId, long catalogVersion, long quantity) {}
}
