package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** HTTP request payload for ReplaceRepairComplexityColors; command semantics are defined by its OpenAPI operation. */
public record ReplaceRepairComplexityColorsRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String lightColor,
    @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String mediumColor,
    @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String complexColor,
    @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String capitalColor) {}
