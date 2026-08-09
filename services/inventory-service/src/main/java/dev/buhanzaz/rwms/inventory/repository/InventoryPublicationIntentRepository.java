package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory publication intent persistence.
 */
public interface InventoryPublicationIntentRepository
    extends JpaRepository<InventoryPublicationIntent, UUID> {
  Optional<InventoryPublicationIntent> findByInventoryIdAndFindingId(
      UUID inventoryId, UUID findingId);

  List<InventoryPublicationIntent> findAllByInventoryIdOrderByFindingId(UUID inventoryId);

  List<InventoryPublicationIntent> findAllByFindingIdInOrderByFindingId(Set<UUID> findingIds);

  List<InventoryPublicationIntent> findAllByInventoryIdInOrderByInventoryIdAscFindingIdAsc(
      Set<UUID> inventoryIds);

  List<InventoryPublicationIntent> findTop20ByStateOrderByUpdatedAtAsc(PublicationState state);
}
