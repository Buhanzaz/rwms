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

@Entity
@Table(
    name = "work_queue_class_binding",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_work_queue_binding",
            columnNames = {"queue_id", "worker_class_id"}))
public class WorkQueueClassBinding extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "queue_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_work_queue_binding_queue"))
  private WorkQueue queue;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_class_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_work_queue_binding_class"))
  private WorkerClass workerClass;

  @Column(name = "stop_task_on_take", nullable = false)
  private boolean stopTaskOnTake;

  @Column(name = "binding_order", nullable = false)
  private int bindingOrder;

  @Enumerated(EnumType.STRING)
  @Column(name = "participation_policy", nullable = false, length = 16)
  private ParticipationPolicy participationPolicy = ParticipationPolicy.PRIMARY;

  @Column(name = "notify_on_primary_take", nullable = false)
  private boolean notifyOnPrimaryTake;

  public WorkQueue getQueue() {
    return queue;
  }

  public void setQueue(WorkQueue v) {
    queue = v;
  }

  public WorkerClass getWorkerClass() {
    return workerClass;
  }

  public void setWorkerClass(WorkerClass v) {
    workerClass = v;
  }

  public boolean isStopTaskOnTake() {
    return stopTaskOnTake;
  }

  public void setStopTaskOnTake(boolean v) {
    stopTaskOnTake = v;
  }

  public int getBindingOrder() {
    return bindingOrder;
  }

  public void setBindingOrder(int v) {
    bindingOrder = v;
  }

  public ParticipationPolicy getParticipationPolicy() {
    return participationPolicy;
  }

  public void setParticipationPolicy(ParticipationPolicy value) {
    participationPolicy = value;
  }

  public boolean isNotifyOnPrimaryTake() {
    return notifyOnPrimaryTake;
  }

  public void setNotifyOnPrimaryTake(boolean value) {
    notifyOnPrimaryTake = value;
  }
}
