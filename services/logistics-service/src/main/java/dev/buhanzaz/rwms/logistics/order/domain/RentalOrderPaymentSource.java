package dev.buhanzaz.rwms.logistics.order.domain;

/** Explicit confirmation provenance, without implying a payment-provider transaction or capture. */
public enum RentalOrderPaymentSource {
  CUSTOMER_TEST,
  MANAGER_CONFIRMATION
}
