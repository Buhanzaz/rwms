package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local cabin catalog item persistence.
 */
public interface CabinCatalogItemRepository extends JpaRepository<CabinCatalogItem, UUID> {
  List<CabinCatalogItem> findAllByKindOrderByNameAscIdAsc(CabinCatalogKind kind);

  List<CabinCatalogItem> findAllByKindAndActiveTrueOrderByNameAscIdAsc(CabinCatalogKind kind);

  List<CabinCatalogItem> findAllByIdIn(Collection<UUID> ids);

  Optional<CabinCatalogItem> findByKindAndNameNormalized(
      CabinCatalogKind kind, String nameNormalized);
}
