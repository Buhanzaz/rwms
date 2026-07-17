package dev.buhanzaz.rwms.maintenance.eventing.transport;

public class MaintenanceEventIdentityConflictException extends RuntimeException {
  public MaintenanceEventIdentityConflictException() {
    super("Inbound eventId was reused with different immutable content");
  }
}
