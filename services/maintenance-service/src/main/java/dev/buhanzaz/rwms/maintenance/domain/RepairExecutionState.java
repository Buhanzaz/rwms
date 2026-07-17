package dev.buhanzaz.rwms.maintenance.domain;

public enum RepairExecutionState {
  DRAFT,
  QUEUED,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED
}
