package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "worker_class_assignment",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_worker_class_assignment",
            columnNames = {"worker_id", "worker_class_id"}))
public class WorkerClassAssignment extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_worker_class_assignment_worker"))
  private Worker worker;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_class_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_worker_class_assignment_class"))
  private WorkerClass workerClass;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "comment_text", length = 1000)
  private String comment;

  public Worker getWorker() {
    return worker;
  }

  public void setWorker(Worker worker) {
    this.worker = worker;
  }

  public WorkerClass getWorkerClass() {
    return workerClass;
  }

  public void setWorkerClass(WorkerClass workerClass) {
    this.workerClass = workerClass;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }
}
