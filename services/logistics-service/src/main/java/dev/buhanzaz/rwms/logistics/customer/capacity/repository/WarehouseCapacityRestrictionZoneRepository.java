package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads warehouse restriction polygons used by customer route feasibility. */
public interface WarehouseCapacityRestrictionZoneRepository
    extends JpaRepository<WarehouseCapacityRestrictionZone, UUID> {
  /** Returns all restriction facts in stable source identity order. */
  @Query(
      """
      select zone from WarehouseCapacityRestrictionZone zone
      where zone.snapshot.warehouseId = :warehouseId
      order by zone.sourceZoneId asc
      """)
  List<WarehouseCapacityRestrictionZone> findRestrictionZones(
      @Param("warehouseId") UUID warehouseId);
}
