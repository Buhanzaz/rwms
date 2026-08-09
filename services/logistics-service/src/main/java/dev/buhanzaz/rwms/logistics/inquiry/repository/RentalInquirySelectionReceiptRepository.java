package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySelectionReceipt;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySelectionReceiptState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locked persistence boundary for inquiry cabin-selection command receipts. */
public interface RentalInquirySelectionReceiptRepository
    extends JpaRepository<RentalInquirySelectionReceipt, UUID> {
  /** Locks a subject-scoped public idempotency receipt before replay or reuse decisions. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select receipt from RentalInquirySelectionReceipt receipt
      where receipt.subjectId = :subjectId
        and receipt.publicIdempotencyKey = :idempotencyKey
      """)
  Optional<RentalInquirySelectionReceipt> findByPublicKeyForUpdate(
      @Param("subjectId") UUID subjectId, @Param("idempotencyKey") UUID idempotencyKey);

  /**
   * Locks the one unfinished effect for an inquiry before another selection command is admitted.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select receipt from RentalInquirySelectionReceipt receipt
      where receipt.inquiryId = :inquiryId and receipt.state = :state
      """)
  Optional<RentalInquirySelectionReceipt> findByInquiryAndStateForUpdate(
      @Param("inquiryId") UUID inquiryId, @Param("state") RentalInquirySelectionReceiptState state);

  /** Locks one exact receipt before completion or rejection. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select receipt from RentalInquirySelectionReceipt receipt where receipt.id = :id")
  Optional<RentalInquirySelectionReceipt> findForUpdate(@Param("id") UUID id);
}
