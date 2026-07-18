package dev.buhanzaz.rwms.logistics.eventing.inbound;

/** The same source event ID must never carry a different immutable envelope. */
public class LogisticsInboundEventIdentityConflictException extends RuntimeException {
  public LogisticsInboundEventIdentityConflictException() {
    super("Inbound event ID conflicts with its previously staged envelope");
  }
}
