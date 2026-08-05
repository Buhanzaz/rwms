package dev.buhanzaz.rwms.warehouse.repository;

import dev.buhanzaz.rwms.warehouse.domain.WarehouseTimeZoneHistory;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseTimeZoneHistoryRepository
    extends JpaRepository<WarehouseTimeZoneHistory, UUID> {
  Optional<WarehouseTimeZoneHistory>
      findFirstByWarehouseIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
          UUID warehouseId, OffsetDateTime asOf);

  @Query(
      """
      select entry
        from WarehouseTimeZoneHistory entry
       where entry.warehouseId in :warehouseIds
         and entry.effectiveFrom <= :asOf
         and entry.effectiveFrom = (
           select max(candidate.effectiveFrom)
             from WarehouseTimeZoneHistory candidate
            where candidate.warehouseId = entry.warehouseId
              and candidate.effectiveFrom <= :asOf)
      """)
  List<WarehouseTimeZoneHistory> findEffectiveAt(
      @Param("warehouseIds") Collection<UUID> warehouseIds, @Param("asOf") OffsetDateTime asOf);
}
