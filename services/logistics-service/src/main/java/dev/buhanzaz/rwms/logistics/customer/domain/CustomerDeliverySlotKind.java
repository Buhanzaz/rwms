package dev.buhanzaz.rwms.logistics.customer.domain;

/**
 * Distinguishes a customer-selected fixed arrival window from a route-planner-selected time within
 * the complete delivery day.
 */
public enum CustomerDeliverySlotKind {
  FIXED_WINDOW,
  DURING_DAY
}
