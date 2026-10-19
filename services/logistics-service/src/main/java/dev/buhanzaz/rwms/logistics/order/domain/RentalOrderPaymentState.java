package dev.buhanzaz.rwms.logistics.order.domain;

/** Five-minute reservation lifecycle; null carries no evidence of payment. */
public enum RentalOrderPaymentState {
  PENDING,
  CONFIRMED,
  EXPIRING,
  EXPIRED,
  CANCELLED;

  /** Only an explicit confirmation admits a saved order to planning and fulfillment. */
  public static boolean allowsFulfillment(RentalOrderPaymentState state) {
    return state == CONFIRMED;
  }
}
