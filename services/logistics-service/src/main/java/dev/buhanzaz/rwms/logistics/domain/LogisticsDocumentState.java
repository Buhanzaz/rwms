package dev.buhanzaz.rwms.logistics.domain;

/**
 * Enumerates Logistics Document State values used by logistics-owned persisted workflow state.
 */
public enum LogisticsDocumentState {
  DRAFT,
  REGISTERING,
  INSPECTION_REQUIRED,
  ACCEPTING,
  ACCEPTED,
  ESTIMATE_PENDING,
  ESTIMATE_REQUESTED,
  PREPARING,
  AWAITING_CONFIRMATION,
  CONFIRMING_PREPARATION,
  SHIPPED,
  CANCELLING,
  DEPARTING,
  IN_TRANSIT,
  ARRIVING,
  COMPLETED,
  CANCELLED,
  CONFLICT,
  RECONCILIATION_REQUIRED
}
