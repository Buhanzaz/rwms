package dev.buhanzaz.rwms.maintenance.api;

import java.time.OffsetDateTime;

/** HTTP response representation for RepairComplexityColors; it is not a mutable persistence model. */
public record RepairComplexityColorsResponse(
    long version,
    String lightColor,
    String mediumColor,
    String complexColor,
    String capitalColor,
    OffsetDateTime updatedAt) {}
