package dev.buhanzaz.rwms.maintenance.api;

import java.util.List;
import java.util.UUID;

/** HTTP response representation for RepairPlaceProjection; it is not a mutable persistence model. */
public record RepairPlaceProjectionResponse(
    UUID warehouseId,
    int repairPlaceCount,
    long reservedCount,
    long occupiedCount,
    long readyToReleaseCount,
    long availableCount,
    boolean overCapacity,
    List<RepairPlaceAllocationResponse> allocations) {}
