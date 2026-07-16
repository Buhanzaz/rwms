package dev.buhanzaz.rwms.auth.integration.warehouse;

import java.util.Set;
import java.util.UUID;

public interface WarehouseExistenceClient {

    void requireActive(Set<UUID> warehouseIds);
}
