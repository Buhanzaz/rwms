package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Anonymous customer account registration command.
 *
 * <p>Passwords remain write-only transport values and are compared and encoded only by the owning
 * registration service.
 *
 * @param username requested login, restricted to a portable native-client character set
 * @param password plaintext password to encode
 * @param passwordConfirmation repeated plaintext password
 */
public record CustomerRegistrationRequest(
        @NotBlank
                @Size(min = 3, max = 64)
                @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$")
                String username,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(min = 8, max = 128) String passwordConfirmation) {

    /** Normalizes harmless surrounding login whitespace before bean validation and uniqueness checks. */
    public CustomerRegistrationRequest {
        username = username == null ? null : username.trim();
    }
}
