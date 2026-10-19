package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.api.EquipmentPricingCatalogResponse;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.mapper.EquipmentCatalogItemMapper;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Supplies the live furniture catalog without owning tariffs or reserving stock. */
@Service
@RequiredArgsConstructor
public class EquipmentPricingReferenceService {
  private final EquipmentCatalogItemRepository equipment;
  private final EquipmentCatalogItemMapper mapper;

  /** Includes inactive and out-of-stock furniture; deleted identities disappear from this read. */
  @Transactional(readOnly = true)
  public EquipmentPricingCatalogResponse catalog() {
    return new EquipmentPricingCatalogResponse(
        equipment.findAllByOrderByNameAscIdAsc().stream()
            .filter(item -> item.getCategory() == EquipmentCategory.FURNITURE)
            .map(mapper::toPricingValue)
            .toList());
  }
}
