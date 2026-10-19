package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Optimistically fenced replacement of one warehouse estimate creation window. */
public record ReplaceEstimateCreationWindowSettingsRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Min(1) @Max(3650) Integer days) {}
