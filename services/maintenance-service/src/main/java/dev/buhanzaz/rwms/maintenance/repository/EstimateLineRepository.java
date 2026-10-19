package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for EstimateLine; business transitions remain in the owning service. */
public interface EstimateLineRepository extends JpaRepository<EstimateLine, UUID> {
  List<EstimateLine> findAllByEstimateIdAndEstimateRevisionOrderByLineNo(UUID estimateId, int estimateRevision);
  List<EstimateLine> findAllByEstimateIdOrderByEstimateRevisionAscLineNoAsc(UUID estimateId);
  List<EstimateLine>
      findAllByEstimateIdInOrderByEstimateIdAscEstimateRevisionAscLineNoAsc(
          Collection<UUID> estimateIds);
  long countByEstimateIdAndEstimateRevision(UUID estimateId, int estimateRevision);
  void deleteAllByEstimateIdAndEstimateRevision(UUID estimateId, int estimateRevision);
}
