package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkQueueRepository extends JpaRepository<WorkQueue, UUID> {
  List<WorkQueue> findAllByWarehouseIdOrderBySortOrderAscNameAsc(UUID warehouseId);

  List<WorkQueue> findAllByWarehouseIdAndActiveTrueOrderBySortOrderAscNameAsc(UUID warehouseId);

  boolean existsByWarehouseIdAndCodeIgnoreCaseAndIdNot(UUID warehouseId, String code, UUID id);

  Optional<WorkQueue> findByWarehouseIdAndCodeIgnoreCase(UUID warehouseId, String code);
}
