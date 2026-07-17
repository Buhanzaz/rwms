package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryExpectedItemRepository extends JpaRepository<InventoryExpectedItem, UUID> {
  List<InventoryExpectedItem> findAllByFindingIdInOrderByFindingId(Set<UUID> findingIds);
}
