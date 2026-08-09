package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocation;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data persistence boundary for RepairPlaceAllocation; business transitions remain in the owning service. */
public interface RepairPlaceAllocationRepository
    extends JpaRepository<RepairPlaceAllocation, UUID> {
  List<RepairPlaceAllocation> findAllByWarehouseIdOrderByCreatedAtAscIdAsc(UUID warehouseId);

  List<RepairPlaceAllocation>
      findAllByWarehouseIdAndStateInOrderByCreatedAtAscIdAsc(
          UUID warehouseId, Collection<RepairPlaceAllocationState> states);

  long countByWarehouseIdAndStateIn(
      UUID warehouseId, Collection<RepairPlaceAllocationState> states);

  long countByWarehouseIdAndState(UUID warehouseId, RepairPlaceAllocationState state);

  boolean existsByWarehouseIdAndRepairIdAndState(
      UUID warehouseId, UUID repairId, RepairPlaceAllocationState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select allocation from RepairPlaceAllocation allocation"
          + " where allocation.repairId = :repairId"
          + " and allocation.state <> "
          + "dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState.RELEASED")
  Optional<RepairPlaceAllocation> findByRepairIdForUpdate(@Param("repairId") UUID repairId);
}
