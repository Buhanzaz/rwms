package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceId;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetSourceOperation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryAssetSourceOperationRepository
    extends JpaRepository<InventoryAssetSourceOperation, InventoryAssetSourceId> {
  @Query("select value.requestFingerprint from InventoryAssetSourceOperation value where value.id = :id")
  Optional<String> findRequestFingerprintById(@Param("id") InventoryAssetSourceId id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryAssetSourceOperation value where value.id = :id")
  Optional<InventoryAssetSourceOperation> findByIdForUpdate(
      @Param("id") InventoryAssetSourceId id);
}
