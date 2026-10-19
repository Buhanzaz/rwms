package dev.buhanzaz.rwms.logistics.service;

/**
 * Signals that a logistics-owned resource required by an operation does not exist.
 */
public class LogisticsNotFoundException extends RuntimeException {
  public LogisticsNotFoundException() {
    super("Logistics document was not found");
  }
}
