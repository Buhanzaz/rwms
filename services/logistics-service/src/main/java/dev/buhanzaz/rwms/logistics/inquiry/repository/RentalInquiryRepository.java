package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RentalInquiryRepository extends JpaRepository<RentalInquiry, UUID> {
  @EntityGraph(attributePaths = "client")
  Optional<RentalInquiry> findByConversationId(UUID conversationId);

  @Override
  @EntityGraph(attributePaths = "client")
  Optional<RentalInquiry> findById(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = "client")
  @Query("select inquiry from RentalInquiry inquiry where inquiry.id = :id")
  Optional<RentalInquiry> findForUpdate(@Param("id") UUID id);

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
