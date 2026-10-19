package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for service-local finding plan snapshot persistence.
 */
public interface FindingPlanSnapshotRepository
    extends JpaRepository<FindingPlanSnapshot, FindingPlanSnapshot.Key> {
  Optional<FindingPlanSnapshot> findByFindingIdAndFindingRevision(
      UUID findingId, long findingRevision);

  Optional<FindingPlanSnapshot> findFirstByFindingIdAndFingerprintOrderByFindingRevisionDesc(
      UUID findingId, String fingerprint);

  @Query(
      """
      select snapshot from FindingPlanSnapshot snapshot, InventoryFinding finding
       where finding.id in :findingIds
         and snapshot.findingId=finding.id
         and snapshot.fingerprint=finding.maintenancePlanFingerprintSha256
         and snapshot.findingRevision=(
           select max(candidate.findingRevision) from FindingPlanSnapshot candidate
            where candidate.findingId=finding.id
              and candidate.fingerprint=finding.maintenancePlanFingerprintSha256)
       order by snapshot.findingId
      """)
  List<FindingPlanSnapshot> findActiveByFindingIds(
      @Param("findingIds") Set<UUID> findingIds);
}
