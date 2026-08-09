package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Warehouse-scoped queries for worker groups, qualifications, and availability. */
public interface WorkerGroupRepository extends JpaRepository<WorkerGroup, UUID> {
  List<WorkerGroup> findAllByWarehouseIdOrderByNameAsc(UUID warehouseId);

  List<WorkerGroup> findAllByWarehouseIdAndActiveTrueOrderByNameAsc(UUID warehouseId);

  Optional<WorkerGroup> findByWarehouseIdAndNameIgnoreCase(UUID warehouseId, String name);

  boolean existsByWorkerClassId(UUID classId);
}
