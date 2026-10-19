package dev.buhanzaz.rwms.maintenance.service;

import org.springframework.http.HttpStatus;

/**
 * Signals a maintenance dependency failure with one maintenance-owned public Problem Details code.
 * Constructors without a code preserve the generic dependency-unavailable contract.
 */
public class MaintenanceDependencyException extends RuntimeException {
  public static final String DEFAULT_CODE = "MAINTENANCE_DEPENDENCY_UNAVAILABLE";

  private final HttpStatus status;
  private final String code;

  public MaintenanceDependencyException(HttpStatus status, String message) {
    this(status, DEFAULT_CODE, message, null);
  }

  public MaintenanceDependencyException(HttpStatus status, String message, Throwable cause) {
    this(status, DEFAULT_CODE, message, cause);
  }

  public MaintenanceDependencyException(HttpStatus status, String code, String message) {
    this(status, code, message, null);
  }

  public MaintenanceDependencyException(
      HttpStatus status, String code, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() { return status; }

  public String code() { return code; }
}
