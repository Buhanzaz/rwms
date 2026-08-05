package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

/** A DRAINING warehouse whose readiness is still required from the authenticated owner. */
public record WarehouseLifecycleReadinessWorkResponse(
    UUID warehouseId, long warehouseVersion, String lifecycleState) {}
