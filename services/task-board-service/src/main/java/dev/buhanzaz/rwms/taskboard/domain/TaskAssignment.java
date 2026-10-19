package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Versioned assignment of a route entry to a worker group and, optionally, an exact worker. */
@Entity
@Table(
    name = "task_assignment",
    indexes =
        @Index(
            name = "idx_task_assignment_worker_status",
            columnList = "worker_id,status"))
public class TaskAssignment extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "queue_entry_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_task_assignment_entry"))
  private QueueEntry queueEntry;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "worker_group_id",
      foreignKey = @ForeignKey(name = "fk_task_assignment_group"))
  private WorkerGroup workerGroup;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "worker_id", foreignKey = @ForeignKey(name = "fk_task_assignment_worker"))
  private Worker worker;

  @Column(name = "worker_name_snapshot", length = 256)
  private String workerNameSnapshot;

  @Column(name = "group_name_snapshot", length = 128)
  private String groupNameSnapshot;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private AssignmentStatus status;

  @Column(name = "assigned_at", nullable = false)
  private OffsetDateTime assignedAt;

  @Column(name = "started_at")
  private OffsetDateTime startedAt;

  @Column(name = "paused_at")
  private OffsetDateTime pausedAt;

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  @Column(name = "primary_participation")
  private Boolean primaryParticipation;

  /** Null denotes older evidence that did not distinguish TAKE from a secondary JOIN. */
  public Boolean getPrimaryParticipation() {
    return primaryParticipation;
  }

  /**
   * Records the participation role when this assignment is created, independent of later class
   * edits.
   */
  public void recordParticipation(Boolean primary) {
    if (primaryParticipation != null && !primaryParticipation.equals(primary)) {
      throw new IllegalStateException("Роль существующего назначения неизменяема");
    }
    primaryParticipation = primary;
  }

  public QueueEntry getQueueEntry() {
    return queueEntry;
  }

  public void setQueueEntry(QueueEntry v) {
    queueEntry = v;
  }

  public WorkerGroup getWorkerGroup() {
    return workerGroup;
  }

  public void setWorkerGroup(WorkerGroup v) {
    workerGroup = v;
  }

  public Worker getWorker() {
    return worker;
  }

  public void setWorker(Worker v) {
    worker = v;
  }

  public String getWorkerNameSnapshot() {
    return workerNameSnapshot;
  }

  public void setWorkerNameSnapshot(String v) {
    workerNameSnapshot = v;
  }

  public String getGroupNameSnapshot() {
    return groupNameSnapshot;
  }

  public void setGroupNameSnapshot(String v) {
    groupNameSnapshot = v;
  }

  public AssignmentStatus getStatus() {
    return status;
  }

  public void setStatus(AssignmentStatus v) {
    status = v;
  }

  public OffsetDateTime getAssignedAt() {
    return assignedAt;
  }

  public void setAssignedAt(OffsetDateTime v) {
    assignedAt = v;
  }

  public OffsetDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(OffsetDateTime v) {
    startedAt = v;
  }

  public OffsetDateTime getPausedAt() {
    return pausedAt;
  }

  public void setPausedAt(OffsetDateTime v) {
    pausedAt = v;
  }

  public OffsetDateTime getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(OffsetDateTime v) {
    finishedAt = v;
  }
}
