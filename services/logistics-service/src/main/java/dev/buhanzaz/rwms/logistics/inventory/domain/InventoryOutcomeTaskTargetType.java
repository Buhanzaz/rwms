package dev.buhanzaz.rwms.logistics.inventory.domain;

/** Logistics-owned task or guard capability displaced by a completed inventory outcome. */
public enum InventoryOutcomeTaskTargetType {
  DRIVER_TASK,
  DOCUMENT_TASK,
  EQUIPMENT_TASK,
  GUARD_LEASE
}
