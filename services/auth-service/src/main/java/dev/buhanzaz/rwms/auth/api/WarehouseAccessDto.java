package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;

public record WarehouseAccessDto(
        String warehouseId,
        WarehouseAccessLevel accessLevel,
        String comment,
        boolean active) {
}
