package dev.buhanzaz.rwms.logistics.domain;

/**
 * Enumerates Logistics Line State values used by logistics-owned persisted workflow state.
 */
public enum LogisticsLineState {
  PENDING,
  DEPARTING,
  DEPARTED,
  ARRIVING,
  ARRIVED,
  CONFLICT,
  CANCELLED
}
