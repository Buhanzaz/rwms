package dev.buhanzaz.rwms.warehouse.api;

import java.util.List;
import java.util.UUID;

/** Keyset page for durable owner-specific lifecycle readiness reconciliation. */
public record WarehouseLifecycleReadinessWorkPageResponse(
    List<WarehouseLifecycleReadinessWorkResponse> items, UUID nextAfter) {}
