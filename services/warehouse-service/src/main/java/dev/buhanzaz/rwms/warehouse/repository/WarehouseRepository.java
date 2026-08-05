package dev.buhanzaz.rwms.warehouse.repository;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseLifecycleState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
  List<Warehouse> findAllByLifecycleState(WarehouseLifecycleState lifecycleState);

  List<Warehouse> findAllByLifecycleStateNot(WarehouseLifecycleState lifecycleState);

  boolean existsByNormalizedName(String normalizedName);

  boolean existsByNormalizedNameAndIdNot(String normalizedName, UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select warehouse from Warehouse warehouse where warehouse.id = :id")
  Optional<Warehouse> findByIdForUpdate(@Param("id") UUID id);
}
