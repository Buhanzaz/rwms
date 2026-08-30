package dev.buhanzaz.rwms.taskboard.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Narrow owner boundary for current warehouse metadata used by Driver Shift. */
public interface WarehouseIdentityGateway {
  /** Returns current owner-held metadata without creating a local warehouse identity copy. */
  WarehouseIdentity identity(UUID warehouseId);

  /** Immutable result of one warehouse-service identity lookup. */
  record WarehouseIdentity(
      UUID id,
      long version,
      boolean active,
      String name,
      String city,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String timeZone) {}
}
