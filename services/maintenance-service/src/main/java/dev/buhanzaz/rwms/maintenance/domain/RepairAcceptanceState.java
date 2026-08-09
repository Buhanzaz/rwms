package dev.buhanzaz.rwms.maintenance.domain;

/** Enumerates RepairAcceptanceState values used by maintenance-owned domain state. */
public enum RepairAcceptanceState {
  NOT_READY,
  PENDING,
  IN_REWORK,
  ACCEPTED,
  WRITTEN_OFF
}
