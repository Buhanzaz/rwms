package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.TaskProblemReport;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Locking persistence boundary for immutable worker problem-report receipts. */
public interface TaskProblemReportRepository extends JpaRepository<TaskProblemReport, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select report from TaskProblemReport report where report.id=:reportId")
  Optional<TaskProblemReport> findForUpdate(@Param("reportId") UUID reportId);
}
