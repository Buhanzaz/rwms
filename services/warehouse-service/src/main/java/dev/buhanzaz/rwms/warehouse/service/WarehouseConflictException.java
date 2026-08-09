package dev.buhanzaz.rwms.warehouse.service;

/** Signals a command conflict that is safe to expose as an HTTP 409 response. */
public class WarehouseConflictException extends RuntimeException {
  public WarehouseConflictException(String message) {
    super(message);
  }
}
