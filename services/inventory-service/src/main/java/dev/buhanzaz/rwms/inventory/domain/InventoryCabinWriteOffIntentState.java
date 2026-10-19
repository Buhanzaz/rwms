package dev.buhanzaz.rwms.inventory.domain;

/** Durable delivery state of one maintenance-owned cabin write-off decision. */
public enum InventoryCabinWriteOffIntentState {
  PENDING,
  SUCCEEDED,
  TRANSIENT_FAILED,
  BLOCKED
}
