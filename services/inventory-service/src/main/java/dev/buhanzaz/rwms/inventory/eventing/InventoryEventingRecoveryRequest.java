package dev.buhanzaz.rwms.inventory.eventing;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Versioned administrator review that releases one intact terminal delivery record. */
public record InventoryEventingRecoveryRequest(
    @NotNull @Min(0) Long expectedReviewVersion, @NotBlank String reason) {}
