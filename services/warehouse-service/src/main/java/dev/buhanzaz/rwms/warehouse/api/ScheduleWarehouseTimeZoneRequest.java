package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record ScheduleWarehouseTimeZoneRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotBlank @Size(max = 64) String timeZone,
    @NotNull OffsetDateTime effectiveFrom) {}
