package dev.buhanzaz.rwms.logistics.domain;

/** Explicit asset-owned reservation readiness for a confirmed transfer plan. */
public enum TransferReservationReadiness {
  NOT_RESERVED,
  RESERVING,
  RESERVED,
  FAILED,
  RELEASING,
  RELEASED
}
