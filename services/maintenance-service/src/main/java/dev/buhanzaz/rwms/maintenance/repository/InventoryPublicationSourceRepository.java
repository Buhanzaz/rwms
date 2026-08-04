package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryPublicationSourceRepository
    extends JpaRepository<InventoryPublicationSource, InventoryPublicationSourceId> {
  Optional<InventoryPublicationSource> findByEstimateId(UUID estimateId);

  Optional<InventoryPublicationSource> findByRepairId(UUID repairId);

  List<InventoryPublicationSource> findAllByAssetIdOrderByCreatedAtAsc(UUID assetId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryPublicationSource value where value.id = :id")
  Optional<InventoryPublicationSource> findByIdForUpdate(
      @Param("id") InventoryPublicationSourceId id);
}
