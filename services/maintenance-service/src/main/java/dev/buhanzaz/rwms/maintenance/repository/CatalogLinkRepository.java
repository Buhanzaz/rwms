package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogLinkRepository extends JpaRepository<CatalogLink, UUID> {
  List<CatalogLink> findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(UUID catalogVersionId);
  void deleteAllByCatalogVersionId(UUID catalogVersionId);
}
