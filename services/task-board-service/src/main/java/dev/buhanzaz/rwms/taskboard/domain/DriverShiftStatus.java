package dev.buhanzaz.rwms.taskboard.domain;

/** Authoritative lifecycle state of one driver's warehouse-local work date. */
public enum DriverShiftStatus {
  DAILY_BRIEFING_REQUIRED,
  MEDICAL_CHECK_REQUIRED,
  VEHICLE_INSPECTION_REQUIRED,
  READY_TO_START,
  SHIFT_ACTIVE,
  SHIFT_CLOSING,
  RETURN_TO_WAREHOUSE_REQUIRED,
  END_VEHICLE_CHECK_REQUIRED,
  SHIFT_READY_TO_CLOSE,
  SHIFT_CLOSED
}
