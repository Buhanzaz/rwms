package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

@JmixEntity
@Table(name = "TASK_ASSIGNMENT")
@Entity
public class TaskAssignment extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "QUEUE_ENTRY_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private QueueEntry queueEntry;

    @JoinColumn(name = "WORKER_GROUP_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private WorkerGroup workerGroup;

    @JoinColumn(name = "WORKER_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Worker worker;

    @Column(name = "ASSIGNED_AT")
    private OffsetDateTime assignedAt;

    @Column(name = "STARTED_AT")
    private OffsetDateTime startedAt;

    @Column(name = "PAUSED_AT")
    private OffsetDateTime pausedAt;

    @Column(name = "FINISHED_AT")
    private OffsetDateTime finishedAt;

    @NotNull
    @Column(name = "STATUS", nullable = false, length = 32)
    private String status = TaskAssignmentStatus.ACTIVE.getId();

    public QueueEntry getQueueEntry() {
        return queueEntry;
    }

    public void setQueueEntry(QueueEntry queueEntry) {
        this.queueEntry = queueEntry;
    }

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

    public OffsetDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(OffsetDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(OffsetDateTime pausedAt) {
        this.pausedAt = pausedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public TaskAssignmentStatus getStatus() {
        return TaskAssignmentStatus.fromId(status);
    }

    public void setStatus(TaskAssignmentStatus status) {
        this.status = status == null ? null : status.getId();
    }
}
