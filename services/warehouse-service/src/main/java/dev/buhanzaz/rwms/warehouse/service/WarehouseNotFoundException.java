package dev.buhanzaz.rwms.warehouse.service;

/** Signals that a requested warehouse aggregate does not exist. */
public class WarehouseNotFoundException extends RuntimeException {
  public WarehouseNotFoundException() {
    super("Warehouse was not found");
  }
}
