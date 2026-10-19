package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

/**
 * A DRAINING warehouse whose readiness is still required from the authenticated owner.
 *
 * @param warehouseId stable warehouse identity
 * @param warehouseVersion version to use for the subsequent confirmation
 * @param lifecycleState always {@code DRAINING}
 */
public record WarehouseLifecycleReadinessWorkResponse(
    UUID warehouseId, long warehouseVersion, String lifecycleState) {}
