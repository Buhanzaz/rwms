package dev.buhanzaz.rwms.maintenance.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RepairPlaceTransitionRequest(
    @NotNull @Min(0) Long expectedVersion) {}
