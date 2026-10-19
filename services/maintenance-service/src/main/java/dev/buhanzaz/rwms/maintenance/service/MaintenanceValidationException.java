package dev.buhanzaz.rwms.maintenance.service;

/** Signals a maintenance service-boundary failure for MaintenanceValidation. */
public class MaintenanceValidationException extends RuntimeException {
  private final String code;

  public MaintenanceValidationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() { return code; }
}
