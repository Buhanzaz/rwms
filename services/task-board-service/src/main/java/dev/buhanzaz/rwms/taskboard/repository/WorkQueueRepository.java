package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkQueueRepository extends JpaRepository<WorkQueue, UUID> {
  @EntityGraph(attributePaths = "definition")
  @Query(
      """
      select queue
      from WorkQueue queue
      where queue.warehouseId = :warehouseId
      order by queue.sortOrder, queue.definition.name, queue.id
      """)
  List<WorkQueue> findAllOrderedByWarehouseId(@Param("warehouseId") UUID warehouseId);

  @EntityGraph(attributePaths = "definition")
  @Query(
      """
      select queue
      from WorkQueue queue
      where queue.warehouseId = :warehouseId
        and queue.active = true
      order by queue.sortOrder, queue.definition.name, queue.id
      """)
  List<WorkQueue> findAllActiveOrderedByWarehouseId(@Param("warehouseId") UUID warehouseId);

  @EntityGraph(attributePaths = "definition")
  Optional<WorkQueue> findByWarehouseIdAndDefinitionId(UUID warehouseId, UUID definitionId);

  @EntityGraph(attributePaths = "definition")
  List<WorkQueue> findAllByDefinitionIdOrderByWarehouseIdAscIdAsc(UUID definitionId);

  boolean existsByDefinitionId(UUID definitionId);

  boolean existsByWarehouseIdAndDefinitionId(UUID warehouseId, UUID definitionId);
}
