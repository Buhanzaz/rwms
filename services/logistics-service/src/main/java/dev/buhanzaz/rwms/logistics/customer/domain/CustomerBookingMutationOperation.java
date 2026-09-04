package dev.buhanzaz.rwms.logistics.customer.domain;

/** Customer-owned booking mutations that preserve the completed checkout identity. */
public enum CustomerBookingMutationOperation {
  CANCEL,
  RESCHEDULE
}
