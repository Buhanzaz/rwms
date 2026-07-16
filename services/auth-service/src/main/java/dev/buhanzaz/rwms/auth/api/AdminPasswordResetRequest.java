package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminPasswordResetRequest(
        @NotBlank @Size(min = 8, max = 200) String password,
        @NotNull Integer expectedVersion) {
}
