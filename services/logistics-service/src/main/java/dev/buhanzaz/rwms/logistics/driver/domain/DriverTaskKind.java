package dev.buhanzaz.rwms.logistics.driver.domain;

/**
 * Enumerates Driver Task Kind values used by logistics-owned persisted workflow state.
 */
public enum DriverTaskKind {
  DELIVER_TO_REPAIR,
  REMOVE_FROM_REPAIR,
  CAPITAL_TO_PRODUCTION,
  GENERAL_MOVEMENT,
  SHIPMENT,
  RETURN,
  TRANSFER;

  public boolean consumesRepairPlace() {
    return this == DELIVER_TO_REPAIR;
  }

  public boolean releasesRepairPlace() {
    return this == REMOVE_FROM_REPAIR;
  }

  /**
   * Returns the identity-free audience used when a source does not select a driver explicitly.
   */
  public DriverTaskAudienceMode defaultAudienceMode() {
    return this == SHIPMENT || this == RETURN
        ? DriverTaskAudienceMode.UNASSIGNED
        : DriverTaskAudienceMode.WAREHOUSE_DRIVERS;
  }
}
