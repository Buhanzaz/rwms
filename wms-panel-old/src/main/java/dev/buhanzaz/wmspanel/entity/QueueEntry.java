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
@Table(name = "QUEUE_ENTRY")
@Entity
public class QueueEntry extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "TASK_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private BoardTask task;

    @NotNull
    @JoinColumn(name = "QUEUE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkQueue queue;

    @Column(name = "ROUTE_INDEX", nullable = false)
    private Integer routeIndex = 0;

    @Column(name = "POSITION", nullable = false)
    private Integer position = 0;

    @NotNull
    @Column(name = "ENTRY_TYPE", nullable = false, length = 32)
    private String entryType = QueueEntryType.SHADOW.getId();

    @NotNull
    @Column(name = "STATUS", nullable = false, length = 32)
    private String status = QueueEntryStatus.WAITING.getId();

    @Column(name = "TASK_TEXT", length = 2000)
    private String taskText;

    @Column(name = "PLANNED_DURATION_MINUTES")
    private Integer plannedDurationMinutes;

    @Column(name = "ACTIVE_STARTED_AT")
    private OffsetDateTime activeStartedAt;

    @Column(name = "PAUSED_AT")
    private OffsetDateTime pausedAt;

    @Column(name = "DONE_AT")
    private OffsetDateTime doneAt;

    @Column(name = "ACTIVE_WORK_SECONDS")
    private Long activeWorkSeconds = 0L;

    public BoardTask getTask() {
        return task;
    }

    public void setTask(BoardTask task) {
        this.task = task;
    }

    public WorkQueue getQueue() {
        return queue;
    }

    public void setQueue(WorkQueue queue) {
        this.queue = queue;
    }

    public Integer getRouteIndex() {
        return routeIndex;
    }

    public void setRouteIndex(Integer routeIndex) {
        this.routeIndex = routeIndex;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public QueueEntryType getEntryType() {
        return QueueEntryType.fromId(entryType);
    }

    public void setEntryType(QueueEntryType entryType) {
        this.entryType = entryType == null ? null : entryType.getId();
    }

    public QueueEntryStatus getStatus() {
        return QueueEntryStatus.fromId(status);
    }

    public void setStatus(QueueEntryStatus status) {
        this.status = status == null ? null : status.getId();
    }

    public String getTaskText() {
        return taskText;
    }

    public void setTaskText(String taskText) {
        this.taskText = taskText;
    }

    public Integer getPlannedDurationMinutes() {
        return plannedDurationMinutes;
    }

    public void setPlannedDurationMinutes(Integer plannedDurationMinutes) {
        this.plannedDurationMinutes = plannedDurationMinutes;
    }

    public OffsetDateTime getActiveStartedAt() {
        return activeStartedAt;
    }

    public void setActiveStartedAt(OffsetDateTime activeStartedAt) {
        this.activeStartedAt = activeStartedAt;
    }

    public OffsetDateTime getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(OffsetDateTime pausedAt) {
        this.pausedAt = pausedAt;
    }

    public OffsetDateTime getDoneAt() {
        return doneAt;
    }

    public void setDoneAt(OffsetDateTime doneAt) {
        this.doneAt = doneAt;
    }

    public Long getActiveWorkSeconds() {
        return activeWorkSeconds;
    }

    public void setActiveWorkSeconds(Long activeWorkSeconds) {
        this.activeWorkSeconds = activeWorkSeconds;
    }
}
