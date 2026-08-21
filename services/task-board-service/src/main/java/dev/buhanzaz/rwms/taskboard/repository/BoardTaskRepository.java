package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence queries for task-board tasks and their stable external source identities. */
public interface BoardTaskRepository extends JpaRepository<BoardTask, UUID> {
  boolean existsByExternalTaskId(UUID externalTaskId);

  Optional<BoardTask> findByExternalTaskId(UUID externalTaskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from BoardTask task where task.externalTaskId = :externalTaskId")
  Optional<BoardTask> findByExternalTaskIdForUpdate(@Param("externalTaskId") UUID externalTaskId);

  Optional<BoardTask> findByWarehouseIdAndExternalTaskId(UUID warehouseId, UUID externalTaskId);
}
