package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Version-fenced command for a one-way administrator lifecycle transition.
 *
 * @param expectedVersion current aggregate version observed by the caller
 */
public record WarehouseLifecycleTransitionRequest(@NotNull @Min(0) Long expectedVersion) {}
