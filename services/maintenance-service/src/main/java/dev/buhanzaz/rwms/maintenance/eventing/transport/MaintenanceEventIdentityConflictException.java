package dev.buhanzaz.rwms.maintenance.eventing.transport;

/** Signals a validated maintenance eventing failure at the service boundary. */
public class MaintenanceEventIdentityConflictException extends RuntimeException {
  public MaintenanceEventIdentityConflictException() {
    super("Inbound eventId was reused with different immutable content");
  }
}
