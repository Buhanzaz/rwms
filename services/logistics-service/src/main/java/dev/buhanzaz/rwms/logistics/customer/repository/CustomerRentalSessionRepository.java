package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists and locks customer cart orchestration without owning asset availability. */
public interface CustomerRentalSessionRepository
    extends JpaRepository<CustomerRentalSession, UUID> {
  Optional<CustomerRentalSession> findByInquiryId(UUID inquiryId);

  Optional<CustomerRentalSession> findByInquiryIdAndCustomerSubjectId(
      UUID inquiryId, UUID customerSubjectId);

  Optional<CustomerRentalSession> findByBookingIdAndCustomerSubjectId(
      UUID bookingId, UUID customerSubjectId);

  /** Locks one booking without disclosing whether it belongs to another customer. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from CustomerRentalSession session where session.bookingId = :bookingId")
  Optional<CustomerRentalSession> findByBookingIdForUpdate(@Param("bookingId") UUID bookingId);

  Optional<CustomerRentalSession> findFirstByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from CustomerRentalSession session where session.inquiryId = :inquiryId")
  Optional<CustomerRentalSession> findByInquiryIdForUpdate(@Param("inquiryId") UUID inquiryId);

  List<CustomerRentalSession> findAllByCustomerSubjectIdOrderByCreatedAtDescIdDesc(
      UUID customerSubjectId);

  /** Uses native SQL locking to claim one due page while concurrent workers skip locked rows. */
  @Query(
      value =
          """
          select session.*
          from customer_rental_session session
          where session.state = 'CHECKOUT_PENDING'
            and session.booking_id is not null
            and session.presentation_token is not null
            and session.recovery_quarantined_at is null
            and session.recovery_next_attempt_at <= :timestamp
            and (
              session.recovery_lease_until is null
              or session.recovery_lease_until <= :timestamp
            )
          order by session.recovery_next_attempt_at, session.updated_at, session.id
          for update skip locked
          limit :batchSize
          """,
      nativeQuery = true)
  List<CustomerRentalSession> findDueCheckoutRecoveryForUpdate(
      @Param("timestamp") OffsetDateTime timestamp, @Param("batchSize") int batchSize);

  /** Returns PostgreSQL wall-clock time for cross-replica lease and backoff decisions. */
  @Query(value = "select clock_timestamp()", nativeQuery = true)
  Instant currentDatabaseTimestamp();
}
