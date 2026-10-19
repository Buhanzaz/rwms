package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.CabinTypeDimension;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local cabin type dimension persistence.
 */
public interface CabinTypeDimensionRepository extends JpaRepository<CabinTypeDimension, UUID> {
  boolean existsByCabinTypeId(UUID cabinTypeId);

  boolean existsByDimensionId(UUID dimensionId);

  boolean existsByCabinTypeIdAndDimensionId(UUID cabinTypeId, UUID dimensionId);

  List<CabinTypeDimension> findAllByCabinTypeIdOrderBySortOrderAscIdAsc(UUID cabinTypeId);

  List<CabinTypeDimension> findAllByCabinTypeIdInOrderByCabinTypeIdAscSortOrderAscIdAsc(
      Collection<UUID> cabinTypeIds);

  void deleteAllByCabinTypeId(UUID cabinTypeId);
}
