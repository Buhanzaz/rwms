package dev.buhanzaz.rwms.logistics.pricing.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;
import java.util.UUID;

/**
 * Current informational prices for one exact cabin set; this read does not book or freeze terms.
 */
public record CabinRentalPricesResponse(UUID warehouseId, long pricingVersion, List<Price> cabins) {
  /** One current cabin classification and the monthly tariff determined by those UUIDs. */
  public record Price(
      UUID rentalItemId,
      long rentalItemVersion,
      UUID rentalTypeId,
      UUID categoryId,
      @JsonFormat(shape = JsonFormat.Shape.STRING) long monthlyPriceRubles) {}
}
