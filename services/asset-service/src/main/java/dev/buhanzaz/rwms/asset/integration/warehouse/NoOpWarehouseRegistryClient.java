package dev.buhanzaz.rwms.asset.integration.warehouse;

import java.util.UUID;

final class NoOpWarehouseRegistryClient implements WarehouseRegistryClient {
  @Override public void requireActive(UUID warehouseId) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
  }
}
