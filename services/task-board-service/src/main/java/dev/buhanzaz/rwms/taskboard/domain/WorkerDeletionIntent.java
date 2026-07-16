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
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(
    name = "worker_deletion_intent",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_worker_deletion_intent_worker", columnNames = "worker_id"))
public class WorkerDeletionIntent extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_worker_deletion_intent_worker"))
  private Worker worker;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private WorkerDeletionStatus status;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Worker getWorker() {
    return worker;
  }

  public void setWorker(Worker worker) {
    this.worker = worker;
  }

  public WorkerDeletionStatus getStatus() {
    return status;
  }

  public void setStatus(WorkerDeletionStatus status) {
    this.status = status;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
