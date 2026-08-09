package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairTaskEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for RepairTaskEvidence; business transitions remain in the owning service. */
public interface RepairTaskEvidenceRepository extends JpaRepository<RepairTaskEvidence, UUID> {
  List<RepairTaskEvidence> findAllByRepairIdOrderByRecordedAtAscEvidenceIdAsc(UUID repairId);
}
