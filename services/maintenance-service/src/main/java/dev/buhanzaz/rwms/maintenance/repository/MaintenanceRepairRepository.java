package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceRepairRepository extends JpaRepository<MaintenanceRepair, UUID> {
  List<MaintenanceRepair> findAllByWarehouseIdOrderByCreatedAtDesc(UUID warehouseId);
  Optional<MaintenanceRepair> findByIdAndWarehouseId(UUID id, UUID warehouseId);
  Optional<MaintenanceRepair> findByExternalTaskId(UUID externalTaskId);
  List<MaintenanceRepair> findAllByLeaseId(UUID leaseId);
  boolean existsBySourceRepairIdAndExecutionStateIn(
      UUID sourceRepairId,
      java.util.Collection<dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from MaintenanceRepair value where value.id in :ids order by value.id")
  List<MaintenanceRepair> findAllByIdForUpdate(@Param("ids") java.util.Collection<UUID> ids);
}
