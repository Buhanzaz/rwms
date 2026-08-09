package dev.buhanzaz.rwms.logistics.driver.domain;

/**
 * Enumerates Driver Task Source Type values used by logistics-owned persisted workflow state.
 */
public enum DriverTaskSourceType {
  REPAIR,
  ESTIMATE,
  INVENTORY,
  REPAIR_PLACE,
  CAPITAL_REPAIR,
  MANUAL
}
