package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeWatermark;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for the latest completed-inventory source per maintenance asset. */
public interface InventoryAuthoritativeOutcomeWatermarkRepository
    extends JpaRepository<InventoryAuthoritativeOutcomeWatermark, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select value from InventoryAuthoritativeOutcomeWatermark value"
          + " where value.assetId = :assetId")
  Optional<InventoryAuthoritativeOutcomeWatermark> findByAssetIdForUpdate(
      @Param("assetId") UUID assetId);
}
