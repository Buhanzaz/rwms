package dev.buhanzaz.rwms.logistics.customer.domain;

/** Lifecycle of a server-calculated customer delivery slot. */
public enum CustomerDeliverySlotState {
  OFFERED,
  HELD,
  CHECKOUT_PENDING,
  CONFIRMED,
  RELEASED
}
