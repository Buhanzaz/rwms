package dev.buhanzaz.rwms.maintenance.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RepairComplexitySettingsResponse(
    UUID warehouseId,
    long version,
    int lightBoundaryMinutes,
    int mediumBoundaryMinutes,
    int complexBoundaryMinutes,
    Long importedFromTaskBoardVersion,
    OffsetDateTime importedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
