package dev.buhanzaz.rwms.maintenance.service;

/** Signals a maintenance service-boundary failure for MaintenanceNotFound. */
public class MaintenanceNotFoundException extends RuntimeException {
  public MaintenanceNotFoundException(String message) { super(message); }
}
