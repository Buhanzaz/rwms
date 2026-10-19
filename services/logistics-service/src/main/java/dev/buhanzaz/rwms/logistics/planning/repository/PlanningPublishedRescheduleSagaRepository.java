package dev.buhanzaz.rwms.logistics.planning.repository;

import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSaga;
import dev.buhanzaz.rwms.logistics.planning.domain.PlanningPublishedRescheduleSagaState;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locking and bounded-recovery persistence for published-plan reschedule intents. */
public interface PlanningPublishedRescheduleSagaRepository
    extends JpaRepository<PlanningPublishedRescheduleSaga, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select saga from PlanningPublishedRescheduleSaga saga where saga.id=:id")
  Optional<PlanningPublishedRescheduleSaga> findForUpdate(@Param("id") UUID id);

  /**
   * Reads booking-related saga checkpoints without locking them. The customer-session row is the
   * admission serializer, so taking a saga lock here would invert the owner saga-to-session order.
   */
  @Query(
      """
      select saga
      from PlanningPublishedRescheduleSaga saga
      where saga.orderId = :orderId
        and (saga.bookingId = :bookingId or saga.bookingId is null)
      order by saga.createdAt, saga.id
      """)
  List<PlanningPublishedRescheduleSaga> findAllForBookingAdmission(
      @Param("orderId") UUID orderId, @Param("bookingId") UUID bookingId);

  @Query(
      """
      select saga.id
      from PlanningPublishedRescheduleSaga saga
      where saga.state in :states
        and saga.nextAttemptAt <= :now
      order by saga.nextAttemptAt, saga.id
      """)
  List<UUID> findDueIds(
      @Param("states") Collection<PlanningPublishedRescheduleSagaState> states,
      @Param("now") OffsetDateTime now,
      Pageable pageable);
}
