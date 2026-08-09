package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;

/**
 * Configured warehouse grant returned as part of an administrative user projection.
 *
 * @param warehouseId canonical warehouse identifier
 * @param accessLevel configured access level before role-wide restrictions
 * @param comment optional administrative note attached to the grant
 * @param active whether the configured grant is currently active
 */
public record WarehouseAccessDto(
        String warehouseId,
        WarehouseAccessLevel accessLevel,
        String comment,
        boolean active) {
}
