package dev.buhanzaz.rwms.taskboard.domain;

/** Lifecycle of the task-board-owned assignment between a route entry and worker or group. */
public enum AssignmentStatus {
  ACTIVE,
  PAUSED,
  DONE,
  CANCELLED
}
