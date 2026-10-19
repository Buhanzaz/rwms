package dev.buhanzaz.rwms.logistics.inventory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/** Stable fingerprint for the complete canonical completed-inventory request. */
final class InventoryOutcomeChecksum {
  private InventoryOutcomeChecksum() {}

  static String sha256(InventoryOutcomeCommand command) {
    StringBuilder canonical = new StringBuilder();
    append(canonical, command.inventoryId().toString());
    append(canonical, command.warehouseId().toString());
    append(canonical, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(command.inventoryCompletedAt()));
    append(canonical, Long.toString(command.finalPlanVersion()));
    append(canonical, command.finalPlanSha256());
    for (InventoryOutcomeCommand.AssetOutcome outcome : command.outcomes()) {
      append(canonical, outcome.findingId().toString());
      append(canonical, outcome.assetId().toString());
      append(canonical, outcome.dispositionKind());
      append(canonical, outcome.desiredStatus());
      if (outcome.formerRental() == null) {
        append(canonical, "-");
      } else {
        append(canonical, outcome.formerRental().returnedOn().toString());
        append(canonical, outcome.formerRental().clientId().toString());
        append(canonical, outcome.formerRental().clientSnapshot());
      }
      if (outcome.shipment() == null) {
        append(canonical, "-");
      } else {
        append(canonical, outcome.shipment().departedOn().toString());
        append(canonical, outcome.shipment().clientId().toString());
        append(canonical, outcome.shipment().clientSnapshot());
        for (InventoryOutcomeCommand.ShipmentFurniture furniture : outcome.shipment().furniture()) {
          append(canonical, furniture.equipmentId().toString());
          append(canonical, Long.toString(furniture.catalogVersion()));
          append(canonical, Long.toString(furniture.quantity()));
        }
      }
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void append(StringBuilder target, String value) {
    target.append(value.length()).append(':').append(value);
  }
}
