package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.TaskSyncSource;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskSyncSourceRepository extends JpaRepository<TaskSyncSource, UUID> {
  boolean existsByBoardTaskIdAndSourceClientId(UUID boardTaskId, String sourceClientId);
}
