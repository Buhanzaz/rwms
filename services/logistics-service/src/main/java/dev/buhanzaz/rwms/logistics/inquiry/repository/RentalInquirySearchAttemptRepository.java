package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySearchAttempt;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySearchAttemptState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Locked persistence boundary for durable rental-inquiry cabin-search receipts.
 */
public interface RentalInquirySearchAttemptRepository
    extends JpaRepository<RentalInquirySearchAttempt, UUID> {
  /** Locks a subject-scoped public idempotency receipt before replay or reuse decisions. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select attempt from RentalInquirySearchAttempt attempt
      where attempt.subjectId = :subjectId
        and attempt.operationName = :operationName
        and attempt.publicIdempotencyKey = :idempotencyKey
      """)
  Optional<RentalInquirySearchAttempt> findByPublicKeyForUpdate(
      @Param("subjectId") UUID subjectId,
      @Param("operationName") String operationName,
      @Param("idempotencyKey") UUID idempotencyKey);

  /** Locks the one active receipt for an inquiry before a distinct search is admitted. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select attempt from RentalInquirySearchAttempt attempt
      where attempt.inquiryId = :inquiryId
        and attempt.state = :state
      """)
  Optional<RentalInquirySearchAttempt> findByInquiryAndStateForUpdate(
      @Param("inquiryId") UUID inquiryId,
      @Param("state") RentalInquirySearchAttemptState state);

  /** Locks an exact receipt for completion, rejection, or expiry fencing. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select attempt from RentalInquirySearchAttempt attempt where attempt.id = :id")
  Optional<RentalInquirySearchAttempt> findForUpdate(@Param("id") UUID id);
}
