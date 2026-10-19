package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityIsochroneTariff;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads the ordered hourly delivery boundary and price tiers for one warehouse. */
public interface WarehouseCapacityIsochroneTariffRepository
    extends JpaRepository<WarehouseCapacityIsochroneTariff, UUID> {
  /** Returns the complete active tariff ordered from the nearest to the farthest isochrone. */
  @Query(
      """
      select tariff from WarehouseCapacityIsochroneTariff tariff
      where tariff.snapshot.warehouseId = :warehouseId
      order by tariff.travelMinutes asc
      """)
  List<WarehouseCapacityIsochroneTariff> findTariffs(@Param("warehouseId") UUID warehouseId);
}
