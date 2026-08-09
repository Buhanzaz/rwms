package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryStatisticsLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory statistics line persistence.
 */
public interface InventoryStatisticsLineRepository
    extends JpaRepository<InventoryStatisticsLine, UUID> {
  List<InventoryStatisticsLine> findAllByInventoryIdOrderByLineTypeAscCatalogVersionIdAscCatalogNodeIdAscNormalizedDescriptionAscUnitAscUnitPriceMinorAsc(
      UUID inventoryId);
}
