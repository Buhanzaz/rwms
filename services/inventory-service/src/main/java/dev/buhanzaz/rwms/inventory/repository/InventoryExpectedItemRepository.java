package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory expected item persistence.
 */
public interface InventoryExpectedItemRepository extends JpaRepository<InventoryExpectedItem, UUID> {
  List<InventoryExpectedItem> findAllByFindingIdInOrderByFindingId(Set<UUID> findingIds);

  @Query(
      "select coalesce(max(item.itemOrder), -1) from InventoryExpectedItem item where item.inventoryId = :inventoryId")
  int maximumOrder(@Param("inventoryId") UUID inventoryId);
}
