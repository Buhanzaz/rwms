package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstimateLineRepository extends JpaRepository<EstimateLine, UUID> {
  List<EstimateLine> findAllByEstimateIdAndEstimateRevisionOrderByLineNo(UUID estimateId, int estimateRevision);
  long countByEstimateIdAndEstimateRevision(UUID estimateId, int estimateRevision);
  void deleteAllByEstimateIdAndEstimateRevision(UUID estimateId, int estimateRevision);
}
