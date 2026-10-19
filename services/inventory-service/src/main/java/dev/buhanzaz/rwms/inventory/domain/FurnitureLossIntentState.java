package dev.buhanzaz.rwms.inventory.domain;

/** Durable delivery state of an inventory furniture-shortage proposal. */
public enum FurnitureLossIntentState {
  PENDING,
  SUCCEEDED,
  TRANSIENT_FAILED,
  BLOCKED
}
