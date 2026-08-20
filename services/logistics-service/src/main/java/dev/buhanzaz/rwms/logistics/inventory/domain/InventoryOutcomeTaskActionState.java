package dev.buhanzaz.rwms.logistics.inventory.domain;

/** Durable result of one logistics task or asset-lease supersession action. */
public enum InventoryOutcomeTaskActionState {
  PENDING,
  CANCELLED,
  PRESERVED,
  DURABLE_CANCELLING,
  RELEASED
}
