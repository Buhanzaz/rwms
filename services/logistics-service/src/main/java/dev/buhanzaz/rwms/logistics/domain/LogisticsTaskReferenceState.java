package dev.buhanzaz.rwms.logistics.domain;

/**
 * Enumerates Logistics Task Reference State values used by logistics-owned persisted workflow state.
 */
public enum LogisticsTaskReferenceState {
  PENDING,
  REGISTERED,
  READY,
  DONE,
  CANCELLED,
  CONFLICT,
  RECONCILIATION_REQUIRED
}
