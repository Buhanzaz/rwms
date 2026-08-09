package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryMembershipMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory membership movement persistence.
 */
public interface InventoryMembershipMovementRepository
    extends JpaRepository<InventoryMembershipMovement, UUID> {
  List<InventoryMembershipMovement> findAllByInventoryIdOrderByOccurredAtAscIdAsc(
      UUID inventoryId);
}
