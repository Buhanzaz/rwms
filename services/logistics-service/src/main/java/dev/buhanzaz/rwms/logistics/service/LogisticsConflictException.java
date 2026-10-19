package dev.buhanzaz.rwms.logistics.service;

/**
 * Signals a logistics conflict such as stale version, invalid state or incompatible replay.
 */
public class LogisticsConflictException extends RuntimeException {
  public LogisticsConflictException(String message) {
    super(message);
  }
}
