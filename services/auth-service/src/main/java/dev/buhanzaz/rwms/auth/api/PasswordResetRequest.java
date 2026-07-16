package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(@NotBlank @Size(min = 8, max = 200) String password) {
}
