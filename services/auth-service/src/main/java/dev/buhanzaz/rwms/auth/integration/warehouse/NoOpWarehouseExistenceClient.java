package dev.buhanzaz.rwms.auth.integration.warehouse;

import java.util.Set;
import java.util.UUID;

/**
 * Disabled implementation of warehouse validation used before the Warehouse Service integration is
 * enabled.
 *
 * <p>Keeping this port as a no-op avoids introducing a URL, secret, or network dependency into
 * auth-service while warehouse validation is deliberately inactive.
 */
final class NoOpWarehouseExistenceClient implements WarehouseExistenceClient {

    /**
     * Accepts the requested warehouse identifiers without a remote lookup while validation is
     * disabled by configuration.
     *
     * @param warehouseIds warehouse identifiers supplied by an authorization mutation
     */
    @Override
    public void requireActive(Set<UUID> warehouseIds) {
        // Validation is intentionally configuration-gated until warehouse-service is activated.
    }
}
