package dev.buhanzaz.rwms.logistics.pricing.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Version-fenced replacement of one cabin pair or furniture unit tariff; zero clears its override. */
public record UpdateRentalPriceRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Pattern(regexp = "0|[1-9][0-9]{0,18}") String monthlyPriceRubles) {}
