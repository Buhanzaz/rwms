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
    name = "worker_group_member",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_worker_group_member",
            columnNames = {"worker_group_id", "worker_id"}))
public class WorkerGroupMember extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_group_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_worker_group_member_group"))
  private WorkerGroup workerGroup;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_worker_group_member_worker"))
  private Worker worker;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  public WorkerGroup getWorkerGroup() {
    return workerGroup;
  }

  public void setWorkerGroup(WorkerGroup workerGroup) {
    this.workerGroup = workerGroup;
  }

  public Worker getWorker() {
    return worker;
  }

  public void setWorker(Worker worker) {
    this.worker = worker;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}
