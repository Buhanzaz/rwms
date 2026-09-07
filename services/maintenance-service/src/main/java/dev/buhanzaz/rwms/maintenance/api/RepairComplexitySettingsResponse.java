package dev.buhanzaz.rwms.maintenance.api;

import java.time.OffsetDateTime;

/** HTTP response representation for RepairComplexitySettings; it is not a mutable persistence model. */
public record RepairComplexitySettingsResponse(
    long version,
    int lightBoundaryMinutes,
    int mediumBoundaryMinutes,
    int complexBoundaryMinutes,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
