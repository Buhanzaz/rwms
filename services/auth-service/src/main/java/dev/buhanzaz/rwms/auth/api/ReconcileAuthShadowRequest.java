package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReconcileAuthShadowRequest(
        @Min(0) long expectedCheckpointVersion,
        @NotBlank @Size(max = 500) String reason) {}
