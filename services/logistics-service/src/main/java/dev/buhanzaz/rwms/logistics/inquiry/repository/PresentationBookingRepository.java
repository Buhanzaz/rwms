package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
      @Param("managerId") UUID managerId, @Param("state") PresentationBookingState state);

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

  List<PresentationBooking> findAllByStateOrderByCreatedAtAscIdAsc(
      PresentationBookingState state, Pageable pageable);
}
