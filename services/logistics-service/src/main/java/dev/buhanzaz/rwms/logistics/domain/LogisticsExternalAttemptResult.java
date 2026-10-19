package dev.buhanzaz.rwms.logistics.domain;

/**
 * Enumerates Logistics External Attempt Result values used by logistics-owned persisted workflow state.
 */
public enum LogisticsExternalAttemptResult {
  PENDING,
  CONFIRMED,
  PERMANENT_REJECTION,
  RETRY,
  RECONCILIATION_REQUIRED
}
