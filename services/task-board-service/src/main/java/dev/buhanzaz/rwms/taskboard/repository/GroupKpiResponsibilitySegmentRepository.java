package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.GroupKpiResponsibilitySegment;
import dev.buhanzaz.rwms.taskboard.domain.GroupKpiSegmentOutcome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupKpiResponsibilitySegmentRepository
    extends JpaRepository<GroupKpiResponsibilitySegment, UUID> {
  Optional<GroupKpiResponsibilitySegment> findByQueueEntryIdAndWorkerGroupIdAndOutcome(
      UUID queueEntryId, UUID workerGroupId, GroupKpiSegmentOutcome outcome);

  List<GroupKpiResponsibilitySegment> findAllByQueueEntryIdAndOutcome(
      UUID queueEntryId, GroupKpiSegmentOutcome outcome);

  List<GroupKpiResponsibilitySegment> findAllByWorkerGroupIdAndOutcome(
      UUID workerGroupId, GroupKpiSegmentOutcome outcome);
}
