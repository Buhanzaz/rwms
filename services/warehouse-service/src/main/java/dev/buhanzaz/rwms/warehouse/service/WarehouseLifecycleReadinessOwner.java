package dev.buhanzaz.rwms.warehouse.service;

import java.util.Arrays;

/**
 * Resource owners whose durable confirmation is required before a draining warehouse can become
 * inactive. The owner is always inferred from the authenticated service client.
 */
public enum WarehouseLifecycleReadinessOwner {
  ASSET("asset-service"),
  INVENTORY("inventory-service"),
  LOGISTICS("logistics-service"),
  MAINTENANCE("maintenance-service"),
  TASK_BOARD("task-board-service");

  private final String clientId;

  WarehouseLifecycleReadinessOwner(String clientId) {
    this.clientId = clientId;
  }

  public String clientId() {
    return clientId;
  }

  public static WarehouseLifecycleReadinessOwner requireClientId(String clientId) {
    return Arrays.stream(values())
        .filter(owner -> owner.clientId.equals(clientId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported warehouse lifecycle owner"));
  }
}
