package dev.buhanzaz.rwms.logistics.customer.domain;

/**
 * A quote is not an executed booking command; APPLYING preserves an unknown cancellation outcome.
 */
public enum CustomerChangeApplicationState {
  OFFERED,
  APPLYING,
  APPLIED
}
