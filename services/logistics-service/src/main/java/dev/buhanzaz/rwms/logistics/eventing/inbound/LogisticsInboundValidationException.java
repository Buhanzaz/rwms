package dev.buhanzaz.rwms.logistics.eventing.inbound;

/** Raised when a source event is not an exact declared, sanitized input fact. */
public class LogisticsInboundValidationException extends RuntimeException {
  public LogisticsInboundValidationException(String message) {
    super(message);
  }

  public LogisticsInboundValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
