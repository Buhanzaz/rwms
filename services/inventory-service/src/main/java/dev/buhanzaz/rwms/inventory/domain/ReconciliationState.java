package dev.buhanzaz.rwms.inventory.domain;

/**
 * Enumerates permitted reconciliation state values in the inventory persistent workflow state.
 */
public enum ReconciliationState {
  MATCHED,
  MISSING,
  CONFLICT
}
