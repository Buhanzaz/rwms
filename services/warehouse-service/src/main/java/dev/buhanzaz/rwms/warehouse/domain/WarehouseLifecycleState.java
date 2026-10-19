package dev.buhanzaz.rwms.warehouse.domain;

/**
 * Canonical warehouse lifecycle. A warehouse never returns to an earlier state: UUID identity and
 * historical metadata remain readable after it becomes {@link #INACTIVE}.
 */
public enum WarehouseLifecycleState {
  ACTIVE,
  DRAINING,
  INACTIVE
}
