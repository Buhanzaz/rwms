package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Full replacement of mutable warehouse metadata.
 *
 * <p>{@code expectedVersion} is the optimistic-concurrency fence. Lifecycle state is not part of
 * this model, and an operated warehouse must schedule rather than directly correct its timezone.
 *
 * @param expectedVersion current aggregate version observed by the caller
 * @param name display name; normalized before uniqueness is evaluated
 * @param city human-readable city
 * @param address optional human-readable address
 * @param timeZone canonical IANA timezone identifier for an unused warehouse
 * @param sortOrder optional non-negative directory ordering value
 */
public record ReplaceWarehouseRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 255) String city,
    @Size(max = 1000) String address,
    @NotBlank @Size(max = 64) String timeZone,
    @Min(0) Integer sortOrder) {}
