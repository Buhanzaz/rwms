package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardTaskRepository extends JpaRepository<BoardTask, UUID> {
  List<BoardTask> findAllByWarehouseIdAndStatusIn(
      UUID warehouseId, Collection<TaskStatus> statuses);

  boolean existsByExternalTaskId(UUID externalTaskId);

  Optional<BoardTask> findByExternalTaskId(UUID externalTaskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from BoardTask task where task.externalTaskId = :externalTaskId")
  Optional<BoardTask> findByExternalTaskIdForUpdate(@Param("externalTaskId") UUID externalTaskId);

  Optional<BoardTask> findByWarehouseIdAndExternalTaskId(UUID warehouseId, UUID externalTaskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select task
      from BoardTask task
      where task.warehouseId = :warehouseId
        and task.id in :taskIds
        and task.status = :status
        and task.scheduledDate < :targetDate
      order by task.scheduledDate, task.id
      """)
  List<BoardTask> findAllOverdueForUpdate(
      @Param("warehouseId") UUID warehouseId,
      @Param("taskIds") Collection<UUID> taskIds,
      @Param("status") TaskStatus status,
      @Param("targetDate") LocalDate targetDate);
}
