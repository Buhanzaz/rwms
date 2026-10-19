package dev.buhanzaz.rwms.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Opaque task-board-owned preparation task reference for a shipment line. */
@Entity
@Table(
    name = "logistics_task_reference",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_logistics_task_reference_line", columnNames = "line_id"),
      @UniqueConstraint(
          name = "uk_logistics_task_reference_external",
          columnNames = "external_task_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsTaskReference {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_task_reference_document"))
  private LogisticsDocument document;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "line_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_task_reference_line"))
  private LogisticsDocumentLine line;

  @Column(name = "external_task_id", nullable = false)
  private UUID externalTaskId;

  @Column(name = "task_id")
  private UUID taskId;

  @Column(name = "task_version")
  private Long taskVersion;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_state", nullable = false, length = 24)
  private LogisticsTaskReferenceState taskState;

  @Column(name = "done_at")
  private OffsetDateTime doneAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static LogisticsTaskReference pending(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID externalTaskId,
      UUID warehouseId,
      OffsetDateTime createdAt) {
    if (document == null
        || line == null
        || externalTaskId == null
        || warehouseId == null
        || createdAt == null) {
      throw new IllegalArgumentException("Task reference ownership and timing are required");
    }
    LogisticsTaskReference reference = new LogisticsTaskReference();
    reference.document = document;
    reference.line = line;
    reference.externalTaskId = externalTaskId;
    reference.warehouseId = warehouseId;
    reference.taskState = LogisticsTaskReferenceState.PENDING;
    reference.createdAt = createdAt;
    reference.updatedAt = createdAt;
    return reference;
  }

  public void register(
      UUID nextTaskId, long nextTaskVersion, String status, OffsetDateTime nextDoneAt) {
    if (nextTaskId == null || nextTaskVersion < 0 || !"ACTIVE".equals(status)) {
      throw new IllegalArgumentException("Task registration result is invalid");
    }
    if (taskState != LogisticsTaskReferenceState.PENDING
        && taskState != LogisticsTaskReferenceState.REGISTERED) {
      throw new IllegalStateException("Task reference cannot be registered in its current state");
    }
    taskId = nextTaskId;
    taskVersion = nextTaskVersion;
    taskState = LogisticsTaskReferenceState.REGISTERED;
    doneAt = nextDoneAt;
    updatedAt = now();
  }

  public void markDone(long nextTaskVersion, OffsetDateTime completedAt) {
    if (taskState != LogisticsTaskReferenceState.REGISTERED
        || nextTaskVersion < taskVersion
        || completedAt == null) {
      throw new IllegalStateException("Task reference cannot become done");
    }
    taskVersion = nextTaskVersion;
    doneAt = completedAt;
    taskState = LogisticsTaskReferenceState.DONE;
    updatedAt = now();
  }

  public void cancel(long nextTaskVersion) {
    if ((taskState != LogisticsTaskReferenceState.PENDING
            && taskState != LogisticsTaskReferenceState.REGISTERED
            && taskState != LogisticsTaskReferenceState.DONE)
        || (taskVersion != null && nextTaskVersion < taskVersion)) {
      throw new IllegalStateException("Task reference cannot be cancelled");
    }
    if (taskVersion != null) taskVersion = nextTaskVersion;
    taskState = LogisticsTaskReferenceState.CANCELLED;
    updatedAt = now();
  }

  /** Records broad source-owned cancellation after completed inventory displaced the document. */
  public void cancelForCompletedInventory(long nextTaskVersion) {
    if (taskState == LogisticsTaskReferenceState.DONE) {
      throw new IllegalStateException("Completed task reference remains historical");
    }
    if (taskVersion != null && nextTaskVersion < taskVersion) {
      throw new IllegalArgumentException("Task reference version moved backwards");
    }
    if (taskVersion != null) taskVersion = nextTaskVersion;
    taskState = LogisticsTaskReferenceState.CANCELLED;
    updatedAt = now();
  }

  /** Reconciles a remote DONE result without converting it into an inventory cancellation. */
  public void preserveCompletedForInventory(long nextTaskVersion, OffsetDateTime completedAt) {
    if (nextTaskVersion < 0 || completedAt == null) {
      throw new IllegalArgumentException("Completed task-board snapshot is invalid");
    }
    if (taskVersion != null && nextTaskVersion < taskVersion) {
      throw new IllegalArgumentException("Task reference version moved backwards");
    }
    taskVersion = nextTaskVersion;
    doneAt = completedAt;
    taskState = LogisticsTaskReferenceState.DONE;
    updatedAt = now();
  }

  public void conflict() {
    if (taskState == LogisticsTaskReferenceState.CANCELLED) {
      throw new IllegalStateException("Cancelled task reference cannot become conflicted");
    }
    taskState = LogisticsTaskReferenceState.CONFLICT;
    updatedAt = now();
  }

  public void requireReconciliation() {
    if (taskState == LogisticsTaskReferenceState.CANCELLED) {
      throw new IllegalStateException("Cancelled task reference cannot require reconciliation");
    }
    taskState = LogisticsTaskReferenceState.RECONCILIATION_REQUIRED;
    updatedAt = now();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
