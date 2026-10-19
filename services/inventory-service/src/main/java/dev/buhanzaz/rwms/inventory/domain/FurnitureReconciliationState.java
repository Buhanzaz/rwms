package dev.buhanzaz.rwms.inventory.domain;

/**
 * Public state of the inventory furniture reconciliation.
 *
 * <p>{@link #NOT_REQUIRED} and {@link #READY} describe an active session before completion. The
 * remaining states are the durable server-side delivery result after completion.
 */
public enum FurnitureReconciliationState {
  NOT_REQUIRED,
  READY,
  PENDING,
  SUCCEEDED,
  TRANSIENT_FAILED,
  BLOCKED
}
