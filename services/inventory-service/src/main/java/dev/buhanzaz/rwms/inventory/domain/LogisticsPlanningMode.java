package dev.buhanzaz.rwms.inventory.domain;

import java.time.LocalDate;

/**
 * Enumerates permitted logistics planning mode values in the inventory persistent workflow state.
 */
public enum LogisticsPlanningMode {
  AUTO,
  FIXED_DATE;

  public static boolean validInboundPlanning(
      boolean movementToRepair, LogisticsPlanningMode mode, LocalDate scheduledDate) {
    if (!movementToRepair) {
      return mode == null && scheduledDate == null;
    }
    return mode != null
        && ((mode == AUTO && scheduledDate == null)
            || (mode == FIXED_DATE && scheduledDate != null));
  }
}
