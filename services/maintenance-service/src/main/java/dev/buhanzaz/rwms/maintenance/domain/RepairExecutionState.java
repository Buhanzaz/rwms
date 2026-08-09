package dev.buhanzaz.rwms.maintenance.domain;

/** Enumerates RepairExecutionState values used by maintenance-owned domain state. */
public enum RepairExecutionState {
  DRAFT,
  QUEUED,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED
}
