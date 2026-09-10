package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence queries for task-board tasks and their stable external source identities. */
public interface BoardTaskRepository extends JpaRepository<BoardTask, UUID> {
  boolean existsByExternalTaskId(UUID externalTaskId);

  List<BoardTask> findAllByWarehouseIdAndStatusOrderById(
      UUID warehouseId, dev.buhanzaz.rwms.taskboard.domain.TaskStatus status);

  Optional<BoardTask> findByExternalTaskId(UUID externalTaskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from BoardTask task where task.externalTaskId = :externalTaskId")
  Optional<BoardTask> findByExternalTaskIdForUpdate(@Param("externalTaskId") UUID externalTaskId);

  Optional<BoardTask> findByWarehouseIdAndExternalTaskId(UUID warehouseId, UUID externalTaskId);

  /** Locks an exact bounded external-task membership in deterministic identity order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select task
      from BoardTask task
      where task.externalTaskId in :externalTaskIds
      order by task.externalTaskId
      """)
  List<BoardTask> findAllByExternalTaskIdInForUpdate(
      @Param("externalTaskIds") Collection<UUID> externalTaskIds);
}
