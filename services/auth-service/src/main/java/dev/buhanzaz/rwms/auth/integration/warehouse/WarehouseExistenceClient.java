package dev.buhanzaz.rwms.auth.integration.warehouse;

import java.util.Set;
import java.util.UUID;

/**
 * Port that confirms every referenced warehouse exists and is active before auth-service persists
 * a warehouse-scoped grant or worker credential.
 */
public interface WarehouseExistenceClient {

    /**
     * Verifies that all supplied warehouse identifiers are active.
     *
     * <p>An implementation must fail the complete caller transaction when an identifier is
     * unknown, inactive, or cannot be verified safely.
     *
     * @param warehouseIds distinct canonical warehouse identifiers to validate
     */
    void requireActive(Set<UUID> warehouseIds);
}
