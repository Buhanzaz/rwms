package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetOutcomeWatermark;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locks the per-cabin completed-inventory high-water mark. */
public interface InventoryAssetOutcomeWatermarkRepository
    extends JpaRepository<InventoryAssetOutcomeWatermark, UUID> {
  /** Locks the current source ordering evidence for one cabin when it already exists. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select watermark
      from InventoryAssetOutcomeWatermark watermark
      where watermark.assetId = :assetId
      """)
  Optional<InventoryAssetOutcomeWatermark> findByAssetIdForUpdate(@Param("assetId") UUID assetId);
}
