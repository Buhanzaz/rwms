package dev.buhanzaz.rwms.maintenance.api;

import java.time.OffsetDateTime;

/** Public global estimate creation window representation. */
public record EstimateCreationWindowSettingsResponse(
    long version,
    int days,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
