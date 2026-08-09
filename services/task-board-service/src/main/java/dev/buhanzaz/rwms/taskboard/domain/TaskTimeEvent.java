package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only execution-time transition used to reconstruct task and KPI timing. */
@Entity
@Table(name = "task_time_event")
public class TaskTimeEvent extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "queue_entry_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_task_time_event_entry"))
  private QueueEntry queueEntry;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "worker_id", foreignKey = @ForeignKey(name = "fk_task_time_event_worker"))
  private Worker worker;

  @Column(name = "worker_name_snapshot", length = 256)
  private String workerNameSnapshot;

  @Column(name = "group_name_snapshot", length = 128)
  private String groupNameSnapshot;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private TimeEventType eventType;

  @Column(name = "reason", length = 1000)
  private String reason;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "related_entry_id")
  private UUID relatedEntryId;

  public QueueEntry getQueueEntry() {
    return queueEntry;
  }

  public void setQueueEntry(QueueEntry v) {
    queueEntry = v;
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

  public TimeEventType getEventType() {
    return eventType;
  }

  public void setEventType(TimeEventType v) {
    eventType = v;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String v) {
    reason = v;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime v) {
    createdAt = v;
  }

  public UUID getRelatedEntryId() {
    return relatedEntryId;
  }

  public void setRelatedEntryId(UUID v) {
    relatedEntryId = v;
  }
}
