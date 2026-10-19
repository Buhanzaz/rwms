package dev.buhanzaz.rwms.logistics.pricing.service;

import java.util.Map;
import java.util.UUID;

/** Detached furniture-only tariff snapshot for an order bill; cabin lines retain their own quotes. */
public record EquipmentRentalPriceSnapshot(long version, Map<UUID, Long> equipmentRates) {
  public EquipmentRentalPriceSnapshot {
    equipmentRates = Map.copyOf(equipmentRates);
  }
}
