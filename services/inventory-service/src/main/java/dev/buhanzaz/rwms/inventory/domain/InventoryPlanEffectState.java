package dev.buhanzaz.rwms.inventory.domain;

/** Durable delivery state of a plan-wide completed-inventory owner effect. */
public enum InventoryPlanEffectState {
  READY,
  PENDING,
  SUCCEEDED,
  TRANSIENT_FAILED,
  BLOCKED
}
