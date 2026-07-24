package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservation;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservationState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderEquipmentReservationRepository
    extends JpaRepository<OrderEquipmentReservation, UUID> {
  List<OrderEquipmentReservation> findAllByOrderIdAndStateOrderByEquipmentId(
      UUID orderId, OrderEquipmentReservationState state);

  @Query(
      """
      select reservation
      from OrderEquipmentReservation reservation
      where reservation.warehouseId = :warehouseId
        and reservation.equipmentId in :equipmentIds
        and reservation.state = :state
      order by reservation.equipmentId, reservation.orderId
      """)
  List<OrderEquipmentReservation> findAllActiveByWarehouseAndEquipmentIds(
      @Param("warehouseId") UUID warehouseId,
      @Param("equipmentIds") Collection<UUID> equipmentIds,
      @Param("state") OrderEquipmentReservationState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select reservation
      from OrderEquipmentReservation reservation
      where reservation.orderId = :orderId
        and reservation.state = :state
      order by reservation.equipmentId, reservation.id
      """)
  List<OrderEquipmentReservation> findAllActiveForUpdate(
      @Param("orderId") UUID orderId,
      @Param("state") OrderEquipmentReservationState state);

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
