package dev.buhanzaz.rwms.auth.integration.warehouse;

import java.util.Set;
import java.util.UUID;

final class NoOpWarehouseExistenceClient implements WarehouseExistenceClient {

    @Override
    public void requireActive(Set<UUID> warehouseIds) {
        // Validation is intentionally configuration-gated until warehouse-service is activated.
    }
}
