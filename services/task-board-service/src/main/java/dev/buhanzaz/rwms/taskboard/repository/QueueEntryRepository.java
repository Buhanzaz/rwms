package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
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
   * <p>The query deliberately does not apply the configured daily-plan count: that value is a
   * presentation marker, while route type and route order remain the command admission fence.
   * Fetching task, queue and definition here prevents per-card lazy-loading queries.
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
