package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local finding plan stage persistence.
 */
public interface FindingPlanStageRepository extends JpaRepository<FindingPlanStage, UUID> {
  @Query(
      """
      select stage from FindingPlanStage stage, InventoryFinding finding, FindingPlanSnapshot snapshot
       where finding.id in :findingIds
         and stage.findingId=finding.id
         and snapshot.findingId=stage.findingId and snapshot.findingRevision=stage.findingRevision
         and snapshot.fingerprint=finding.maintenancePlanFingerprintSha256
         and snapshot.findingRevision=(
           select max(candidate.findingRevision) from FindingPlanSnapshot candidate
            where candidate.findingId=finding.id
              and candidate.fingerprint=finding.maintenancePlanFingerprintSha256)
       order by stage.findingId,stage.stageNo
      """)
  List<FindingPlanStage> findActiveByFindingIds(@Param("findingIds") Set<UUID> findingIds);
}
