package dev.buhanzaz.rwms.logistics.order.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Confirms the exact order revision; source and identity are derived exclusively by the server.
 */
public record ConfirmOrderPaymentRequest(@NotNull @Min(0) Long expectedVersion) {}
