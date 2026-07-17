package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryValidationItem;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryValidationItemRepository
    extends JpaRepository<InventoryValidationItem, InventoryValidationItem.Key> {
  long deleteByInventoryId(UUID inventoryId);
}
