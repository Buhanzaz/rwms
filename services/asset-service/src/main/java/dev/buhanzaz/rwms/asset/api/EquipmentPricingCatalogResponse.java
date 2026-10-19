package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import java.util.List;
import java.util.UUID;

/**
 * Live furniture identities for logistics-owned monthly tariffs; this read contains no prices.
 */
public record EquipmentPricingCatalogResponse(List<Value> items) {
  /**
   * Pricing identity and current label of {@link EquipmentCatalogItem}, including inactive items.
   */
  public record Value(UUID id, String name, boolean active) {}
}
