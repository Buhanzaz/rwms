package dev.buhanzaz.rwms.logistics.domain;

/**
 * Enumerates Logistics Media Readiness values used by logistics-owned persisted workflow state.
 */
public enum LogisticsMediaReadiness {
  PENDING,
  READY,
  REJECTED,
  RECONCILIATION_REQUIRED
}
