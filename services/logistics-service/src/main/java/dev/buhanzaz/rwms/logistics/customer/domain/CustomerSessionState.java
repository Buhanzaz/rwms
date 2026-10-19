package dev.buhanzaz.rwms.logistics.customer.domain;

/** Durable orchestration state for one customer-owned rental inquiry. */
public enum CustomerSessionState {
  ACTIVE,
  SELECTION_PENDING,
  CHECKOUT_PENDING,
  BOOKED,
  CANCEL_PENDING,
  CANCELLED
}
