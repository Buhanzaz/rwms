package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.TaskProblemReportReadReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for personal manager report read markers. */
public interface TaskProblemReportReadReceiptRepository
    extends JpaRepository<TaskProblemReportReadReceipt, UUID> {
  Optional<TaskProblemReportReadReceipt> findByReportIdAndManagerId(UUID reportId, UUID managerId);
}
