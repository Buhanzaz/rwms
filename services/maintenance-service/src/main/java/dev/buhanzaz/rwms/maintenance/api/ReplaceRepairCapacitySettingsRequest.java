package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReplaceRepairCapacitySettingsRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Min(1) Integer repairPlaceCount) {}
