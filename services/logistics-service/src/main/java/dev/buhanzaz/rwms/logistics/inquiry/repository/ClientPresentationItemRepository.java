package dev.buhanzaz.rwms.logistics.inquiry.repository;

import dev.buhanzaz.rwms.logistics.inquiry.domain.ClientPresentationItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientPresentationItemRepository
    extends JpaRepository<ClientPresentationItem, UUID> {
  List<ClientPresentationItem>
      findAllByPresentationIdAndPresentationRevisionOrderBySortOrderAscIdAsc(
          UUID presentationId, long presentationRevision);
}
