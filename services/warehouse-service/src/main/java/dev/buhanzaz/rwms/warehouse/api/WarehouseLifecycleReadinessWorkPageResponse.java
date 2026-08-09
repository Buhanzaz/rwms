package dev.buhanzaz.rwms.warehouse.api;

import java.util.List;
import java.util.UUID;

/**
 * Keyset page for durable owner-specific lifecycle readiness reconciliation.
 *
 * @param items work owed by the authenticated owner in UUID order
 * @param nextAfter cursor for the next page, or {@code null} when this page is complete
 */
public record WarehouseLifecycleReadinessWorkPageResponse(
    List<WarehouseLifecycleReadinessWorkResponse> items, UUID nextAfter) {}
