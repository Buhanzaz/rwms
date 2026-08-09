package dev.buhanzaz.rwms.logistics.domain;

/**
 * Enumerates Logistics Guard State values used by logistics-owned persisted workflow state.
 */
public enum LogisticsGuardState {
  PENDING,
  ACTIVE,
  RELEASED,
  CONFLICT,
  RECONCILIATION_REQUIRED
}
