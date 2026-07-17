package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceEstimateRepository extends JpaRepository<MaintenanceEstimate, UUID> {
  List<MaintenanceEstimate> findAllByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId);
  Optional<MaintenanceEstimate> findByIdAndWarehouseId(UUID id, UUID warehouseId);
}
