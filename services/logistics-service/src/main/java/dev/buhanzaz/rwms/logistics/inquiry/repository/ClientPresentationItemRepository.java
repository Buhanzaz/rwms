package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data persistence boundary for logistics-owned Client Presentation Item Repository; it does not own cross-service workflow decisions.
 */
public interface ClientPresentationItemRepository
    extends JpaRepository<ClientPresentationItem, UUID> {
  List<ClientPresentationItem>
      findAllByPresentationIdAndPresentationRevisionOrderBySortOrderAscIdAsc(
          UUID presentationId, long presentationRevision);

  Optional<ClientPresentationItem> findByPresentationIdAndPresentationRevisionAndRentalItemId(
      UUID presentationId, long presentationRevision, UUID rentalItemId);
}
