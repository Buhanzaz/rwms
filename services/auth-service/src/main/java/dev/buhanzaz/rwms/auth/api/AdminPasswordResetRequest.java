package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Administrative password replacement command fenced by the user's expected aggregate version.
 *
 * <p>The password is write-only transport data and must never be echoed in a response or log.
 *
 * @param password replacement plaintext password, validated before the service hashes it
 * @param expectedVersion current aggregate version expected by the administrator
 */
public record AdminPasswordResetRequest(
        @NotBlank @Size(min = 8, max = 200) String password,
        @NotNull Integer expectedVersion) {
}
