package dev.buhanzaz.rwms.asset.domain;

/**
 * Canonical target lifecycle vocabulary. The enum intentionally preserves every
 * status currently rendered by the panel; future workflow owners must not add
 * semantic transitions by reusing a browser-only value.
 */
public enum RentalItemStatus {
  NEW,
  RENTED,
  BOOKED,
  REPAIR,
  WAITING_REPAIR_CHECK,
  WRITTEN_OFF,
  CAPITAL_REPAIR,
  AFTER_RENT,
  WAITING_ESTIMATE_CONFIRMATION,
  SALE,
  USED_SALE,
  RESERVED,
  FREE,
  WAREHOUSE,
  OWN_NEEDS,
  IN_TRANSFER;

  public boolean acceptsManualStatusChangeTo(RentalItemStatus next) {
    if (next == null || this == WRITTEN_OFF) return false;
    // IN_TRANSFER is owned by the future logistics workflow, not by this public
    // operator endpoint. Cabin write-off has its own fenced command.
    return next != IN_TRANSFER && next != WRITTEN_OFF;
  }
}
