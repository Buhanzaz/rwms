package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureOperation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local inventory asset capture operation persistence.
 */
public interface InventoryAssetCaptureOperationRepository
    extends JpaRepository<InventoryAssetCaptureOperation, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryAssetCaptureOperation value where value.operationId = :id")
  Optional<InventoryAssetCaptureOperation> findByIdForUpdate(@Param("id") UUID id);
}
