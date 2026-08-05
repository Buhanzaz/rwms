package dev.buhanzaz.rwms.warehouse.service;

import java.util.Arrays;

/** The operation owner is inferred from the authenticated private client, never supplied by it. */
public enum WarehouseOperationSource {
  ASSET("asset-service"),
  INVENTORY("inventory-service"),
  LOGISTICS("logistics-service"),
  MAINTENANCE("maintenance-service");

  private final String clientId;

  WarehouseOperationSource(String clientId) {
    this.clientId = clientId;
  }

  public String clientId() {
    return clientId;
  }

  public static WarehouseOperationSource requireClientId(String clientId) {
    return Arrays.stream(values())
        .filter(source -> source.clientId.equals(clientId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported warehouse operation source"));
  }
}
