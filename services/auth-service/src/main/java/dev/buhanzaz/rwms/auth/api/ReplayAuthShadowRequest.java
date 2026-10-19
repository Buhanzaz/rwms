package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Operator-confirmed request to rebuild the authorization shadow under a durable operation ID.
 *
 * @param operationId caller-provided identifier used to audit and fence the rebuild operation
 * @param reason operator-supplied audit reason for the rebuild
 */
public record ReplayAuthShadowRequest(
        @NotNull UUID operationId,
        @NotBlank @Size(max = 500) String reason) {}
