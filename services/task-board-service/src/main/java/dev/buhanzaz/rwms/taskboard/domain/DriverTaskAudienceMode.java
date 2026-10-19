package dev.buhanzaz.rwms.taskboard.domain;

/**
 * Determines which warehouse drivers may discover and execute a logistics driver task.
 *
 * <p>Only {@link #ASSIGNED_DRIVER} carries an exact worker identity. Shared warehouse-driver work
 * stays identity-free until task-board records the actual executor as an assignment.
 */
public enum DriverTaskAudienceMode {
  /** The task remains visible to dispatchers but to no worker. */
  UNASSIGNED,

  /** Only the explicitly planned driver may see and execute the task. */
  ASSIGNED_DRIVER,

  /** Every qualified driver in the warehouse may see the unclaimed task. */
  WAREHOUSE_DRIVERS
}
