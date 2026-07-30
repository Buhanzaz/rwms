package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateUserRequest(
        @NotBlank @Size(max = 128) String username,
        @NotBlank @Size(min = 8, max = 200) String password,
        @Size(max = 128) String firstName,
        @Size(max = 128) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 64) String timeZoneId,
        @NotNull UserGlobalRole globalRole,
        Boolean active,
        Boolean mobileAppAccess,
        Boolean rentalAccess,
        List<@Valid WarehouseAccessRequest> warehouseAccesses) {

    public CreateUserRequest(
            String username,
            String password,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            Boolean active,
            List<WarehouseAccessRequest> warehouseAccesses) {
        this(
                username,
                password,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                null,
                null,
                warehouseAccesses);
    }

    public CreateUserRequest(
            String username,
            String password,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            Boolean active,
            Boolean mobileAppAccess,
            List<WarehouseAccessRequest> warehouseAccesses) {
        this(
                username,
                password,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                mobileAppAccess,
                null,
                warehouseAccesses);
    }
}
