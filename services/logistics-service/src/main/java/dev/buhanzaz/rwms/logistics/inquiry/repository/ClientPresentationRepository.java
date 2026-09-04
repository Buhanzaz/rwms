package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Client Presentation Repository; it does not own cross-service workflow decisions.
 */
public interface ClientPresentationRepository
    extends JpaRepository<ClientPresentation, UUID> {
  Optional<ClientPresentation> findByInquiryId(UUID inquiryId);

  Optional<ClientPresentation> findById(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select presentation from ClientPresentation presentation where presentation.id = :id")
  Optional<ClientPresentation> findForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select presentation from ClientPresentation presentation where presentation.inquiryId = :inquiryId")
  Optional<ClientPresentation> findByInquiryIdForUpdate(@Param("inquiryId") UUID inquiryId);
}
