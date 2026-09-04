package dev.buhanzaz.rwms.logistics.vehicle.repository;

import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignment;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for logistics-owned vehicle reservation and placement history. */
public interface VehicleOperationalAssignmentRepository
    extends JpaRepository<VehicleOperationalAssignment, UUID> {
  /** Locks all assignment rows for one transfer after their vehicle advisory locks are held. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select assignment
      from VehicleOperationalAssignment assignment
      where assignment.transferId = :transferId
      order by assignment.id
      """)
  List<VehicleOperationalAssignment> findAllForUpdateByTransferId(
      @Param("transferId") UUID transferId);

  /** Returns stable vehicle lock keys without taking row locks. */
  @Query(
      """
      select assignment.vehicleId
      from VehicleOperationalAssignment assignment
      where assignment.transferId = :transferId
      order by assignment.vehicleId
      """)
  List<UUID> findVehicleIdsByTransferId(@Param("transferId") UUID transferId);

  List<VehicleOperationalAssignment> findAllByTransferIdOrderById(UUID transferId);

  /** Locks the complete vehicle chain after its stable transaction advisory lock is held. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select assignment
      from VehicleOperationalAssignment assignment
      where assignment.vehicleId = :vehicleId
      order by assignment.travelStartsAt, assignment.id
      """)
  List<VehicleOperationalAssignment> findAllForUpdateByVehicleId(
      @Param("vehicleId") UUID vehicleId);

  /**
   * Returns live chain facts for every vehicle whose history ever touched the queried warehouse.
   * Terminal rows seed the vehicle ancestry subquery but never appear in the payload.
   */
  @Query(
      """
      select assignment
      from VehicleOperationalAssignment assignment
      where assignment.vehicleId in (
          select history.vehicleId
          from VehicleOperationalAssignment history
          where history.sourceWarehouseId = :warehouseId
             or history.destinationWarehouseId = :warehouseId
      )
        and assignment.status in (:planned, :inTransit, :active)
        and assignment.travelStartsAt < :windowEnd
      order by assignment.vehicleId, assignment.travelStartsAt, assignment.id
      """)
  List<VehicleOperationalAssignment> findLiveVehicleChainBefore(
      @Param("warehouseId") UUID warehouseId,
      @Param("windowEnd") OffsetDateTime windowEnd,
      @Param("planned") VehicleOperationalAssignmentStatus planned,
      @Param("inTransit") VehicleOperationalAssignmentStatus inTransit,
      @Param("active") VehicleOperationalAssignmentStatus active);
}
