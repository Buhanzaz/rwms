package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.TransferUnitReservation;
import dev.buhanzaz.rwms.asset.domain.TransferUnitReservationState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for asset-owned inter-warehouse cabin reservation history. */
public interface TransferUnitReservationRepository
    extends JpaRepository<TransferUnitReservation, UUID> {
  /** Locks active reservation owners for the already locked cabins in stable identity order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select reservation
      from TransferUnitReservation reservation
      where reservation.rentalItemId in :rentalItemIds
        and reservation.state = :state
      order by reservation.rentalItemId, reservation.id
      """)
  List<TransferUnitReservation> findAllByRentalItemIdsAndStateForUpdate(
      @Param("rentalItemIds") Collection<UUID> rentalItemIds,
      @Param("state") TransferUnitReservationState state);

  /** Locks one exact transfer line after its rental-item row has been locked. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select reservation
      from TransferUnitReservation reservation
      where reservation.transferId = :transferId
        and reservation.lineId = :lineId
        and reservation.rentalItemId = :rentalItemId
      """)
  Optional<TransferUnitReservation> findOwnedForUpdate(
      @Param("transferId") UUID transferId,
      @Param("lineId") UUID lineId,
      @Param("rentalItemId") UUID rentalItemId);

  /** Locks a release batch in stable reservation identity order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select reservation
      from TransferUnitReservation reservation
      where reservation.id in :ids
      order by reservation.id
      """)
  List<TransferUnitReservation> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids);

  Optional<TransferUnitReservation> findByTransferIdAndLineId(UUID transferId, UUID lineId);

  /** Detects a still-live frozen inventory capture that currently includes the selected cabin. */
  @Query(
      value =
          """
          select exists(
            select 1
            from public.inventory_asset_capture_member member
            join public.inventory_asset_capture capture on capture.capture_id = member.capture_id
            where member.asset_id = :rentalItemId
              and capture.state = 'ACTIVE'
              and capture.expires_at > clock_timestamp()
          )
          """,
      nativeQuery = true)
  boolean existsLiveInventoryCapture(@Param("rentalItemId") UUID rentalItemId);

  /** Detects a maintenance-owned prepared write-off/loss fence for the selected cabin. */
  @Query(
      value =
          """
          select exists(
            select 1
            from public.property_disposition_fence fence
            where fence.asset_kind = 'CABIN'
              and fence.asset_id = :rentalItemId
              and fence.state = 'PREPARED'
          )
          """,
      nativeQuery = true)
  boolean existsPreparedDisposition(@Param("rentalItemId") UUID rentalItemId);
}
