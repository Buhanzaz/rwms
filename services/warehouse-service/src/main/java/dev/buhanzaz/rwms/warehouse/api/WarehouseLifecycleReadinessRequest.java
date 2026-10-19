package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Version-fenced readiness confirmation for the authenticated lifecycle owner.
 *
 * @param expectedVersion current aggregate version observed before the first confirmation attempt
 */
public record WarehouseLifecycleReadinessRequest(@NotNull @Min(0) Long expectedVersion) {}
