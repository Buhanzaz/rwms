package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.CabinStatusColors;
import java.time.OffsetDateTime;
import java.util.Map;

/** Public global palette from {@link CabinStatusColors}, independent of warehouse selection. */
public record CabinStatusColorsResponse(
    long version, Map<String, String> colors, OffsetDateTime updatedAt) {}
