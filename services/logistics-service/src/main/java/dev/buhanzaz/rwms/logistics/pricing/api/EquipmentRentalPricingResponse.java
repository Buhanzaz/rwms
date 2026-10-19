package dev.buhanzaz.rwms.logistics.pricing.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Live asset furniture labels joined with one logistics-owned monthly tariff revision. */
public record EquipmentRentalPricingResponse(
    long version, List<Item> items, OffsetDateTime updatedAt) {
  /** Monthly whole-RUB price of one unit, encoded exactly without JavaScript number rounding. */
  public record Item(
      UUID equipmentId,
      String name,
      boolean active,
      @JsonFormat(shape = JsonFormat.Shape.STRING) long monthlyPriceRubles) {}
}
