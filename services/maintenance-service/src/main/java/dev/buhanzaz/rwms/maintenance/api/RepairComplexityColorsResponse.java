package dev.buhanzaz.rwms.maintenance.api;

import java.time.OffsetDateTime;

public record RepairComplexityColorsResponse(
    long version,
    String lightColor,
    String mediumColor,
    String complexColor,
    String capitalColor,
    OffsetDateTime updatedAt) {}
