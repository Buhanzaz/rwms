package dev.buhanzaz.rwms.logistics.service;

public class LogisticsNotFoundException extends RuntimeException {
  public LogisticsNotFoundException() {
    super("Logistics document was not found");
  }
}
