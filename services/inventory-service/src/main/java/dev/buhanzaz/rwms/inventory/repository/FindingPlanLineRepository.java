package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FindingPlanLineRepository extends JpaRepository<FindingPlanLine, UUID> {
  @Query(
      """
      select line from FindingPlanLine line, InventoryFinding finding
       where finding.inventoryId=:inventoryId and finding.id=line.findingId
         and finding.revision=line.findingRevision
       order by line.lineType,line.lineNo
      """)
  List<FindingPlanLine> findActiveByInventoryId(@Param("inventoryId") UUID inventoryId);

  @Query(
      """
      select line from FindingPlanLine line, InventoryFinding finding, FindingPlanSnapshot snapshot
       where finding.id in :findingIds
         and line.findingId=finding.id
         and snapshot.findingId=line.findingId and snapshot.findingRevision=line.findingRevision
         and snapshot.fingerprint=finding.maintenancePlanFingerprintSha256
         and snapshot.findingRevision=(
           select max(candidate.findingRevision) from FindingPlanSnapshot candidate
            where candidate.findingId=finding.id
              and candidate.fingerprint=finding.maintenancePlanFingerprintSha256)
       order by line.findingId,line.lineNo
      """)
  List<FindingPlanLine> findActiveByFindingIds(@Param("findingIds") Set<UUID> findingIds);
}
