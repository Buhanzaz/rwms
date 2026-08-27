package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from CustomerRentalSession session where session.inquiryId = :inquiryId")
  Optional<CustomerRentalSession> findByInquiryIdForUpdate(@Param("inquiryId") UUID inquiryId);

  List<CustomerRentalSession> findAllByCustomerSubjectIdOrderByCreatedAtDescIdDesc(
      UUID customerSubjectId);

  List<CustomerRentalSession> findAllByStateOrderByUpdatedAtAscIdAsc(
      CustomerSessionState state, Pageable page);
}
