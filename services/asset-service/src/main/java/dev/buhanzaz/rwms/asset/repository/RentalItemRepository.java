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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local rental item persistence.
 */
public interface RentalItemRepository extends JpaRepository<RentalItem, UUID> {
  /** Inserts an inventory-reserved UUID exactly instead of invoking the entity UUID generator. */
  @Modifying
  @Query(
      value =
          """
          insert into rental_item (
            id, version, warehouse_id, display_canonical_number, identity_match_key, status,
            transfer_origin_status, cabin_type_id, cabin_dimension_id, cabin_finishing_id,
            cabin_category_id, category, linoleum, general_comment, passport_json, tags_json,
            created_at, updated_at
          ) values (
            :id, 0, :warehouseId, :number, :identityMatchKey, :status,
            null, :rentalTypeId, :dimensionId, :finishingId,
            :categoryId, :category, :linoleum, null, :passportJson, :tagsJson,
            current_timestamp, current_timestamp
          )
          """,
      nativeQuery = true)
  int insertInventorySource(
      @Param("id") UUID id,
      @Param("warehouseId") UUID warehouseId,
      @Param("number") String number,
      @Param("identityMatchKey") String identityMatchKey,
      @Param("status") String status,
      @Param("rentalTypeId") UUID rentalTypeId,
      @Param("dimensionId") UUID dimensionId,
      @Param("finishingId") UUID finishingId,
      @Param("categoryId") UUID categoryId,
      @Param("category") String category,
      @Param("linoleum") Boolean linoleum,
      @Param("passportJson") String passportJson,
      @Param("tagsJson") String tagsJson);

  /** Includes held rows because a catalog reference cannot be deleted while history still uses it. */
  @Query(
      value = "select exists (select 1 from rental_item where cabin_type_id = :rentalTypeId)",
      nativeQuery = true)
  boolean existsByRentalTypeId(@Param("rentalTypeId") UUID rentalTypeId);

  /** Includes held rows because a catalog reference cannot be deleted while history still uses it. */
  @Query(
      value = "select exists (select 1 from rental_item where cabin_dimension_id = :dimensionId)",
      nativeQuery = true)
  boolean existsByDimensionId(@Param("dimensionId") UUID dimensionId);

  /** Includes held rows because a catalog reference cannot be deleted while history still uses it. */
  @Query(
      value = "select exists (select 1 from rental_item where cabin_finishing_id = :finishingId)",
      nativeQuery = true)
  boolean existsByFinishingId(@Param("finishingId") UUID finishingId);

  /** Includes held rows because a catalog reference cannot be deleted while history still uses it. */
  @Query(
      value = "select exists (select 1 from rental_item where cabin_category_id = :categoryId)",
      nativeQuery = true)
  boolean existsByCategoryId(@Param("categoryId") UUID categoryId);

  /**
   * Reads one held pre-proposal source row only when its immutable source receipt and operation
   * prove the exact inventory/finding/item tuple. This is intentionally not a generic held-item
   * lookup.
   */
  @Query(
      value =
          """
          select item.*
          from rental_item item
          join inventory_asset_source_operation operation
            on operation.inventory_id = :inventoryId
           and operation.finding_id = :findingId
           and operation.reserved_rental_item_id = item.id
          join inventory_asset_source source
            on source.inventory_id = operation.inventory_id
           and source.finding_id = operation.finding_id
           and source.rental_item_id = item.id
           and source.request_fingerprint = operation.request_fingerprint
          where item.id = :assetId
            and item.inventory_isolation_id = :inventoryId
            and operation.source_plan is null
          """,
      nativeQuery = true)
  Optional<RentalItem> findHeldLegacyInventorySource(
      @Param("inventoryId") UUID inventoryId,
      @Param("findingId") UUID findingId,
      @Param("assetId") UUID assetId);

  /** Same receipt-scoped held lookup while serializing completed inventory reconciliation. */
  @Query(
      value =
          """
          select item.*
          from rental_item item
          join inventory_asset_source_operation operation
            on operation.inventory_id = :inventoryId
           and operation.finding_id = :findingId
           and operation.reserved_rental_item_id = item.id
          join inventory_asset_source source
            on source.inventory_id = operation.inventory_id
           and source.finding_id = operation.finding_id
           and source.rental_item_id = item.id
           and source.request_fingerprint = operation.request_fingerprint
          where item.id = :assetId
            and item.inventory_isolation_id = :inventoryId
            and operation.source_plan is null
          for update of item
          """,
      nativeQuery = true)
  Optional<RentalItem> findHeldLegacyInventorySourceForUpdate(
      @Param("inventoryId") UUID inventoryId,
      @Param("findingId") UUID findingId,
      @Param("assetId") UUID assetId);

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
