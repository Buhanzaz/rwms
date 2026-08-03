package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;

public record ReplaceRepairCapacitySettingsRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Min(1) Integer repairPlaceCount,
    @NotNull @Min(1) @Max(1440) Integer automaticRefillDelayMinutes) {}
