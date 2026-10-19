package dev.buhanzaz.rwms.logistics.pricing.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Complete live taxonomy with one logistics-owned monthly tariff for every type/category pair. */
public record RentalPricingSettingsResponse(
    long version, List<Type> types, OffsetDateTime updatedAt) {
  /** Includes unused/inactive asset-owned types; no client-maintained list defines this table. */
  public record Type(UUID rentalTypeId, String name, boolean active, List<Category> categories) {}

  /** An exact nonnegative whole-ruble monthly price, serialized as a string without JS rounding. */
  public record Category(
      UUID categoryId,
      String name,
      boolean active,
      @JsonFormat(shape = JsonFormat.Shape.STRING) long monthlyPriceRubles) {}
}
