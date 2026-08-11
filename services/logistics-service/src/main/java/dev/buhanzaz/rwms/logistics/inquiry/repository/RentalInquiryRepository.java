package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Rental Inquiry Repository; it does not own
 * cross-service workflow decisions.
 */
public interface RentalInquiryRepository extends JpaRepository<RentalInquiry, UUID> {
  @EntityGraph(attributePaths = "client")
  Optional<RentalInquiry> findByConversationId(UUID conversationId);

  @EntityGraph(attributePaths = "client")
  Optional<RentalInquiry> findByManagerIdAndCreationIdempotencyKey(
      UUID managerId, UUID creationIdempotencyKey);

  @Override
  @EntityGraph(attributePaths = "client")
  Optional<RentalInquiry> findById(UUID id);

  @EntityGraph(attributePaths = "client")
  List<RentalInquiry> findAllByRentalOrderIdOrderByCreatedAtDescIdDesc(UUID rentalOrderId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = "client")
  @Query("select inquiry from RentalInquiry inquiry where inquiry.id = :id")
  Optional<RentalInquiry> findForUpdate(@Param("id") UUID id);
}
