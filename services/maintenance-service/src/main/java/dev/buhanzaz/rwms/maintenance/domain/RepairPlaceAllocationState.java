package dev.buhanzaz.rwms.maintenance.domain;

/** Enumerates RepairPlaceAllocationState values used by maintenance-owned domain state. */
public enum RepairPlaceAllocationState {
  RESERVED,
  OCCUPIED,
  READY_TO_RELEASE,
  RELEASED
}
