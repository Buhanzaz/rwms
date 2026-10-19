package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** HTTP request payload for RepairPlaceTransition; command semantics are defined by its OpenAPI operation. */
public record RepairPlaceTransitionRequest(
    @NotNull @Min(0) Long expectedVersion) {}
