package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.RentalItem;
import java.util.List;
import java.util.UUID;

/**
 * Exact current cabin-to-classification references for one warehouse, without passport contents or
 * rental pricing decisions. This read does not check availability or create a hold.
 */
public record CabinPricingReferencesResponse(UUID warehouseId, List<Reference> cabins) {

  /**
   * Stable catalog identities of {@link RentalItem}; the cabin version fences the observed
   * classification and must not be interpreted as a tariff revision.
   */
  public record Reference(
      UUID rentalItemId, long rentalItemVersion, UUID rentalTypeId, UUID categoryId) {}
}
