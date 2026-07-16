package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardTaskRepository extends JpaRepository<BoardTask, UUID> {
  List<BoardTask> findAllByWarehouseIdAndStatusIn(
      UUID warehouseId, Collection<TaskStatus> statuses);

  boolean existsByExternalTaskId(UUID externalTaskId);

  Optional<BoardTask> findByExternalTaskId(UUID externalTaskId);

  Optional<BoardTask> findByWarehouseIdAndExternalTaskId(UUID warehouseId, UUID externalTaskId);
}
