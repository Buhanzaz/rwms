package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.util.List;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        int version,
        String username,
        String firstName,
        String lastName,
        String email,
        String timeZoneId,
        boolean active,
        UserGlobalRole globalRole,
        List<WarehouseAccessDto> warehouseAccesses) {
}
