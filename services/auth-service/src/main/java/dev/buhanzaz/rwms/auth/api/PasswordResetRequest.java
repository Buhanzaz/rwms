package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Write-only password replacement command for a worker credential.
 *
 * @param password replacement plaintext password, validated before the service hashes it
 */
public record PasswordResetRequest(@NotBlank @Size(min = 8, max = 200) String password) {
}
