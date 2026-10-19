package dev.buhanzaz.rwms.logistics.customer.domain;

/** Server-owned settlement, independent of whether the booking change has been applied. */
public enum CustomerChangeSettlement {
  POLICY_UNCONFIGURED,
  PAYMENT_REQUIRED,
  NOT_REQUIRED,
  TEST_PAID,
  WAIVED
}
