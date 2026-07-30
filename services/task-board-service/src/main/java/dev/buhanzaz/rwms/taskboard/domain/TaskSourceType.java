package dev.buhanzaz.rwms.taskboard.domain;

/**
 * A public, user-navigable source aggregate for a task that was registered by
 * another service. Internal OAuth client IDs are deliberately not exposed to
 * panel consumers.
 */
public enum TaskSourceType {
  MAINTENANCE_REPAIR
}
