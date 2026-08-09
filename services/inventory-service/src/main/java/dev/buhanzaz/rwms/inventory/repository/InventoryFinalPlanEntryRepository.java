package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory final plan entry persistence.
 */
public interface InventoryFinalPlanEntryRepository
    extends JpaRepository<InventoryFinalPlanEntry, InventoryFinalPlanEntry.Key> {
  List<InventoryFinalPlanEntry> findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
      UUID inventoryId, long finalPlanVersion);

  Optional<InventoryFinalPlanEntry> findByInventoryIdAndFinalPlanVersionAndFindingId(
      UUID inventoryId, long finalPlanVersion, UUID findingId);
}
