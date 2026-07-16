package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
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
@Table(name = "WORKER_CLASS_ASSIGNMENT", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_WORKER_CLASS_ASSIGNMENT_UNQ", columnNames = {"WORKER_ID", "WORKER_CLASS_ID"})
})
@Entity
public class WorkerClassAssignment extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "WORKER_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Worker worker;

    @NotNull
    @JoinColumn(name = "WORKER_CLASS_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkerClass workerClass;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @InstanceName
    public String getDisplayName() {
        String workerName = worker == null ? null : worker.getDisplayName();
        String className = workerClass == null ? null : workerClass.getName();
        if (workerName == null && className == null) {
            return null;
        }
        return String.format("%s / %s", workerName == null ? "" : workerName, className == null ? "" : className).trim();
    }

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

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
