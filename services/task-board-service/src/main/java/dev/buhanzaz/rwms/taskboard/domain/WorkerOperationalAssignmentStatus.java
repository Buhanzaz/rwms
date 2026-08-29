package dev.buhanzaz.rwms.taskboard.domain;

/** Physical and operational lifecycle of a transfer-backed worker assignment. */
public enum WorkerOperationalAssignmentStatus {
  PLANNED,
  IN_TRANSIT,
  ACTIVE,
  COMPLETED,
  CANCELLED
}
