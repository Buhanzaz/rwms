package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for CatalogLink; business transitions remain in the owning service. */
public interface CatalogLinkRepository extends JpaRepository<CatalogLink, UUID> {
  List<CatalogLink> findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(UUID catalogVersionId);
  void deleteAllByCatalogVersionId(UUID catalogVersionId);
}
