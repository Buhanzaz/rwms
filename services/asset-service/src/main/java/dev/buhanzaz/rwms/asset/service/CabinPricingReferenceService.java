package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.api.CabinPricingCatalogResponse;
import dev.buhanzaz.rwms.asset.api.CabinPricingReferencesResponse;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailabilityRequest;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.mapper.CabinPricingReferenceMapper;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides current classification facts to the rental-price owner. Catalog reads include every
 * existing type and category, including values with no cabins; no price is stored or inferred here.
 */
@Service
@RequiredArgsConstructor
public class CabinPricingReferenceService {
  private final CabinCatalogItemRepository catalog;
  private final RentalItemRepository rentalItems;
  private final CabinPricingReferenceMapper mapper;

  /** Reads both classification dimensions from one consistent asset database snapshot. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public CabinPricingCatalogResponse catalog() {
    return new CabinPricingCatalogResponse(
        catalog.findAllByKindOrderBySortOrderAscIdAsc(CabinCatalogKind.TYPE).stream()
            .map(mapper::toValue)
            .toList(),
        catalog.findAllByKindOrderBySortOrderAscIdAsc(CabinCatalogKind.CATEGORY).stream()
            .map(mapper::toValue)
            .toList());
  }

  /**
   * Returns the complete requested set or fails without disclosing a foreign-warehouse cabin.
   * Duplicate identities are rejected instead of silently changing the requested set.
   */
  @Transactional(readOnly = true)
  public CabinPricingReferencesResponse references(CabinAvailabilityRequest request) {
    if (request == null
        || request.warehouseId() == null
        || request.rentalItemIds() == null
        || request.rentalItemIds().isEmpty()
        || request.rentalItemIds().size() > 100
        || request.rentalItemIds().stream().anyMatch(Objects::isNull)
        || new HashSet<>(request.rentalItemIds()).size() != request.rentalItemIds().size()) {
      throw new IllegalArgumentException("One to 100 distinct cabin identities are required");
    }
    List<RentalItem> cabins = rentalItems.findAllById(request.rentalItemIds());
    if (cabins.size() != request.rentalItemIds().size()
        || cabins.stream().anyMatch(item -> !request.warehouseId().equals(item.getWarehouseId()))) {
      throw new AssetNotFoundException("Cabin pricing references were not found");
    }
    return new CabinPricingReferencesResponse(
        request.warehouseId(),
        cabins.stream()
            .sorted(Comparator.comparing(RentalItem::getId))
            .map(mapper::toReference)
            .toList());
  }
}
