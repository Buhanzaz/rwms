package dev.buhanzaz.rwms.taskboard.service;

public class ExternalServiceException extends RuntimeException {
  public ExternalServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
