package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.GroupKpiSegmentDay;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupKpiSegmentDayRepository extends JpaRepository<GroupKpiSegmentDay, UUID> {
  Optional<GroupKpiSegmentDay> findBySegmentIdAndEvidenceId(UUID segmentId, UUID evidenceId);

  List<GroupKpiSegmentDay> findAllBySegmentId(UUID segmentId);
}
