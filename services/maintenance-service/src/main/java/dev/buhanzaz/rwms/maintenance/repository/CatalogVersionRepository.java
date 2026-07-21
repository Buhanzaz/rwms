package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogVersionRepository extends JpaRepository<CatalogVersion, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<CatalogVersion> findByWarehouseIdAndSourceSha256(UUID warehouseId, String sourceSha256);
  Optional<CatalogVersion> findByIdAndWarehouseId(UUID id, UUID warehouseId);
  Optional<CatalogVersion> findByWarehouseIdAndState(UUID warehouseId, CatalogVersionState state);
  List<CatalogVersion> findAllByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from CatalogVersion value where value.id = :id")
  Optional<CatalogVersion> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select value from CatalogVersion value
      where value.warehouseId = :warehouseId
      order by value.id
      """)
  List<CatalogVersion> findAllByWarehouseIdForUpdate(@Param("warehouseId") UUID warehouseId);
}
