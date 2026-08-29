package dev.buhanzaz.rwms.asset.domain;

/** Durable lifecycle of one cabin reservation owned by an inter-warehouse transfer line. */
public enum TransferUnitReservationState {
  ACTIVE,
  RELEASED,
  CONSUMED
}
