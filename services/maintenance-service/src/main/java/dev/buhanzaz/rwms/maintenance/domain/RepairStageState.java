package dev.buhanzaz.rwms.maintenance.domain;

/** Enumerates RepairStageState values used by maintenance-owned domain state. */
public enum RepairStageState {
  PLANNED,
  QUEUED,
  IN_PROGRESS,
  DONE,
  CANCELLED
}
