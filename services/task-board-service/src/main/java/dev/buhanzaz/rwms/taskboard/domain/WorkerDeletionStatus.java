package dev.buhanzaz.rwms.taskboard.domain;

/** Recovery-visible state of a coordinated worker deletion. */
public enum WorkerDeletionStatus {
  PENDING_AUTH,
  AUTH_DELETED,
  ERROR
}
