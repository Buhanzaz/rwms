package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for EstimatePlanStage; business transitions remain in the owning service. */
public interface EstimatePlanStageRepository extends JpaRepository<EstimatePlanStage, UUID> {
  List<EstimatePlanStage> findAllByEstimateIdAndEstimateRevisionOrderByStageNo(UUID estimateId, int estimateRevision);
  List<EstimatePlanStage>
      findAllByEstimateIdInOrderByEstimateIdAscEstimateRevisionAscStageNoAsc(
          Collection<UUID> estimateIds);
  void deleteAllByEstimateIdAndEstimateRevision(UUID estimateId, int estimateRevision);
}
