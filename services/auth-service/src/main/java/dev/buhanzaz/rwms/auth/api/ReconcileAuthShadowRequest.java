package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Operator-confirmed request to reconcile one authorization shadow from its event stream.
 *
 * <p>The expected checkpoint version prevents a recovery command from overwriting newer progress.
 *
 * @param expectedCheckpointVersion recovery checkpoint version observed by the operator
 * @param reason operator-supplied audit reason for the reconciliation
 */
public record ReconcileAuthShadowRequest(
        @Min(0) long expectedCheckpointVersion,
        @NotBlank @Size(max = 500) String reason) {}
