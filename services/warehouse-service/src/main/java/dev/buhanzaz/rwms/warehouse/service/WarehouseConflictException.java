package dev.buhanzaz.rwms.warehouse.service;

public class WarehouseConflictException extends RuntimeException {
  public WarehouseConflictException(String message) {
    super(message);
  }
}
