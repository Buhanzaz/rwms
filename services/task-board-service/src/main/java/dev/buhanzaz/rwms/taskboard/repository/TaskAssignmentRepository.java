package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.TaskAssignment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, UUID> {
  List<TaskAssignment> findAllByQueueEntryId(UUID entryId);

  List<TaskAssignment> findAllByWorkerIdAndStatusIn(
      UUID workerId, Collection<AssignmentStatus> statuses);

  boolean existsByWorkerId(UUID workerId);

  boolean existsByWorkerGroupId(UUID groupId);

  List<TaskAssignment> findAllByQueueEntryIdAndStatusIn(
      UUID entryId, Collection<AssignmentStatus> statuses);

  boolean existsByQueueEntryIdInAndStartedAtIsNotNull(Collection<UUID> entryIds);
}
