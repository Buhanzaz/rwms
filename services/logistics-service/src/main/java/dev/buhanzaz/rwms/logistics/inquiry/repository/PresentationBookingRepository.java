package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Persists logistics-owned presentation bookings and exposes their PostgreSQL SKIP LOCKED recovery
 * locks; cross-service workflow decisions remain in the owning application service.
 */
public interface PresentationBookingRepository
    extends JpaRepository<PresentationBooking, UUID> {
  Optional<PresentationBooking> findByPresentationIdAndPresentationRevision(
      UUID presentationId, long presentationRevision);

  boolean existsByPresentationIdAndPresentationRevision(
      UUID presentationId, long presentationRevision);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select booking from PresentationBooking booking where booking.id = :id")
  Optional<PresentationBooking> findForUpdate(@Param("id") UUID id);

  @Query(
      """
      select booking
      from PresentationBooking booking
      join ClientPresentation presentation on presentation.id = booking.presentationId
      join RentalInquiry inquiry on inquiry.id = presentation.inquiryId
      where booking.state = :state
        and booking.managerAction is null
        and inquiry.managerId = :managerId
      order by booking.completedAt asc, booking.id asc
      """)
  List<PresentationBooking> findUnprocessedByManagerIdAndState(
      @Param("managerId") UUID managerId,
      @Param("state") PresentationBookingState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select booking
      from PresentationBooking booking
      join ClientPresentation presentation on presentation.id = booking.presentationId
      join RentalInquiry inquiry on inquiry.id = presentation.inquiryId
      where booking.id = :bookingId
        and booking.state = :state
        and inquiry.managerId = :managerId
      """)
  Optional<PresentationBooking> findForUpdateByIdAndManagerIdAndState(
      @Param("bookingId") UUID bookingId,
      @Param("managerId") UUID managerId,
      @Param("state") PresentationBookingState state);

  /**
   * Locks one bounded due page without waiting for rows already claimed by another replica.
   * Hibernate maps the negative lock timeout to PostgreSQL {@code FOR UPDATE SKIP LOCKED}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select booking
      from PresentationBooking booking
      where booking.state = dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState.PENDING
        and booking.recoveryQuarantinedAt is null
        and (booking.recoveryNextAttemptAt is null
             or booking.recoveryNextAttemptAt <= current_timestamp)
        and (booking.recoveryLeaseUntil is null
             or booking.recoveryLeaseUntil <= current_timestamp)
      order by coalesce(booking.recoveryNextAttemptAt, booking.createdAt),
               booking.createdAt,
               booking.id
      """)
  List<PresentationBooking> lockDueForRecovery(Pageable page);

  /**
   * Locks one exact due row for synchronous confirmation without waiting on another claimant.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select booking
      from PresentationBooking booking
      where booking.id = :bookingId
        and booking.state = dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState.PENDING
        and booking.recoveryQuarantinedAt is null
        and (booking.recoveryNextAttemptAt is null
             or booking.recoveryNextAttemptAt <= current_timestamp)
        and (booking.recoveryLeaseUntil is null
             or booking.recoveryLeaseUntil <= current_timestamp)
      """)
  Optional<PresentationBooking> lockExactDueForRecovery(@Param("bookingId") UUID bookingId);

  /** Re-locks the exact unexpired capability before a recovery worker mutates local state. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select booking
      from PresentationBooking booking
      where booking.id = :bookingId
        and booking.state = dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState.PENDING
        and booking.recoveryQuarantinedAt is null
        and booking.recoveryLeaseToken = :leaseToken
        and booking.recoveryLeaseUntil > current_timestamp
      """)
  Optional<PresentationBooking> lockCurrentRecoveryClaim(
      @Param("bookingId") UUID bookingId, @Param("leaseToken") UUID leaseToken);

  /** Reads an exact unexpired capability without retaining a database lock across remote work. */
  @Query(
      """
      select booking
      from PresentationBooking booking
      where booking.id = :bookingId
        and booking.state = dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState.PENDING
        and booking.recoveryQuarantinedAt is null
        and booking.recoveryLeaseToken = :leaseToken
        and booking.recoveryLeaseUntil > current_timestamp
      """)
  Optional<PresentationBooking> findCurrentRecoveryClaim(
      @Param("bookingId") UUID bookingId, @Param("leaseToken") UUID leaseToken);

  /** Reads PostgreSQL transaction time through Hibernate's specified JDBC timestamp scalar. */
  @Query(
      """
      select current_timestamp
      from PresentationBooking booking
      where booking.id = :bookingId
      """)
  Optional<Timestamp> currentDatabaseTimestampScalar(@Param("bookingId") UUID bookingId);

  /** Returns PostgreSQL transaction time as a UTC domain value. */
  default Optional<OffsetDateTime> currentDatabaseTimestamp(UUID bookingId) {
    return currentDatabaseTimestampScalar(bookingId)
        .map(timestamp -> timestamp.toInstant().atOffset(ZoneOffset.UTC));
  }
}
