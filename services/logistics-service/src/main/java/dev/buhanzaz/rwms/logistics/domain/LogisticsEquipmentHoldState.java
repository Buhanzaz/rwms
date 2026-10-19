package dev.buhanzaz.rwms.logistics.domain;

/**
 * Enumerates Logistics Equipment Hold State values used by logistics-owned persisted workflow state.
 */
public enum LogisticsEquipmentHoldState {
  ACTIVE,
  COMMITTED,
  RELEASED,
  CONFLICT,
  RECONCILIATION_REQUIRED
}
