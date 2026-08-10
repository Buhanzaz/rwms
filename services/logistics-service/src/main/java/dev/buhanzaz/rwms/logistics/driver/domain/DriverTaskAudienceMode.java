package dev.buhanzaz.rwms.logistics.driver.domain;

/**
 * Defines who may discover and execute a logistics-owned driver task in WorkerApp.
 */
public enum DriverTaskAudienceMode {
  UNASSIGNED,
  ASSIGNED_DRIVER,
  WAREHOUSE_DRIVERS
}
