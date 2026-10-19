package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Warehouse-scoped queries for worker groups, qualifications, and availability. */
public interface WorkerGroupRepository extends JpaRepository<WorkerGroup, UUID> {
  /** Directory reads exclude archived groups without hiding historical assignment associations. */
  @Override
  @Query("select g from WorkerGroup g where g.id = :id and g.archived = false")
  Optional<WorkerGroup> findById(@Param("id") UUID id);

  @Query("select g from WorkerGroup g where g.warehouseId = :warehouseId "
      + "and g.archived = false order by g.name")
  List<WorkerGroup> findAllByWarehouseIdOrderByNameAsc(UUID warehouseId);

  List<WorkerGroup> findAllByWarehouseIdAndActiveTrueOrderByNameAsc(UUID warehouseId);

  @Query("select g from WorkerGroup g where g.warehouseId = :warehouseId "
      + "and lower(g.name) = lower(:name) and g.archived = false")
  Optional<WorkerGroup> findByWarehouseIdAndNameIgnoreCase(UUID warehouseId, String name);

  boolean existsByWorkerClassId(UUID classId);
}
