package dev.buhanzaz.rwms.logistics.driver.domain;

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
