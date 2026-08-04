package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
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
  boolean existsByRentalTypeId(UUID rentalTypeId);

  boolean existsByDimensionId(UUID dimensionId);

  boolean existsByFinishingId(UUID finishingId);

  boolean existsByCategoryId(UUID categoryId);

  boolean existsByWarehouseIdAndIdentityMatchKey(UUID warehouseId, String identityMatchKey);
  boolean existsByWarehouseIdAndIdentityMatchKeyAndIdNot(
      UUID warehouseId, String identityMatchKey, UUID id);
  Optional<RentalItem> findByWarehouseIdAndIdentityMatchKey(
      UUID warehouseId, String identityMatchKey);
  Optional<RentalItem> findFirstByIdentityMatchKeyOrderByIdAsc(String identityMatchKey);
  List<RentalItem> findAllByWarehouseIdOrderByNumber(UUID warehouseId);
  Optional<RentalItem> findByIdAndWarehouseId(UUID id, UUID warehouseId);
  List<RentalItem> findAllByWarehouseIdAndStatusInOrderByIdentityMatchKeyAscIdAsc(
      UUID warehouseId, Collection<RentalItemStatus> statuses);

  @Query(
      """
      select item
      from RentalItem item
      where item.warehouseId = :warehouseId
        and (
          :search = ''
          or upper(item.number) like concat('%', :search, '%')
          or upper(coalesce(item.category, '')) like concat('%', :search, '%')
          or exists (
            select value.id
            from CabinCatalogItem value
            where value.id in (item.rentalTypeId, item.dimensionId, item.finishingId)
              and upper(value.name) like concat('%', :search, '%')
          )
          or exists (
            select link.id
            from RentalItemCharacteristic link, CabinCatalogItem characteristic
            where link.rentalItemId = item.id
              and characteristic.id = link.characteristicId
              and upper(characteristic.name) like concat('%', :search, '%')
          )
        )
      """)
  Page<RentalItem> findPublicPage(
      @Param("warehouseId") UUID warehouseId,
      @Param("search") String search,
      Pageable pageable);

  @Query(
      """
      select item
      from RentalItem item
      where item.warehouseId = :warehouseId
        and item.status not in :excludedStatuses
        and (
          :search = ''
          or upper(item.number) like concat('%', :search, '%')
          or upper(coalesce(item.category, '')) like concat('%', :search, '%')
          or exists (
            select value.id
            from CabinCatalogItem value
            where value.id in (item.rentalTypeId, item.dimensionId, item.finishingId)
              and upper(value.name) like concat('%', :search, '%')
          )
          or exists (
            select link.id
            from RentalItemCharacteristic link, CabinCatalogItem characteristic
            where link.rentalItemId = item.id
              and characteristic.id = link.characteristicId
              and upper(characteristic.name) like concat('%', :search, '%')
          )
        )
      """)
  Page<RentalItem> findPublicPageExcludingStatuses(
      @Param("warehouseId") UUID warehouseId,
      @Param("excludedStatuses") Collection<RentalItemStatus> excludedStatuses,
      @Param("search") String search,
      Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select item from RentalItem item where item.id = :id")
  Optional<RentalItem> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select item from RentalItem item where item.id in :ids order by item.id")
  List<RentalItem> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids);

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
                    and (
                      reservation.draftReservationExpiresAt is null
                      or reservation.draftReservationExpiresAt > :now
                    )
                )
                and not exists (
                  select lease.id
                  from OperationLease lease
                  where lease.rentalItemId = item.id
                    and lease.state = dev.buhanzaz.rwms.asset.domain.OperationLeaseState.ACTIVE
                    and lease.expiresAt > current_timestamp
                )
                and not exists (
                  select hold.id
                  from PresentationUnitHold hold
                  where hold.rentalItemId = item.id
                    and hold.state = :activeHoldState
                    and hold.expiresAt > :now
                )
              )
              or exists (
                select reservation.id
                from OrderUnitReservation reservation
                where reservation.rentalItemId = item.id
                  and reservation.orderId = :orderId
                  and reservation.state = :activeState
                  and (
                    reservation.draftReservationExpiresAt is null
                    or reservation.draftReservationExpiresAt > :now
                  )
              )
            )
            and (
              :search = ''
              or upper(item.number) like concat('%', :search, '%')
              or upper(coalesce(item.category, '')) like concat('%', :search, '%')
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
                    and (
                      reservation.draftReservationExpiresAt is null
                      or reservation.draftReservationExpiresAt > :now
                    )
                )
                and not exists (
                  select lease.id
                  from OperationLease lease
                  where lease.rentalItemId = item.id
                    and lease.state = dev.buhanzaz.rwms.asset.domain.OperationLeaseState.ACTIVE
                    and lease.expiresAt > current_timestamp
                )
                and not exists (
                  select hold.id
                  from PresentationUnitHold hold
                  where hold.rentalItemId = item.id
                    and hold.state = :activeHoldState
                    and hold.expiresAt > :now
                )
              )
              or exists (
                select reservation.id
                from OrderUnitReservation reservation
                where reservation.rentalItemId = item.id
                  and reservation.orderId = :orderId
                  and reservation.state = :activeState
                  and (
                    reservation.draftReservationExpiresAt is null
                    or reservation.draftReservationExpiresAt > :now
                  )
              )
            )
            and (
              :search = ''
              or upper(item.number) like concat('%', :search, '%')
              or upper(coalesce(item.category, '')) like concat('%', :search, '%')
            )
          """)
  Page<RentalItem> findOrderCandidates(
      @Param("orderId") UUID orderId,
      @Param("warehouseId") UUID warehouseId,
      @Param("reservableStatuses") Collection<RentalItemStatus> reservableStatuses,
      @Param("activeState") OrderUnitReservationState activeState,
      @Param("activeHoldState") PresentationUnitHoldState activeHoldState,
      @Param("now") OffsetDateTime now,
      @Param("search") String search,
      Pageable pageable);
}
