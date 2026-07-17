package dev.buhanzaz.rwms.inventory.service;

import org.springframework.http.HttpStatus;

public final class InventoryException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public InventoryException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static InventoryException notFound(String message) {
    return new InventoryException(HttpStatus.NOT_FOUND, "INVENTORY_NOT_FOUND", message);
  }

  public static InventoryException conflict(String message) {
    return new InventoryException(HttpStatus.CONFLICT, "INVENTORY_VERSION_CONFLICT", message);
  }

  public static InventoryException badRequest(String message) {
    return new InventoryException(HttpStatus.BAD_REQUEST, "INVENTORY_VALIDATION_FAILED", message);
  }

  public static InventoryException dependency(String message) {
    return new InventoryException(
        HttpStatus.SERVICE_UNAVAILABLE, "INVENTORY_DEPENDENCY_UNAVAILABLE", message);
  }
}
