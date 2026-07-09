package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Entity
@Table(name = "REPAIR_PROCESS_TASK_LINE")
public class RepairProcessTaskLine extends UuidEntity {

    @NotNull
    @JoinColumn(name = "TASK_PLAN_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RepairEstimateTaskPlan taskPlan;

    @NotNull
    @JoinColumn(name = "ESTIMATE_LINE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RepairEstimateLine estimateLine;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "LINE_TYPE", nullable = false, length = 32)
    private RepairEstimateLineType lineType = RepairEstimateLineType.WORK;

    @Column(name = "SORT_ORDER", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "PRIMARY_WORK_LINE", nullable = false)
    private Boolean primaryWorkLine = false;

    public RepairEstimateTaskPlan getTaskPlan() {
        return taskPlan;
    }

    public void setTaskPlan(RepairEstimateTaskPlan taskPlan) {
        this.taskPlan = taskPlan;
    }

    public RepairEstimateLine getEstimateLine() {
        return estimateLine;
    }

    public void setEstimateLine(RepairEstimateLine estimateLine) {
        this.estimateLine = estimateLine;
    }

    public RepairEstimateLineType getLineType() {
        return lineType;
    }

    public void setLineType(RepairEstimateLineType lineType) {
        this.lineType = lineType;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getPrimaryWorkLine() {
        return primaryWorkLine;
    }

    public void setPrimaryWorkLine(Boolean primaryWorkLine) {
        this.primaryWorkLine = primaryWorkLine;
    }
}
