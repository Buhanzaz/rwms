package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstimatePlanStageRepository extends JpaRepository<EstimatePlanStage, UUID> {
  List<EstimatePlanStage> findAllByEstimateIdAndEstimateRevisionOrderByStageNo(UUID estimateId, int estimateRevision);
  void deleteAllByEstimateIdAndEstimateRevision(UUID estimateId, int estimateRevision);
}
