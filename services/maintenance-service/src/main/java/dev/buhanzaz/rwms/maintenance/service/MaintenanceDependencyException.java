package dev.buhanzaz.rwms.maintenance.service;

import org.springframework.http.HttpStatus;

public class MaintenanceDependencyException extends RuntimeException {
  private final HttpStatus status;

  public MaintenanceDependencyException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public MaintenanceDependencyException(HttpStatus status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public HttpStatus status() { return status; }
}
