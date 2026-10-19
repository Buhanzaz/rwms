package dev.buhanzaz.rwms.logistics.customer.domain;

/** Durable state of one idempotent customer booking mutation. */
public enum CustomerBookingMutationState {
  PENDING,
  COMPLETED,
  QUARANTINED
}
