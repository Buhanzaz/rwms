package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.QueueDefinitionClassBinding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueDefinitionClassBindingRepository
    extends JpaRepository<QueueDefinitionClassBinding, UUID> {
  List<QueueDefinitionClassBinding> findAllByDefinitionIdOrderByBindingOrderAscIdAsc(
      UUID definitionId);

  List<QueueDefinitionClassBinding> findAllByWorkerClassId(UUID workerClassId);

  boolean existsByWorkerClassId(UUID workerClassId);
}
