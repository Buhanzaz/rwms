package dev.buhanzaz.rwms.asset.integration.warehouse;

import java.util.UUID;

public interface WarehouseRegistryClient {
  void requireActive(UUID warehouseId);
}
