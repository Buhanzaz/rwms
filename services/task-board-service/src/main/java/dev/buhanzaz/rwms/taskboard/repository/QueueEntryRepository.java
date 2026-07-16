package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, UUID> {
  List<QueueEntry> findAllByTaskIdOrderByRouteIndexAsc(UUID taskId);

  List<QueueEntry> findAllByQueueIdAndStatusInOrderByQueuePositionAsc(
      UUID queueId, Collection<EntryStatus> statuses);

  Optional<QueueEntry> findFirstByTaskIdAndStatusNotInOrderByRouteIndexAsc(
      UUID taskId, Collection<EntryStatus> statuses);

  boolean existsByQueueId(UUID queueId);

  List<QueueEntry> findAllByQueueIdOrderByQueuePositionAsc(UUID queueId);

  List<QueueEntry> findAllByQueueIsNullAndTask_WarehouseIdOrderByQueuePositionAsc(UUID warehouseId);
}
