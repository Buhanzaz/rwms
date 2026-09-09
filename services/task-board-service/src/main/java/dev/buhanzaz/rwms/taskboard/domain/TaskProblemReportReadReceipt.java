package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Personal, idempotent manager read marker for one immutable problem report. */
@Entity
@Table(
    name = "task_problem_report_read_receipt",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_task_problem_report_read_receipt_report_manager",
            columnNames = {"report_id", "manager_id"}),
    indexes = @Index(name = "idx_task_problem_report_read_receipt_manager", columnList = "manager_id,report_id"))
public class TaskProblemReportReadReceipt extends AbstractVersionedEntity {
  @Column(name = "report_id", nullable = false)
  private UUID reportId;

  @Column(name = "manager_id", nullable = false)
  private UUID managerId;

  @Column(name = "read_at", nullable = false)
  private OffsetDateTime readAt;

  protected TaskProblemReportReadReceipt() {}

  /** Creates one personal receipt; the database uniqueness constraint makes concurrent calls safe. */
  public static TaskProblemReportReadReceipt create(
      UUID receiptId, UUID reportId, UUID managerId, OffsetDateTime readAt) {
    TaskProblemReportReadReceipt receipt = new TaskProblemReportReadReceipt();
    receipt.assignReviewedId(Objects.requireNonNull(receiptId));
    receipt.reportId = Objects.requireNonNull(reportId);
    receipt.managerId = Objects.requireNonNull(managerId);
    receipt.readAt = Objects.requireNonNull(readAt);
    return receipt;
  }

  public UUID getReportId() { return reportId; }

  public UUID getManagerId() { return managerId; }

  public OffsetDateTime getReadAt() { return readAt; }
}
