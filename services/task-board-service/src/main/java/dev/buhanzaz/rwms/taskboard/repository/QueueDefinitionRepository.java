package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.QueueDefinition;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueDefinitionRepository extends JpaRepository<QueueDefinition, UUID> {
  List<QueueDefinition> findAllByPurposeOrderBySortOrderAscNameAscTypeAscIdAsc(
      QueuePurpose purpose);

  List<QueueDefinition> findAllByOrderByNameAscTypeAscIdAsc();

  List<QueueDefinition> findAllByPurposeOrderByNameAscIdAsc(QueuePurpose purpose);

  Optional<QueueDefinition> findByIdAndPurpose(UUID id, QueuePurpose purpose);

  Optional<QueueDefinition> findByNormalizedNameAndType(String normalizedName, QueueType type);

  boolean existsByNormalizedNameAndType(String normalizedName, QueueType type);
}
