package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.QueueUsageReference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueUsageReferenceRepository extends JpaRepository<QueueUsageReference, UUID> {
  boolean existsByDefinitionId(UUID definitionId);

  Optional<QueueUsageReference> findByReferenceTypeAndExternalReferenceId(
      QueueReferenceType referenceType, String externalReferenceId);
}
