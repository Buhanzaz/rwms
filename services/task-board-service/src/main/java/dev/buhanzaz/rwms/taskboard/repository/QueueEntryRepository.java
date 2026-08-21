package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import jakarta.persistence.LockModeType;
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
  List<QueueEntry> findAllByTaskIdOrderByRouteIndexAsc(UUID taskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select entry from QueueEntry entry where entry.task.id = :taskId order by entry.routeIndex")
  List<QueueEntry> findAllByTaskIdForUpdate(@Param("taskId") UUID taskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select entry from QueueEntry entry where entry.id = :id")
  Optional<QueueEntry> findByIdForUpdate(@Param("id") UUID id);

  List<QueueEntry> findAllByQueueIdAndStatusInOrderByQueuePositionAsc(
      UUID queueId, Collection<EntryStatus> statuses);

  /** Loads one ordinary queue window without per-entry task or queue-definition lookups. */
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
   * Selects the bounded actionable ordinary-board entry IDs in canonical availability order.
   *
   * <p>Every REAL started or paused entry is retained. Waiting rows are ranked per physical queue
   * in the same priority/position/identity order used by TAKE and capped by the queue's
   * persisted availability limit before any entity is materialized.
   */
  @Query(
      value =
          """
          with ordinary_queue as (
            select queue.id, queue.available_task_limit
              from work_queue queue
              join queue_definition definition on definition.id = queue.definition_id
             where queue.warehouse_id = :warehouseId
               and queue.active
               and not queue.hidden
               and definition.queue_purpose <> 'LOGISTICS_DRIVER'
          ), waiting_entry as (
            select available.id
              from ordinary_queue queue
              join lateral (
                select entry.id
                  from board_task task
                  join queue_entry entry on entry.task_id = task.id
                 where task.warehouse_id = :warehouseId
                   and task.status = 'ACTIVE'
                   and entry.queue_id = queue.id
                   and entry.status = 'WAITING'
                   and entry.entry_type = 'REAL'
                 order by task.priority,
                          entry.queue_position,
                          task.id,
                          entry.route_index,
                          entry.id
                 limit queue.available_task_limit
              ) available on true
          )
          select waiting.id from waiting_entry waiting
          union all
          select entry.id
            from ordinary_queue queue
            join queue_entry entry on entry.queue_id = queue.id
            join board_task task on task.id = entry.task_id
           where task.warehouse_id = :warehouseId
             and task.status = 'ACTIVE'
             and entry.entry_type = 'REAL'
             and entry.status in ('IN_PROGRESS', 'PAUSED')
          """,
      nativeQuery = true)
  List<UUID> findVisibleOrdinaryEntryIds(@Param("warehouseId") UUID warehouseId);

  /** Hydrates only a previously bounded entry-ID set together with ordering dependencies. */
  @Query(
      """
      select entry
      from QueueEntry entry
      join fetch entry.task task
      join fetch entry.queue queue
      join fetch queue.definition definition
      where entry.id in :entryIds
      """)
  List<QueueEntry> findAllWithTaskAndQueueByIdIn(
      @Param("entryIds") Collection<UUID> entryIds);

  Optional<QueueEntry> findFirstByTaskIdAndStatusNotInOrderByRouteIndexAsc(
      UUID taskId, Collection<EntryStatus> statuses);

  boolean existsByQueueId(UUID queueId);

  List<QueueEntry> findAllByQueueIdOrderByQueuePositionAsc(UUID queueId);

  List<QueueEntry> findAllByQueueIsNullAndTask_WarehouseIdOrderByQueuePositionAsc(UUID warehouseId);
}
