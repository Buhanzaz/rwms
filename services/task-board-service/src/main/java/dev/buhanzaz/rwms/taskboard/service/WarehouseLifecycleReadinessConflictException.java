package dev.buhanzaz.rwms.taskboard.service;

/** A stale lifecycle version is expected during concurrent owner reconciliation. */
public final class WarehouseLifecycleReadinessConflictException extends RuntimeException {
  public WarehouseLifecycleReadinessConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
