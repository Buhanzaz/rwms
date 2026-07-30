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
        boolean mobileAppAccess,
        boolean rentalAccess,
        List<WarehouseAccessDto> warehouseAccesses) {

    public AdminUserResponse(
            UUID id,
            int version,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            boolean active,
            UserGlobalRole globalRole,
            boolean mobileAppAccess,
            List<WarehouseAccessDto> warehouseAccesses) {
        this(
                id,
                version,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                active,
                globalRole,
                mobileAppAccess,
                false,
                warehouseAccesses);
    }

    public AdminUserResponse(
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
        this(
                id,
                version,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                active,
                globalRole,
                false,
                false,
                warehouseAccesses);
    }
}
