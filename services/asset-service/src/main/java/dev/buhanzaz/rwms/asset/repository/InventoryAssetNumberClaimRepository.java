package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetNumberClaim;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory asset number claim persistence.
 */
public interface InventoryAssetNumberClaimRepository
    extends JpaRepository<InventoryAssetNumberClaim, UUID> {
  boolean existsByWarehouseIdAndIdentityMatchKey(UUID warehouseId, String identityMatchKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select value from InventoryAssetNumberClaim value
      where value.warehouseId = :warehouseId and value.identityMatchKey = :key
      """)
  Optional<InventoryAssetNumberClaim> findByWarehouseIdAndIdentityMatchKeyForUpdate(
      @Param("warehouseId") UUID warehouseId, @Param("key") String key);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select value from InventoryAssetNumberClaim value
      where value.inventoryId = :inventoryId and value.findingId = :findingId
      """)
  Optional<InventoryAssetNumberClaim> findBySourceForUpdate(
      @Param("inventoryId") UUID inventoryId, @Param("findingId") UUID findingId);
}
