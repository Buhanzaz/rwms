package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.TaskTimeEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskTimeEventRepository extends JpaRepository<TaskTimeEvent, UUID> {
  boolean existsByWorkerId(UUID workerId);

  boolean existsByQueueEntryQueueId(UUID queueId);

  long countByQueueEntryIdAndEventType(
      UUID entryId, dev.buhanzaz.rwms.taskboard.domain.TimeEventType eventType);

  List<TaskTimeEvent> findAllByQueueEntryIdOrderByCreatedAtAsc(UUID entryId);
}
