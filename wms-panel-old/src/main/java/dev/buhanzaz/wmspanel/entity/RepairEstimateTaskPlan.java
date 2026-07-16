package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

@JmixEntity
@Entity
@Table(name = "REPAIR_ESTIMATE_TASK_PLAN")
public class RepairEstimateTaskPlan extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "ESTIMATE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RepairEstimate estimate;

    @NotNull
    @JoinColumn(name = "ESTIMATE_LINE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RepairEstimateLine estimateLine;

    @JoinColumn(name = "REPAIR_PROCESS_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RepairProcess repairProcess;

    @JoinColumn(name = "QUEUE_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private WorkQueue queue;

    @Column(name = "GROUP_COMMENT", length = 2000)
    private String groupComment;

    @JoinColumn(name = "FOLLOW_UP_NODE_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RepairEstimateCatalogNode followUpNode;

    @JoinColumn(name = "GENERATED_BOARD_TASK_ID", unique = true)
    @OneToOne(fetch = FetchType.LAZY)
    private BoardTask generatedBoardTask;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "GENERATION_STATUS", nullable = false, length = 32)
    private RepairEstimateTaskPlanGenerationStatus generationStatus = RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION;

    @Column(name = "SORT_ORDER", nullable = false)
    private Integer sortOrder = 0;

    @NotNull
    @Column(name = "ACTIVE", nullable = false)
    private Boolean active = true;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @OneToMany(mappedBy = "taskPlan")
    private List<RepairProcessTaskLine> taskLines = new ArrayList<>();

    @InstanceName
    public String getDisplayName() {
        String description = estimateLine == null ? null : estimateLine.getDescription();
        if (description == null || description.isBlank()) {
            description = "План сметы";
        }
        String queueName = queue == null ? null : queue.getName();
        if (queueName == null || queueName.isBlank()) {
            return description;
        }
        return description + " / " + queueName;
    }

    public RepairEstimate getEstimate() {
        return estimate;
    }

    public void setEstimate(RepairEstimate estimate) {
        this.estimate = estimate;
    }

    public RepairEstimateLine getEstimateLine() {
        return estimateLine;
    }

    public void setEstimateLine(RepairEstimateLine estimateLine) {
        this.estimateLine = estimateLine;
    }

    public RepairProcess getRepairProcess() {
        return repairProcess;
    }

    public void setRepairProcess(RepairProcess repairProcess) {
        this.repairProcess = repairProcess;
    }

    public WorkQueue getQueue() {
        return queue;
    }

    public void setQueue(WorkQueue queue) {
        this.queue = queue;
    }

    public String getGroupComment() {
        return groupComment;
    }

    public void setGroupComment(String groupComment) {
        this.groupComment = groupComment;
    }

    public RepairEstimateCatalogNode getFollowUpNode() {
        return followUpNode;
    }

    public void setFollowUpNode(RepairEstimateCatalogNode followUpNode) {
        this.followUpNode = followUpNode;
    }

    public BoardTask getGeneratedBoardTask() {
        return generatedBoardTask;
    }

    public void setGeneratedBoardTask(BoardTask generatedBoardTask) {
        this.generatedBoardTask = generatedBoardTask;
    }

    public RepairEstimateTaskPlanGenerationStatus getGenerationStatus() {
        return generationStatus;
    }

    public void setGenerationStatus(RepairEstimateTaskPlanGenerationStatus generationStatus) {
        this.generationStatus = generationStatus;
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

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<RepairProcessTaskLine> getTaskLines() {
        return taskLines;
    }

    public void setTaskLines(List<RepairProcessTaskLine> taskLines) {
        this.taskLines = taskLines;
    }

    @PrePersist
    @PreUpdate
    private void normalizeBeforeSave() {
        groupComment = trimToNull(groupComment);
        comment = trimToNull(comment);
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (active == null) {
            active = true;
        }
        if (generationStatus == null) {
            generationStatus = RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
