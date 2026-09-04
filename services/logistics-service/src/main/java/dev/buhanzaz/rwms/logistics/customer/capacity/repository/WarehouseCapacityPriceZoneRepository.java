package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads tariff polygons independently from route-capacity jobs, shifts, and isochrone tiers. */
public interface WarehouseCapacityPriceZoneRepository
    extends JpaRepository<WarehouseCapacityPriceZone, UUID> {
  /** Returns all special-price zones in stable source identity order. */
  @Query(
      """
      select zone from WarehouseCapacityPriceZone zone
      where zone.snapshot.warehouseId = :warehouseId
      order by zone.sourceZoneId asc
      """)
  List<WarehouseCapacityPriceZone> findTariffZones(@Param("warehouseId") UUID warehouseId);
}
