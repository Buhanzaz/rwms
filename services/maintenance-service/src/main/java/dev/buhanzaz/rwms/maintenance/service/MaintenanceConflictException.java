package dev.buhanzaz.rwms.maintenance.service;

/** Signals a maintenance service-boundary failure for MaintenanceConflict. */
public class MaintenanceConflictException extends RuntimeException {
  private final String code;

  public MaintenanceConflictException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() { return code; }
}
