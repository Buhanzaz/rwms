package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Private logistics projection enriched with the cabin that consumes the repair place. */
public record LogisticsRepairPlaceAllocationResponse(
    UUID id,
    long version,
    UUID warehouseId,
    UUID repairId,
    UUID rentalItemId,
    RepairPlaceAllocationState state,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
