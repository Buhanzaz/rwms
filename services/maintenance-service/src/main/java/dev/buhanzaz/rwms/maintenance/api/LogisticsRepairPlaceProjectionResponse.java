package dev.buhanzaz.rwms.maintenance.api;

import java.util.List;
import java.util.UUID;

/**
 * Authoritative warehouse repair-place snapshot consumed by logistics scheduling.
 *
 * <p>{@code automaticRefillDelayMinutes} is a transitional compatibility marker fixed at zero;
 * repair-place refill is immediately eligible.
 */
public record LogisticsRepairPlaceProjectionResponse(
    UUID warehouseId,
    int repairPlaceCount,
    int automaticRefillDelayMinutes,
    long reservedCount,
    long occupiedCount,
    long readyToReleaseCount,
    long availableCount,
    boolean overCapacity,
    List<LogisticsRepairPlaceProjectionAllocationResponse> allocations) {}
