package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetSource;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory asset source persistence.
 */
public interface InventoryAssetSourceRepository
    extends JpaRepository<InventoryAssetSource, InventoryAssetSourceId> {
  Optional<InventoryAssetSource> findById(InventoryAssetSourceId id);
}
