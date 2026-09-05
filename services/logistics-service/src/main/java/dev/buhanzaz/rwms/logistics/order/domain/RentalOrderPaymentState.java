package dev.buhanzaz.rwms.logistics.order.domain;

/** Five-minute reservation lifecycle; null means no payment window, including historical orders. */
public enum RentalOrderPaymentState {
  PENDING,
  CONFIRMED,
  EXPIRING,
  EXPIRED,
  CANCELLED
}
