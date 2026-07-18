package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsTaskReferenceRepository extends JpaRepository<LogisticsTaskReference, UUID> {
  List<LogisticsTaskReference> findAllByDocument_IdOrderByCreatedAtAsc(UUID documentId);

  Optional<LogisticsTaskReference> findByLine_Id(UUID lineId);

  Optional<LogisticsTaskReference> findByExternalTaskId(UUID externalTaskId);
}
