package dev.buhanzaz.rwms.maintenance.service;

public class MaintenanceValidationException extends RuntimeException {
  private final String code;

  public MaintenanceValidationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() { return code; }
}
