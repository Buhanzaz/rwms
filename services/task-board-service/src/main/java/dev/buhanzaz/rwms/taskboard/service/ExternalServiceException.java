package dev.buhanzaz.rwms.taskboard.service;

/** Reports an unavailable or invalid private dependency without fabricating task-board success. */
public class ExternalServiceException extends RuntimeException {
  public ExternalServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
