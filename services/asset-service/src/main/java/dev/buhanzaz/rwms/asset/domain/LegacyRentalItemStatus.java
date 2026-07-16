package dev.buhanzaz.rwms.asset.domain;

import java.util.Map;

/** Explicit migration mapping. Unknown legacy values must remain unmapped. */
public final class LegacyRentalItemStatus {
  private static final Map<String, RentalItemStatus> TARGETS =
      Map.of(
          "READY", RentalItemStatus.FREE,
          "TEMP_RESERVED", RentalItemStatus.BOOKED,
          "RESERVED", RentalItemStatus.RESERVED,
          "IN_RENT", RentalItemStatus.RENTED,
          "NEED_INSPECTION", RentalItemStatus.AFTER_RENT,
          "WAITING_REPAIR", RentalItemStatus.REPAIR,
          "IN_REPAIR", RentalItemStatus.REPAIR,
          "WAITING_REPAIR_CHECK", RentalItemStatus.WAITING_REPAIR_CHECK,
          "IN_CAP_REPAIR", RentalItemStatus.CAPITAL_REPAIR);

  private LegacyRentalItemStatus() {}

  public static RentalItemStatus requireTarget(String legacyValue) {
    if (legacyValue == null) throw new IllegalArgumentException("Legacy status is required");
    RentalItemStatus target = TARGETS.get(legacyValue.trim());
    if (target == null) {
      throw new IllegalArgumentException("Legacy rental-item status is not mapped: " + legacyValue);
    }
    return target;
  }

  public static Map<String, RentalItemStatus> mappings() {
    return TARGETS;
  }
}
