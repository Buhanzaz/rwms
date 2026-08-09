package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventorySourceAttachment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory source attachment persistence.
 */
public interface InventorySourceAttachmentRepository
    extends JpaRepository<InventorySourceAttachment, UUID> {
  Optional<InventorySourceAttachment> findByInventoryIdAndFindingId(
      UUID inventoryId, UUID findingId);
}
