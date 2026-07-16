package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ReplayAuthShadowRequest(
        @NotNull UUID operationId,
        @NotBlank @Size(max = 500) String reason) {}
