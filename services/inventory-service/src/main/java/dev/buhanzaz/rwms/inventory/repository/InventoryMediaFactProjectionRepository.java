package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryMediaFactProjection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryMediaFactProjectionRepository
    extends JpaRepository<InventoryMediaFactProjection, InventoryMediaFactProjection.Key> {
  Optional<InventoryMediaFactProjection> findByMediaIdAndGeneration(UUID mediaId, long generation);

  Optional<InventoryMediaFactProjection>
      findByMediaIdAndGenerationAndOwnerTypeAndOwnerIdAndWarehouseIdAndMediaStatus(
          UUID mediaId,
          long generation,
          String ownerType,
          UUID ownerId,
          UUID warehouseId,
          String mediaStatus);
}
