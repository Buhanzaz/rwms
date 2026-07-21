package dev.buhanzaz.rwms.logistics.equipment.domain;

/** Durable lifecycle of a worker-mediated equipment movement. */
public enum EquipmentMovementTaskState {
  RESERVING,
  REGISTERING_TASK,
  AWAITING_WORKER,
  EXECUTING,
  CANCELLING,
  COMPLETED,
  CANCELLED,
  EXPIRED,
  CONFLICT,
  RECONCILIATION_REQUIRED;

  public boolean isTerminal() {
    return this == COMPLETED
        || this == CANCELLED
        || this == EXPIRED
        || this == CONFLICT
        || this == RECONCILIATION_REQUIRED;
  }
}
