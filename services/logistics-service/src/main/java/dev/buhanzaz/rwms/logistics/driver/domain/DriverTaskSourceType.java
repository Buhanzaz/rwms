package dev.buhanzaz.rwms.logistics.driver.domain;

/**
 * Enumerates Driver Task Source Type values used by logistics-owned persisted workflow state.
 * `LOGISTICS_DOCUMENT` identifies a new grouped shipment; the line source remains readable for
 * historical document tasks.
 */
public enum DriverTaskSourceType {
  REPAIR,
  ESTIMATE,
  INVENTORY,
  REPAIR_PLACE,
  CAPITAL_REPAIR,
  MANUAL,
  LOGISTICS_DOCUMENT,
  LOGISTICS_DOCUMENT_LINE
}
