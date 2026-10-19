package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import java.util.List;
import java.util.UUID;

/**
 * Current asset-owned classification identities used by logistics-owned rental tariffs. Deleted
 * catalog values are absent; inactive values retain their identity and explicit availability flag.
 */
public record CabinPricingCatalogResponse(List<Value> types, List<Value> categories) {

  /**
   * The minimal pricing classification reference for {@link CabinCatalogItem}; names are labels,
   * never tariff keys.
   */
  public record Value(UUID id, String name, boolean active) {}
}
