package dev.buhanzaz.rwms.maintenance.eventing.transport;

public class MaintenanceInboundValidationException extends RuntimeException {
  public MaintenanceInboundValidationException(String message) {
    super(message);
  }

  public MaintenanceInboundValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
