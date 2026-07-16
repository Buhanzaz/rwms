package dev.buhanzaz.rwms.asset.service;

import org.springframework.http.HttpStatus;

public class AssetDependencyException extends RuntimeException {
  private final HttpStatus status;

  public AssetDependencyException(HttpStatus status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public AssetDependencyException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() { return status; }
}
