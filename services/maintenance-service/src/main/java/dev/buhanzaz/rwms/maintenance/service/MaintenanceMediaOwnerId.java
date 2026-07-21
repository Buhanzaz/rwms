package dev.buhanzaz.rwms.maintenance.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class MaintenanceMediaOwnerId {
  static final String CATALOG_NODE_PREFIX = "maintenance-catalog-node-owner:";

  private MaintenanceMediaOwnerId() {}

  static UUID catalogNode(UUID warehouseId, UUID nodeId) {
    if (warehouseId == null || nodeId == null) {
      throw new IllegalArgumentException("Catalog media owner identity is required");
    }
    return UUID.nameUUIDFromBytes(
        (CATALOG_NODE_PREFIX + warehouseId + ":" + nodeId)
            .getBytes(StandardCharsets.UTF_8));
  }
}
