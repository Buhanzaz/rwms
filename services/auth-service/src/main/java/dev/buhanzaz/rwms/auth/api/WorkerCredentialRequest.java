package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Write-only worker credential configuration supplied by a trusted internal caller.
 *
 * <p>The password must never be included in a response, event payload, or log.
 *
 * @param warehouseId warehouse to which the worker credential is bound
 * @param appLogin canonical login name for the worker application
 * @param password write-only plaintext password to hash before persistence
 */
public record WorkerCredentialRequest(
        @NotBlank @Size(max = 128) String warehouseId,
        @NotBlank @Size(max = 128) String appLogin,
        @NotBlank @Size(min = 8, max = 200) String password) {
}
