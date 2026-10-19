package dev.buhanzaz.rwms.logistics.customer.claims;

/** Immutable audit action recorded for a customer cabin problem lifecycle mutation. */
public enum CustomerCabinProblemActionKind {
  STATUS_TRANSITION,
  RESOLUTION_DECISION
}
