package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** HTTP request payload for ReplaceRepairComplexitySettings; command semantics are defined by its OpenAPI operation. */
public record ReplaceRepairComplexitySettingsRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Min(1) Integer lightBoundaryMinutes,
    @NotNull @Min(2) Integer mediumBoundaryMinutes,
    @NotNull @Min(3) Integer complexBoundaryMinutes) {}
