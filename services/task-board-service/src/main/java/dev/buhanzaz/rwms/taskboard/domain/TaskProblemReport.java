package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable worker-originated problem report for one active task entry.
 *
 * <p>The report is also the replay receipt for its worker command. Media reservations remain in
 * {@code worker_task_evidence} because task-board already owns their media correlation, while a
 * nullable report foreign key prevents those photos from becoming completion evidence.
 */
@Entity
@Table(
    name = "task_problem_report",
    indexes = {
      @Index(name = "idx_task_problem_report_warehouse_recorded", columnList = "warehouse_id,recorded_at,id"),
      @Index(name = "idx_task_problem_report_author", columnList = "worker_id,warehouse_id,recorded_at")
    })
public class TaskProblemReport extends AbstractVersionedEntity {
  @NotNull
  @Column(name = "entry_id", nullable = false)
  private UUID entryId;

  @NotNull
  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @NotNull
  @Column(name = "worker_id", nullable = false)
  private UUID workerId;

  @NotBlank
  @Column(name = "worker_name", nullable = false, length = 256)
  private String workerName;

  @NotBlank
  @Column(name = "entry_title", nullable = false, length = 512)
  private String entryTitle;

  @Column(name = "route_index", nullable = false)
  private int routeIndex;

  @NotBlank
  @Column(name = "comment_text", nullable = false, length = 2000)
  private String comment;

  @NotNull
  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @NotNull
  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  protected TaskProblemReport() {}

  /** Creates the server-derived immutable report and assigns its reviewed client operation ID. */
  public static TaskProblemReport create(
      UUID reportId,
      UUID entryId,
      UUID taskId,
      UUID warehouseId,
      UUID workerId,
      String workerName,
      String entryTitle,
      int routeIndex,
      String comment,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt) {
    if (routeIndex < 0) throw new IllegalArgumentException("Route index must not be negative");
    TaskProblemReport report = new TaskProblemReport();
    report.assignReviewedId(Objects.requireNonNull(reportId));
    report.entryId = Objects.requireNonNull(entryId);
    report.taskId = Objects.requireNonNull(taskId);
    report.warehouseId = Objects.requireNonNull(warehouseId);
    report.workerId = Objects.requireNonNull(workerId);
    report.workerName = requireText(workerName, "Worker name");
    report.entryTitle = requireText(entryTitle, "Entry title");
    report.routeIndex = routeIndex;
    report.comment = requireText(comment, "Comment");
    report.occurredAt = Objects.requireNonNull(occurredAt);
    report.recordedAt = Objects.requireNonNull(recordedAt);
    return report;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    return value;
  }

  public UUID getEntryId() { return entryId; }

  public UUID getTaskId() { return taskId; }

  public UUID getWarehouseId() { return warehouseId; }

  public UUID getWorkerId() { return workerId; }

  public String getWorkerName() { return workerName; }

  public String getEntryTitle() { return entryTitle; }

  public int getRouteIndex() { return routeIndex; }

  public String getComment() { return comment; }

  public OffsetDateTime getOccurredAt() { return occurredAt; }

  public OffsetDateTime getRecordedAt() { return recordedAt; }
}
