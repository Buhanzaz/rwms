package dev.buhanzaz.rwms.inventory.domain;

/**
 * Enumerates permitted publication state values in the inventory persistent workflow state.
 */
public enum PublicationState {
  NOT_REQUIRED,
  READY,
  PENDING,
  SUCCEEDED,
  TRANSIENT_FAILED,
  BLOCKED,
  CLOSED_BLOCKED
}
