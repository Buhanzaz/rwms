package dev.buhanzaz.rwms.logistics.driver.domain;

/**
 * Enumerates Driver Task Kind values used by logistics-owned persisted workflow state.
 */
public enum DriverTaskKind {
  DELIVER_TO_REPAIR,
  REMOVE_FROM_REPAIR,
  CAPITAL_TO_PRODUCTION,
  GENERAL_MOVEMENT;

  public boolean consumesRepairPlace() {
    return this == DELIVER_TO_REPAIR;
  }

  public boolean releasesRepairPlace() {
    return this == REMOVE_FROM_REPAIR;
  }
}
