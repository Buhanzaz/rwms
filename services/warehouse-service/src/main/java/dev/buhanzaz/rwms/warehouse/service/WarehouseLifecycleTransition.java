package dev.buhanzaz.rwms.warehouse.service;

/** Immutable audit facts recorded by warehouse-service for lifecycle state transitions. */
public enum WarehouseLifecycleTransition {
  DRAINING_STARTED,
  INACTIVATED
}
