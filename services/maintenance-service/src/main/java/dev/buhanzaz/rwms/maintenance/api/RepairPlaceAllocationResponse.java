package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import java.time.OffsetDateTime;
import java.util.UUID;

/** HTTP response representation for RepairPlaceAllocation; it is not a mutable persistence model. */
public record RepairPlaceAllocationResponse(
    UUID id,
    long version,
    UUID warehouseId,
    UUID repairId,
    RepairPlaceAllocationState state,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
