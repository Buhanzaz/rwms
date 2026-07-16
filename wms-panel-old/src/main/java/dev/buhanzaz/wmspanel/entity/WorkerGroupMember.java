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
@Table(name = "WORKER_GROUP_MEMBER", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_WORKER_GROUP_MEMBER_WORKER_UNQ", columnNames = {"WORKER_GROUP_ID", "WORKER_ID"})
})
@Entity
public class WorkerGroupMember extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "WORKER_GROUP_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WorkerGroup workerGroup;

    @NotNull
    @JoinColumn(name = "WORKER_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Worker worker;

    @Column(name = "ROLE_IN_GROUP", length = 128)
    private String roleInGroup;

    @Column(name = "ACTIVE")
    private Boolean active = true;

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

    public String getRoleInGroup() {
        return roleInGroup;
    }

    public void setRoleInGroup(String roleInGroup) {
        this.roleInGroup = roleInGroup;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
