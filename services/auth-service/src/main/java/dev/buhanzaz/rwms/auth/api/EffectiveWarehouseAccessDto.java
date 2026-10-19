package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;

/**
 * Warehouse grant after role-wide restrictions have been applied for the current user.
 *
 * <p>For example, a viewer's configured edit or manage grant is exposed as effective view access.
 *
 * @param warehouseId canonical warehouse identifier
 * @param level access level effective for the current user's role
 */
public record EffectiveWarehouseAccessDto(String warehouseId, WarehouseAccessLevel level) {
}
