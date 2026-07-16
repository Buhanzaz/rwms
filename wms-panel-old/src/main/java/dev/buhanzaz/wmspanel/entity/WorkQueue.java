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
@Table(name = "WORK_QUEUE", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_WORK_QUEUE_UNQ_WAREHOUSE_CODE", columnNames = {"WAREHOUSE_ID", "CODE"})
})
@Entity
public class WorkQueue extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @NotNull
    @Column(name = "CODE", nullable = false, length = 64)
    private String code;

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false, length = 128)
    private String name;

    @Column(name = "DESCRIPTION", length = 1000)
    private String description;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    @Column(name = "COLLAPSED")
    private Boolean collapsed = false;

    @Column(name = "HIDDEN")
    private Boolean hidden = false;

    @Column(name = "SINK_QUEUE")
    private Boolean sinkQueue = false;

    @Column(name = "QUEUE_KIND", length = 32)
    private String queueKind = WorkQueueKind.REPAIR.getId();

    @Column(name = "NOTIFICATION_THRESHOLD")
    private Integer notificationThreshold;

    @Column(name = "NOTIFY_WHEN_THRESHOLD_REACHED")
    private Boolean notifyWhenThresholdReached = false;

    @Column(name = "HOLDING_PERIOD_MINUTES")
    private Integer holdingPeriodMinutes;

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Boolean getCollapsed() {
        return collapsed;
    }

    public void setCollapsed(Boolean collapsed) {
        this.collapsed = collapsed;
    }

    public Boolean getHidden() {
        return hidden;
    }

    public void setHidden(Boolean hidden) {
        this.hidden = hidden;
    }

    public Boolean getSinkQueue() {
        return sinkQueue;
    }

    public void setSinkQueue(Boolean sinkQueue) {
        this.sinkQueue = sinkQueue;
    }

    public WorkQueueKind getQueueKind() {
        return WorkQueueKind.fromId(queueKind);
    }

    public void setQueueKind(WorkQueueKind queueKind) {
        this.queueKind = queueKind == null ? null : queueKind.getId();
    }

    public Integer getNotificationThreshold() {
        return notificationThreshold;
    }

    public void setNotificationThreshold(Integer notificationThreshold) {
        this.notificationThreshold = notificationThreshold;
    }

    public Boolean getNotifyWhenThresholdReached() {
        return notifyWhenThresholdReached;
    }

    public void setNotifyWhenThresholdReached(Boolean notifyWhenThresholdReached) {
        this.notifyWhenThresholdReached = notifyWhenThresholdReached;
    }

    public Integer getHoldingPeriodMinutes() {
        return holdingPeriodMinutes;
    }

    public void setHoldingPeriodMinutes(Integer holdingPeriodMinutes) {
        this.holdingPeriodMinutes = holdingPeriodMinutes;
    }
}
