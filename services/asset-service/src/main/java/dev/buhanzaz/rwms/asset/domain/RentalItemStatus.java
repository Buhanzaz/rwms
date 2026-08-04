package dev.buhanzaz.rwms.asset.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Canonical target lifecycle vocabulary. The enum intentionally preserves every
 * status currently rendered by the panel; future workflow owners must not add
 * semantic transitions by reusing a browser-only value.
 */
public enum RentalItemStatus {
  RENTED,
  BOOKED,
  REPAIR,
  WAITING_REPAIR_CHECK,
  WRITTEN_OFF,
  LOST,
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

  private static final Set<RentalItemStatus> MANUAL_STATUSES =
      EnumSet.of(SALE, USED_SALE, FREE, WAREHOUSE, OWN_NEEDS);

  public boolean acceptsManualStatusChangeTo(RentalItemStatus next) {
    return next != null && MANUAL_STATUSES.contains(this) && MANUAL_STATUSES.contains(next);
  }

  public boolean isTerminalDispositionStatus() {
    return this == WRITTEN_OFF || this == LOST;
  }
}
