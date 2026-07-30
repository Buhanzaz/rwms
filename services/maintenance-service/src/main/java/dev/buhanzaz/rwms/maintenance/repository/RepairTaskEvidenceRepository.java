package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairTaskEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairTaskEvidenceRepository extends JpaRepository<RepairTaskEvidence, UUID> {
  List<RepairTaskEvidence> findAllByRepairIdOrderByRecordedAtAscEvidenceIdAsc(UUID repairId);
}
