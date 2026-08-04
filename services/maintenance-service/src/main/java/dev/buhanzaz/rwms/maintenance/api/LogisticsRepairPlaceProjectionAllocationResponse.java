package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Repair-place allocation enriched with repair priority and the earliest unfinished repair stage.
 * This projection is private to logistics scheduling and the public driver-board read model;
 * transition responses deliberately stay limited to allocation state.
 */
public record LogisticsRepairPlaceProjectionAllocationResponse(
    UUID id,
    long version,
    UUID warehouseId,
    UUID repairId,
    UUID rentalItemId,
    RepairPlaceAllocationState state,
    String repairStageName,
    RepairStageState repairStageState,
    int priority,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
