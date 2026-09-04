package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.domain.RepairCapacitySettings;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for {@link RepairCapacitySettings}.
 */
public record RepairCapacitySettingsResponse(
    UUID warehouseId,
    long version,
    int repairPlaceCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
