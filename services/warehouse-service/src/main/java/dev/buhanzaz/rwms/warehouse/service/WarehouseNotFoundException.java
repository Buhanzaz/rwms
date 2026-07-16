package dev.buhanzaz.rwms.warehouse.service;

public class WarehouseNotFoundException extends RuntimeException {
  public WarehouseNotFoundException() {
    super("Warehouse was not found");
  }
}
