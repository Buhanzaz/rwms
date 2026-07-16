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
@Table(name = "TASK_TIME_EVENT")
@Entity
public class TaskTimeEvent extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "QUEUE_ENTRY_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private QueueEntry queueEntry;

    @JoinColumn(name = "WORKER_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Worker worker;

    @NotNull
    @Column(name = "EVENT_TYPE", nullable = false, length = 32)
    private String eventType;

    @Column(name = "REASON", length = 1000)
    private String reason;

    @Column(name = "CREATED_AT")
    private OffsetDateTime createdAt;

    public QueueEntry getQueueEntry() {
        return queueEntry;
    }

    public void setQueueEntry(QueueEntry queueEntry) {
        this.queueEntry = queueEntry;
    }

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker;
    }

    public TaskTimeEventType getEventType() {
        return TaskTimeEventType.fromId(eventType);
    }

    public void setEventType(TaskTimeEventType eventType) {
        this.eventType = eventType == null ? null : eventType.getId();
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
