package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkQueueClassBindingRepository
    extends JpaRepository<WorkQueueClassBinding, UUID> {
  List<WorkQueueClassBinding> findAllByQueueId(UUID queueId);

  List<WorkQueueClassBinding> findAllByQueueIdOrderByBindingOrderAscIdAsc(UUID queueId);

  List<WorkQueueClassBinding> findAllByWorkerClassId(UUID workerClassId);

  boolean existsByWorkerClassId(UUID classId);

  Optional<WorkQueueClassBinding> findByQueueIdAndWorkerClassId(
      UUID queueId, UUID workerClassId);
}
