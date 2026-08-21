package dev.buhanzaz.rwms.maintenance.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Public warehouse-scoped estimate creation window representation. */
public record EstimateCreationWindowSettingsResponse(
    UUID warehouseId,
    long version,
    int days,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
