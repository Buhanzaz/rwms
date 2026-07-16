package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;

public record EffectiveWarehouseAccessDto(String warehouseId, WarehouseAccessLevel level) {
}
