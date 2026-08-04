package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  Optional<QueueEntry> findFirstByTaskIdAndStatusNotInOrderByRouteIndexAsc(
      UUID taskId, Collection<EntryStatus> statuses);

  boolean existsByQueueId(UUID queueId);

  List<QueueEntry> findAllByQueueIdOrderByQueuePositionAsc(UUID queueId);

  List<QueueEntry> findAllByQueueIsNullAndTask_WarehouseIdOrderByQueuePositionAsc(UUID warehouseId);
}
