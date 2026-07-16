package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkerCredentialRequest(
        @NotBlank @Size(max = 128) String warehouseId,
        @NotBlank @Size(max = 128) String appLogin,
        @NotBlank @Size(min = 8, max = 200) String password) {
}
