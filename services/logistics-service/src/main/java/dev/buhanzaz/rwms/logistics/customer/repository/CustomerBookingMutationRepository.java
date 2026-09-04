package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationOperation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists idempotent CustomerApp booking lifecycle commands and their recovery leases. */
public interface CustomerBookingMutationRepository
    extends JpaRepository<CustomerBookingMutation, UUID> {
  Optional<CustomerBookingMutation> findByCustomerSubjectIdAndIdempotencyKey(
      UUID customerSubjectId, UUID idempotencyKey);

  /**
   * Returns whether an order is fenced by an unfinished customer booking operation. Callers first
   * lock the rental-order row, so command admission and checkpoint creation serialize in one order.
   */
  boolean existsByOrderIdAndOperationAndStateIn(
      UUID orderId,
      CustomerBookingMutationOperation operation,
      Collection<CustomerBookingMutationState> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select mutation from CustomerBookingMutation mutation where mutation.id = :id")
  Optional<CustomerBookingMutation> findForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select mutation
      from CustomerBookingMutation mutation
      where mutation.bookingId = :bookingId
        and mutation.state in :states
      order by mutation.createdAt, mutation.id
      """)
  List<CustomerBookingMutation> findOpenForBookingForUpdate(
      @Param("bookingId") UUID bookingId,
      @Param("states") Collection<CustomerBookingMutationState> states);

  /** Uses native SQL locking to claim an oldest-first page while workers skip locked rows. */
  @Query(
      value =
          """
          select mutation.*
          from customer_booking_mutation mutation
          where mutation.operation = 'CANCEL'
            and mutation.state = 'PENDING'
            and mutation.next_attempt_at <= :timestamp
            and (mutation.lease_until is null or mutation.lease_until <= :timestamp)
          order by mutation.next_attempt_at, mutation.created_at, mutation.id
          for update skip locked
          limit :batchSize
          """,
      nativeQuery = true)
  List<CustomerBookingMutation> findDueForUpdate(
      @Param("timestamp") OffsetDateTime timestamp, @Param("batchSize") int batchSize);

  /** Returns PostgreSQL wall-clock time for lease and backoff decisions. */
  @Query(value = "select clock_timestamp()", nativeQuery = true)
  Instant currentDatabaseTimestamp();
}
