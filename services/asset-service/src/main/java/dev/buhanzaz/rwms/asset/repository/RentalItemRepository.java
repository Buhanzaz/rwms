package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RentalItemRepository extends JpaRepository<RentalItem, UUID> {
  boolean existsByWarehouseIdAndIdentityMatchKey(UUID warehouseId, String identityMatchKey);
  boolean existsByWarehouseIdAndIdentityMatchKeyAndIdNot(
      UUID warehouseId, String identityMatchKey, UUID id);
  Optional<RentalItem> findByWarehouseIdAndIdentityMatchKey(
      UUID warehouseId, String identityMatchKey);
  List<RentalItem> findAllByWarehouseIdOrderByNumber(UUID warehouseId);
  Optional<RentalItem> findByIdAndWarehouseId(UUID id, UUID warehouseId);
  List<RentalItem> findAllByWarehouseIdAndStatusInOrderByIdentityMatchKeyAscIdAsc(
      UUID warehouseId, Collection<RentalItemStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select item from RentalItem item where item.id = :id")
  Optional<RentalItem> findByIdForUpdate(@Param("id") UUID id);

  @Query(
      value =
          """
          select item
          from RentalItem item
          where item.warehouseId = :warehouseId
            and (
              (
                item.status in :reservableStatuses
                and not exists (
                  select reservation.id
                  from OrderUnitReservation reservation
                  where reservation.rentalItemId = item.id
                    and reservation.state = :activeState
                )
                and not exists (
                  select lease.id
                  from OperationLease lease
                  where lease.rentalItemId = item.id
                    and lease.state = dev.buhanzaz.rwms.asset.domain.OperationLeaseState.ACTIVE
                    and lease.expiresAt > current_timestamp
                )
              )
              or exists (
                select reservation.id
                from OrderUnitReservation reservation
                where reservation.rentalItemId = item.id
                  and reservation.orderId = :orderId
                  and reservation.state = :activeState
              )
            )
            and (
              :search = ''
              or upper(item.number) like concat('%', :search, '%')
              or upper(coalesce(item.rentalType, '')) like concat('%', :search, '%')
              or upper(coalesce(item.category, '')) like concat('%', :search, '%')
              or upper(coalesce(item.characteristics, '')) like concat('%', :search, '%')
            )
          """,
      countQuery =
          """
          select count(item)
          from RentalItem item
          where item.warehouseId = :warehouseId
            and (
              (
                item.status in :reservableStatuses
                and not exists (
                  select reservation.id
                  from OrderUnitReservation reservation
                  where reservation.rentalItemId = item.id
                    and reservation.state = :activeState
                )
                and not exists (
                  select lease.id
                  from OperationLease lease
                  where lease.rentalItemId = item.id
                    and lease.state = dev.buhanzaz.rwms.asset.domain.OperationLeaseState.ACTIVE
                    and lease.expiresAt > current_timestamp
                )
              )
              or exists (
                select reservation.id
                from OrderUnitReservation reservation
                where reservation.rentalItemId = item.id
                  and reservation.orderId = :orderId
                  and reservation.state = :activeState
              )
            )
            and (
              :search = ''
              or upper(item.number) like concat('%', :search, '%')
              or upper(coalesce(item.rentalType, '')) like concat('%', :search, '%')
              or upper(coalesce(item.category, '')) like concat('%', :search, '%')
              or upper(coalesce(item.characteristics, '')) like concat('%', :search, '%')
            )
          """)
  Page<RentalItem> findOrderCandidates(
      @Param("orderId") UUID orderId,
      @Param("warehouseId") UUID warehouseId,
      @Param("reservableStatuses") Collection<RentalItemStatus> reservableStatuses,
      @Param("activeState") OrderUnitReservationState activeState,
      @Param("search") String search,
      Pageable pageable);
}
