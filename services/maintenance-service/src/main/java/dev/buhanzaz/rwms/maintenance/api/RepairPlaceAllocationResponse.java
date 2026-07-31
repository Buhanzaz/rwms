package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RepairPlaceAllocationResponse(
    UUID id,
    long version,
    UUID warehouseId,
    UUID repairId,
    RepairPlaceAllocationState state,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
