package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.TaskAssignment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for current and historical task route assignments. */
public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, UUID> {
  List<TaskAssignment> findAllByQueueEntryId(UUID entryId);

  /** Loads assignments for a board result in one query, including worker/group identities. */
  @Query(
      """
      select assignment
      from TaskAssignment assignment
      join fetch assignment.queueEntry entry
      left join fetch assignment.worker worker
      left join fetch assignment.workerGroup workerGroup
      where entry.id in :entryIds
      order by entry.id, assignment.assignedAt, assignment.id
      """)
  List<TaskAssignment> findAllBoardAssignmentsByEntryIdIn(
      @Param("entryIds") Collection<UUID> entryIds);

  List<TaskAssignment> findAllByWorkerIdAndStatusIn(
      UUID workerId, Collection<AssignmentStatus> statuses);

  List<TaskAssignment> findAllByWorkerGroupIdAndStatusIn(
      UUID workerGroupId, Collection<AssignmentStatus> statuses);

  boolean existsByWorkerId(UUID workerId);

  boolean existsByWorkerGroupId(UUID groupId);

  List<TaskAssignment> findAllByQueueEntryIdAndStatusIn(
      UUID entryId, Collection<AssignmentStatus> statuses);

  boolean existsByQueueEntryIdInAndStartedAtIsNotNull(Collection<UUID> entryIds);

  boolean existsByQueueEntryIdIn(Collection<UUID> entryIds);
}
