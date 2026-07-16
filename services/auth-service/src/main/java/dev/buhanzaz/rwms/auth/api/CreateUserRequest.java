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
        List<@Valid WarehouseAccessRequest> warehouseAccesses) {
}
