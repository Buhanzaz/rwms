package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSource;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepairSourceRepository
    extends JpaRepository<InventoryRepairSource, UUID> {
  Optional<InventoryRepairSource> findByInventoryIdAndFindingId(UUID inventoryId, UUID findingId);
  Optional<InventoryRepairSource> findByRepairId(UUID repairId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select value from InventoryRepairSource value
      where value.inventoryId = :inventoryId and value.findingId = :findingId
      """)
  Optional<InventoryRepairSource> findBySourceForUpdate(
      @Param("inventoryId") UUID inventoryId, @Param("findingId") UUID findingId);
}
