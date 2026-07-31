package dev.buhanzaz.rwms.maintenance.api;

import java.util.List;
import java.util.UUID;

/** Authoritative warehouse repair-place snapshot consumed by logistics scheduling. */
public record LogisticsRepairPlaceProjectionResponse(
    UUID warehouseId,
    int repairPlaceCount,
    long reservedCount,
    long occupiedCount,
    long readyToReleaseCount,
    long availableCount,
    boolean overCapacity,
    List<LogisticsRepairPlaceAllocationResponse> allocations) {}
