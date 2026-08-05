package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WarehouseLifecycleTransitionRequest(@NotNull @Min(0) Long expectedVersion) {}
