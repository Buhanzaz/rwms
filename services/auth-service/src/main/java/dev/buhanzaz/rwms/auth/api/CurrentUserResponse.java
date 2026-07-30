package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        String username,
        String displayName,
        String firstName,
        String lastName,
        String email,
        PrincipalType principalType,
        UserGlobalRole globalRole,
        boolean rentalAccess,
        boolean warehouseAccessAll,
        List<EffectiveWarehouseAccessDto> warehouseAccesses) {

    public CurrentUserResponse(
            UUID id,
            String username,
            String displayName,
            String firstName,
            String lastName,
            String email,
            PrincipalType principalType,
            UserGlobalRole globalRole,
            boolean warehouseAccessAll,
            List<EffectiveWarehouseAccessDto> warehouseAccesses) {
        this(
                id,
                username,
                displayName,
                firstName,
                lastName,
                email,
                principalType,
                globalRole,
                false,
                warehouseAccessAll,
                warehouseAccesses);
    }
}
