package dev.buhanzaz.rwms.logistics.driver.domain;

/**
 * Enumerates Driver Task State values used by logistics-owned persisted workflow state.
 */
public enum DriverTaskState {
  REGISTERING,
  SCHEDULED,
  CURRENT,
  FINALIZING,
  COMPLETED,
  CANCELLED,
  RECONCILIATION_REQUIRED;

  public boolean isTerminal() {
    return this == COMPLETED || this == CANCELLED || this == RECONCILIATION_REQUIRED;
  }
}
