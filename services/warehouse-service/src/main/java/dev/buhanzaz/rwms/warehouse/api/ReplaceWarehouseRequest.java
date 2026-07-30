package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplaceWarehouseRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 255) String city,
    @Size(max = 1000) String address,
    @NotBlank @Size(max = 64) String timeZone,
    @NotNull Boolean active,
    @Min(0) Integer sortOrder) {}
