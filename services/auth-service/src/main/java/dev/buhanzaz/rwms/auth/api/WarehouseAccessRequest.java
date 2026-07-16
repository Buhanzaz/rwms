package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WarehouseAccessRequest(
        @NotBlank @Size(max = 128) String warehouseId,
        @NotNull WarehouseAccessLevel accessLevel,
        @Size(max = 1000) String comment,
        Boolean active) {
}
