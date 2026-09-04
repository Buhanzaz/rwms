package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** HTTP request payload for ReplaceRepairCapacitySettings; command semantics are defined by its OpenAPI operation. */
public record ReplaceRepairCapacitySettingsRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Min(1) Integer repairPlaceCount) {}
