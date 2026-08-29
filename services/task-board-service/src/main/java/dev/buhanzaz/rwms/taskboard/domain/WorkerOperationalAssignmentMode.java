package dev.buhanzaz.rwms.taskboard.domain;

/**
 * Operational effect of a logistics-backed worker commitment.
 *
 * <p>{@link #TRIP_ONLY} reserves a driver for one cross-warehouse trip while preserving the
 * worker's existing operational and home warehouse placement.
 */
public enum WorkerOperationalAssignmentMode {
  TEMPORARY,
  PERMANENT,
  TRIP_ONLY
}
