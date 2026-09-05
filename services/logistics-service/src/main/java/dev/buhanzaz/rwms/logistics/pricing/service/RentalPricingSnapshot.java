package dev.buhanzaz.rwms.logistics.pricing.service;

import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingRate;
import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingSettings;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Detached revision of {@link RentalPricingSettings}; omitted catalog pairs have a zero tariff. */
public record RentalPricingSnapshot(
    long version, List<Rate> rates, UUID updatedBySubjectId, OffsetDateTime updatedAt) {
  public RentalPricingSnapshot {
    rates = List.copyOf(rates);
  }

  /** Exact whole-ruble value of {@link RentalPricingRate}, independent of catalog display names. */
  public record Rate(UUID rentalTypeId, UUID categoryId, long monthlyPriceRubles) {}
}
