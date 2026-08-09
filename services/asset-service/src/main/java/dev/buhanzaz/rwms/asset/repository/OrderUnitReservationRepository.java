package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local order unit reservation persistence.
 */
public interface OrderUnitReservationRepository
    extends JpaRepository<OrderUnitReservation, UUID> {
  Optional<OrderUnitReservation> findByOrderIdAndRentalItemIdAndState(
      UUID orderId, UUID rentalItemId, OrderUnitReservationState state);

  Optional<OrderUnitReservation> findByRentalItemIdAndState(
      UUID rentalItemId, OrderUnitReservationState state);

  List<OrderUnitReservation> findAllByRentalItemIdInAndState(
      List<UUID> rentalItemIds, OrderUnitReservationState state);

  List<OrderUnitReservation> findAllByOrderIdAndStateOrderByCreatedAtAscIdAsc(
      UUID orderId, OrderUnitReservationState state);

  @Query(
      """
      select reservation.id
      from OrderUnitReservation reservation
      where reservation.state = :state
        and reservation.draftReservationExpiresAt is not null
        and reservation.draftReservationExpiresAt <= :timestamp
      order by reservation.draftReservationExpiresAt, reservation.id
      """)
  List<UUID> findExpiredDraftReservationIds(
      @Param("state") OrderUnitReservationState state,
      @Param("timestamp") OffsetDateTime timestamp);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select reservation from OrderUnitReservation reservation where reservation.id = :id")
  Optional<OrderUnitReservation> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select reservation
      from OrderUnitReservation reservation
      where reservation.orderId = :orderId
        and reservation.rentalItemId = :rentalItemId
        and reservation.state = :state
      """)
  Optional<OrderUnitReservation> findActiveForUpdate(
      @Param("orderId") UUID orderId,
      @Param("rentalItemId") UUID rentalItemId,
      @Param("state") OrderUnitReservationState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select reservation
      from OrderUnitReservation reservation
      where reservation.orderId = :orderId
        and reservation.state = :state
      order by reservation.createdAt, reservation.id
      """)
  List<OrderUnitReservation> findAllActiveForUpdate(
      @Param("orderId") UUID orderId,
      @Param("state") OrderUnitReservationState state);

  boolean existsByOrderIdAndState(UUID orderId, OrderUnitReservationState state);
}
