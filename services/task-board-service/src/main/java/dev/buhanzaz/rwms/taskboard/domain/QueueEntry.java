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
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(
    name = "queue_entry",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_queue_entry_route", columnNames = {"task_id", "route_index"}),
    indexes =
        @Index(
            name = "idx_queue_entry_board",
            columnList = "queue_id,status,entry_type,queue_position"))
public class QueueEntry extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "task_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_queue_entry_task"))
  private BoardTask task;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "queue_id", foreignKey = @ForeignKey(name = "fk_queue_entry_queue"))
  private WorkQueue queue;

  @Column(name = "queue_code", length = 64)
  private String queueCode;

  @Column(name = "route_index", nullable = false)
  private int routeIndex;

  @Column(name = "queue_position", nullable = false)
  private int queuePosition;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, length = 16)
  private EntryType entryType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private EntryStatus status = EntryStatus.WAITING;

  @Column(name = "task_text", length = 2000)
  private String taskText;

  @Column(name = "planned_duration_minutes")
  private Integer plannedDurationMinutes;

  @Column(name = "active_started_at")
  private OffsetDateTime activeStartedAt;

  @Column(name = "paused_at")
  private OffsetDateTime pausedAt;

  @Column(name = "done_at")
  private OffsetDateTime doneAt;

  @Column(name = "active_work_seconds", nullable = false)
  private long activeWorkSeconds;

  @Enumerated(EnumType.STRING)
  @Column(name = "pause_origin", length = 16)
  private PauseOrigin pauseOrigin;

  public BoardTask getTask() {
    return task;
  }

  public void setTask(BoardTask v) {
    task = v;
  }

  public WorkQueue getQueue() {
    return queue;
  }

  public void setQueue(WorkQueue v) {
    queue = v;
  }

  public String getQueueCode() {
    return queueCode;
  }

  public void setQueueCode(String v) {
    queueCode = v;
  }

  public int getRouteIndex() {
    return routeIndex;
  }

  public void setRouteIndex(int v) {
    routeIndex = v;
  }

  public int getQueuePosition() {
    return queuePosition;
  }

  public void setQueuePosition(int v) {
    queuePosition = v;
  }

  public EntryType getEntryType() {
    return entryType;
  }

  public void setEntryType(EntryType v) {
    entryType = v;
  }

  public EntryStatus getStatus() {
    return status;
  }

  public void setStatus(EntryStatus v) {
    status = v;
  }

  public String getTaskText() {
    return taskText;
  }

  public void setTaskText(String v) {
    taskText = v;
  }

  public Integer getPlannedDurationMinutes() {
    return plannedDurationMinutes;
  }

  public void setPlannedDurationMinutes(Integer v) {
    plannedDurationMinutes = v;
  }

  public OffsetDateTime getActiveStartedAt() {
    return activeStartedAt;
  }

  public void setActiveStartedAt(OffsetDateTime v) {
    activeStartedAt = v;
  }

  public OffsetDateTime getPausedAt() {
    return pausedAt;
  }

  public void setPausedAt(OffsetDateTime v) {
    pausedAt = v;
  }

  public OffsetDateTime getDoneAt() {
    return doneAt;
  }

  public void setDoneAt(OffsetDateTime v) {
    doneAt = v;
  }

  public long getActiveWorkSeconds() {
    return activeWorkSeconds;
  }

  public void setActiveWorkSeconds(long v) {
    activeWorkSeconds = v;
  }

  public PauseOrigin getPauseOrigin() {
    return pauseOrigin;
  }

  public void setPauseOrigin(PauseOrigin v) {
    pauseOrigin = v;
  }
}
