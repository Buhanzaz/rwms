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

/**
 * The global workforce template for one GENERAL queue definition.
 *
 * <p>Each warehouse receives a derived {@link WorkQueueClassBinding} with
 * its own stable identity, because historic tasks refer to that physical queue
 * projection. This aggregate is the only editable source of the class
 * relation.
 */
@Entity
@Table(
    name = "queue_definition_class_binding",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_queue_definition_binding",
            columnNames = {"definition_id", "worker_class_id"}))
public class QueueDefinitionClassBinding extends AbstractVersionedEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "definition_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_queue_definition_binding_definition"))
  private QueueDefinition definition;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_class_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_queue_definition_binding_class"))
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

  public QueueDefinition getDefinition() {
    return definition;
  }

  public void setDefinition(QueueDefinition value) {
    definition = value;
  }

  public WorkerClass getWorkerClass() {
    return workerClass;
  }

  public void setWorkerClass(WorkerClass value) {
    workerClass = value;
  }

  public boolean isStopTaskOnTake() {
    return stopTaskOnTake;
  }

  public void setStopTaskOnTake(boolean value) {
    stopTaskOnTake = value;
  }

  public int getBindingOrder() {
    return bindingOrder;
  }

  public void setBindingOrder(int value) {
    bindingOrder = value;
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
