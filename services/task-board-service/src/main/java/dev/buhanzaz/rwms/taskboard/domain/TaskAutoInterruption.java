package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

/**
 * Recoverable record of an automatic interruption applied when operational responsibility moves.
 */
@Entity
@Table(
    name = "task_auto_interruption",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_auto_interruption",
            columnNames = {"worker_id", "interrupted_entry_id", "interrupting_entry_id"}))
public class TaskAutoInterruption extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_auto_interruption_worker"))
  private Worker worker;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "interrupted_entry_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_auto_interruption_interrupted"))
  private QueueEntry interruptedEntry;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "interrupting_entry_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_auto_interruption_interrupting"))
  private QueueEntry interruptingEntry;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "resolved_at")
  private OffsetDateTime resolvedAt;

  public Worker getWorker() {
    return worker;
  }

  public void setWorker(Worker v) {
    worker = v;
  }

  public QueueEntry getInterruptedEntry() {
    return interruptedEntry;
  }

  public void setInterruptedEntry(QueueEntry v) {
    interruptedEntry = v;
  }

  public QueueEntry getInterruptingEntry() {
    return interruptingEntry;
  }

  public void setInterruptingEntry(QueueEntry v) {
    interruptingEntry = v;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean v) {
    active = v;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime v) {
    createdAt = v;
  }

  public OffsetDateTime getResolvedAt() {
    return resolvedAt;
  }

  public void setResolvedAt(OffsetDateTime v) {
    resolvedAt = v;
  }
}
