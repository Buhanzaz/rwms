package dev.buhanzaz.rwms.logistics.order.domain;

/**
 * Enumerates Rental Order Status values used by logistics-owned persisted workflow state.
 */
public enum RentalOrderStatus {
  DRAFT,
  SAVED,
  FULFILLED,
  CLOSED,
  CANCELLED
}
