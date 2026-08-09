package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Requested warehouse-specific grant for an interactive user.
 *
 * @param warehouseId warehouse identifier supplied by the caller and canonicalized by the service
 * @param accessLevel requested configured access level
 * @param comment optional administrative note for the grant
 * @param active optional active state, defaulting to enabled when omitted
 */
public record WarehouseAccessRequest(
        @NotBlank @Size(max = 128) String warehouseId,
        @NotNull WarehouseAccessLevel accessLevel,
        @Size(max = 1000) String comment,
        Boolean active) {
}
