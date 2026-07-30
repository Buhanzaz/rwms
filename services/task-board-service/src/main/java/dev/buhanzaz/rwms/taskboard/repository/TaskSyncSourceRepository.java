package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.TaskSyncSource;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskSyncSourceRepository extends JpaRepository<TaskSyncSource, UUID> {
  boolean existsByBoardTaskIdAndSourceClientId(UUID boardTaskId, String sourceClientId);

  List<TaskSyncSource> findAllBySourceClientId(String sourceClientId);

  List<TaskSyncSource> findAllByBoardTaskIdIn(Collection<UUID> boardTaskIds);
}
