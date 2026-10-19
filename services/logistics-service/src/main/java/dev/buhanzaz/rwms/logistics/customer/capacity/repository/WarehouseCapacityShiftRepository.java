package dev.buhanzaz.rwms.logistics.customer.capacity.repository;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads anonymous simulator driver shifts for one warehouse-local capacity calculation. */
public interface WarehouseCapacityShiftRepository extends JpaRepository<WarehouseCapacityShift, UUID> {
  @Query(
      """
      select shift from WarehouseCapacityShift shift
      where shift.snapshot.warehouseId = :warehouseId
        and shift.deliveryDate = :date
      order by shift.shiftStart asc, shift.shiftEnd asc, shift.sourceShiftId asc
      """)
  List<WarehouseCapacityShift> findCapacityShifts(
      @Param("warehouseId") UUID warehouseId, @Param("date") LocalDate date);
}
