package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceEstimateRepository extends JpaRepository<MaintenanceEstimate, UUID> {
  List<MaintenanceEstimate> findAllByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId);
  Optional<MaintenanceEstimate> findByIdAndWarehouseId(UUID id, UUID warehouseId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from MaintenanceEstimate value where value.id = :id")
  Optional<MaintenanceEstimate> findByIdForUpdate(@Param("id") UUID id);
}
