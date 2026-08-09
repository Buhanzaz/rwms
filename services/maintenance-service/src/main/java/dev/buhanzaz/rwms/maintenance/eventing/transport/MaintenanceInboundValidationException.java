package dev.buhanzaz.rwms.maintenance.eventing.transport;

/** Signals a validated maintenance eventing failure at the service boundary. */
public class MaintenanceInboundValidationException extends RuntimeException {
  public MaintenanceInboundValidationException(String message) {
    super(message);
  }

  public MaintenanceInboundValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
