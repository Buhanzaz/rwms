package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @NotNull Integer expectedVersion,
        @NotBlank @Size(max = 128) String username,
        @Size(max = 128) String firstName,
        @Size(max = 128) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 64) String timeZoneId,
        @NotNull Boolean active,
        @NotNull UserGlobalRole globalRole,
        Boolean mobileAppAccess,
        Boolean rentalAccess) {

    public UpdateUserRequest(
            Integer expectedVersion,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            Boolean active,
            UserGlobalRole globalRole) {
        this(
                expectedVersion,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                active,
                globalRole,
                null,
                null);
    }

    public UpdateUserRequest(
            Integer expectedVersion,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            Boolean active,
            UserGlobalRole globalRole,
            Boolean mobileAppAccess) {
        this(
                expectedVersion,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                active,
                globalRole,
                mobileAppAccess,
                null);
    }
}
