package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Table(name = "WORK_QUEUE_WORKER_GROUP", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_WORK_QUEUE_WORKER_GROUP_UNQ", columnNames = {"QUEUE_ID", "WORKER_CLASS_ID"})
})
@Entity
public class WorkQueueWorkerGroup extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "QUEUE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkQueue queue;

    @NotNull
    @JoinColumn(name = "WORKER_CLASS_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkerClass workerClass;

    @Column(name = "STOP_TASK_ON_TAKE")
    private Boolean stopTaskOnTake = false;

    public WorkQueue getQueue() {
        return queue;
    }

    public void setQueue(WorkQueue queue) {
        this.queue = queue;
    }

    public WorkerClass getWorkerClass() {
        return workerClass;
    }

    public void setWorkerClass(WorkerClass workerClass) {
        this.workerClass = workerClass;
    }

    public Boolean getStopTaskOnTake() {
        return stopTaskOnTake;
    }

    public void setStopTaskOnTake(Boolean stopTaskOnTake) {
        this.stopTaskOnTake = stopTaskOnTake;
    }
}
