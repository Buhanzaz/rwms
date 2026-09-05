package dev.buhanzaz.rwms.logistics.order.domain;

/** Five-minute reservation lifecycle; null means no payment window, including historical orders. */
public enum RentalOrderPaymentState {
  PENDING,
  CONFIRMED,
  EXPIRING,
  EXPIRED,
  CANCELLED;

  /** Existing orders without a payment window keep their admission; a new window requires proof. */
  public static boolean allowsFulfillment(RentalOrderPaymentState state) {
    return state == null || state == CONFIRMED;
  }
}
