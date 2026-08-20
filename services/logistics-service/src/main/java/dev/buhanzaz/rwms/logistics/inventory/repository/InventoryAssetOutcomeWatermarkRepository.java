package dev.buhanzaz.rwms.logistics.inventory.repository;

import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryAssetOutcomeWatermark;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locked persistence boundary for per-cabin completed-inventory fences. */
public interface InventoryAssetOutcomeWatermarkRepository
    extends JpaRepository<InventoryAssetOutcomeWatermark, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select watermark from InventoryAssetOutcomeWatermark watermark where watermark.assetId in :assetIds order by watermark.assetId")
  List<InventoryAssetOutcomeWatermark> findAllForUpdate(
      @Param("assetIds") Collection<UUID> assetIds);
}
