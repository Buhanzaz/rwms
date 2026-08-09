package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * Appends an immutable, future-effective timezone decision for an operated warehouse.
 *
 * @param expectedVersion current aggregate version observed by the caller
 * @param timeZone canonical IANA timezone identifier to become effective
 * @param effectiveFrom future instant at which the timezone becomes operationally effective
 */
public record ScheduleWarehouseTimeZoneRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotBlank @Size(max = 64) String timeZone,
    @NotNull OffsetDateTime effectiveFrom) {}
