package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderUnitReservationRepository
    extends JpaRepository<OrderUnitReservation, UUID> {
  Optional<OrderUnitReservation> findByOrderIdAndRentalItemIdAndState(
      UUID orderId, UUID rentalItemId, OrderUnitReservationState state);

  Optional<OrderUnitReservation> findByRentalItemIdAndState(
      UUID rentalItemId, OrderUnitReservationState state);

  List<OrderUnitReservation> findAllByOrderIdAndStateOrderByCreatedAtAscIdAsc(
      UUID orderId, OrderUnitReservationState state);

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
