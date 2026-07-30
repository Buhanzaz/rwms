package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogNodeRepository extends JpaRepository<CatalogNode, UUID> {
  List<CatalogNode> findAllByCatalogVersionIdOrderByNameAscIdAsc(UUID catalogVersionId);
  Optional<CatalogNode> findByCatalogVersionIdAndId(UUID catalogVersionId, UUID id);
  long countById(UUID id);
  void deleteAllByCatalogVersionId(UUID catalogVersionId);
}
