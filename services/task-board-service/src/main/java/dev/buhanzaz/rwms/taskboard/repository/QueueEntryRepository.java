package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Ordered and locking queries for mutable task route entries. */
public interface QueueEntryRepository extends JpaRepository<QueueEntry, UUID> {
  /**
   * Finds waiting consecutive continuations from durable completion assignments. No reservation is
   * invented from a client selection, a display name, or an unrelated repair of the same cabin.
   */
  @Query(
      """
      select distinct target as entry, workerGroup as workerGroup, source.doneAt as completedAt
      from QueueEntry target
      join target.task task
      join target.queue targetQueue
      join targetQueue.definition targetDefinition
      join QueueEntry source on source.task = task and source.routeIndex + 1 = target.routeIndex
      join source.queue sourceQueue
      join sourceQueue.definition sourceDefinition
      join TaskAssignment assignment on assignment.queueEntry = source
      join assignment.workerGroup workerGroup
      where task.warehouseId = :warehouseId
        and task.status = 'ACTIVE'
        and target.status = 'WAITING' and target.entryType = 'REAL'
        and source.status = 'DONE' and assignment.status = 'DONE'
        and assignment.primaryParticipation = true
        and sourceDefinition.linkedQueueDefinitionId = targetDefinition.id
        and targetDefinition.linkedQueueDefinitionId = sourceDefinition.id
        and sourceDefinition.active = true and targetDefinition.active = true
        and sourceQueue.active = true and targetQueue.active = true
        and workerGroup.warehouseId = :warehouseId
        and workerGroup.active = true and workerGroup.operationalStatus = 'AVAILABLE'
        and workerGroup.workerClass.active = true
        and exists (select binding.id from WorkQueueClassBinding binding
                    where binding.queue = targetQueue and binding.workerClass = workerGroup.workerClass
                      and binding.participationPolicy = 'PRIMARY')
        and exists (select member.id from WorkerGroupMember member
                    where member.workerGroup = workerGroup and member.active = true
                      and member.worker.active = true)
      order by source.doneAt, target.id
      """)
  List<LinkedContinuation> findLinkedContinuations(@Param("warehouseId") UUID warehouseId);

  /** A continuation and its previous stage's primary group, derived from persisted facts. */
  interface LinkedContinuation {
    QueueEntry getEntry();

    WorkerGroup getWorkerGroup();

    OffsetDateTime getCompletedAt();
  }

  List<QueueEntry> findAllByTaskIdOrderByRouteIndexAsc(UUID taskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select entry from QueueEntry entry where entry.task.id = :taskId order by entry.routeIndex")
  List<QueueEntry> findAllByTaskIdForUpdate(@Param("taskId") UUID taskId);

  /** Locks complete routes for a bounded task set before an all-or-nothing plan replacement. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select entry
      from QueueEntry entry
      join fetch entry.task task
      join fetch entry.queue queue
      join fetch queue.definition definition
      where task.id in :taskIds
      order by task.externalTaskId, entry.routeIndex
      """)
  List<QueueEntry> findAllByTaskIdInForUpdate(@Param("taskIds") Collection<UUID> taskIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select entry from QueueEntry entry where entry.id = :id")
  Optional<QueueEntry> findByIdForUpdate(@Param("id") UUID id);

  List<QueueEntry> findAllByQueueIdAndStatusInOrderByQueuePositionAsc(
      UUID queueId, Collection<EntryStatus> statuses);

  /** Loads one queue's unfinished entries without per-entry task or definition lookups. */
  @Query(
      """
      select entry
      from QueueEntry entry
      join fetch entry.task task
      join fetch entry.queue queue
      join fetch queue.definition definition
      where queue.id = :queueId
        and task.status = :taskStatus
        and entry.status in :statuses
      """)
  List<QueueEntry> findAllActiveByQueueIdAndStatusIn(
      @Param("queueId") UUID queueId,
      @Param("taskStatus") TaskStatus taskStatus,
      @Param("statuses") Collection<EntryStatus> statuses);

  /**
   * Loads every unfinished entry on the active ordinary board with its to-one ordering state.
   *
   * <p>The query deliberately does not apply the warehouse's WorkerApp publication controls:
   * manager projections need the complete board and the WorkerApp policy applies the switch and
   * waiting-real window after canonical SES gating. Fetching task, queue and definition here
   * prevents per-card lazy-loading queries.
   */
  @Query(
      """
      select entry
      from QueueEntry entry
      join fetch entry.task task
      join fetch entry.queue queue
      join fetch queue.definition definition
      where task.warehouseId = :warehouseId
        and queue.warehouseId = :warehouseId
        and task.status = :taskStatus
        and entry.status in :statuses
        and queue.active = true
        and queue.hidden = false
        and definition.purpose <> :excludedPurpose
      """)
  List<QueueEntry> findAllUnfinishedOrdinaryByWarehouseId(
      @Param("warehouseId") UUID warehouseId,
      @Param("taskStatus") TaskStatus taskStatus,
      @Param("statuses") Collection<EntryStatus> statuses,
      @Param("excludedPurpose") QueuePurpose excludedPurpose);

  Optional<QueueEntry> findFirstByTaskIdAndStatusNotInOrderByRouteIndexAsc(
      UUID taskId, Collection<EntryStatus> statuses);

  boolean existsByQueueId(UUID queueId);

  List<QueueEntry> findAllByQueueIdOrderByQueuePositionAsc(UUID queueId);

  List<QueueEntry> findAllByQueueIsNullAndTask_WarehouseIdOrderByQueuePositionAsc(UUID warehouseId);
}
