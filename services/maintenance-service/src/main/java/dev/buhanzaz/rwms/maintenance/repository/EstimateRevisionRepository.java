package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.EstimateRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstimateRevisionRepository extends JpaRepository<EstimateRevision, UUID> {
  Optional<EstimateRevision> findByEstimateIdAndRevision(UUID estimateId, int revision);
  List<EstimateRevision> findAllByEstimateIdOrderByRevision(UUID estimateId);
}
