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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

@JmixEntity
@Entity
@Table(name = "REPAIR_PROCESS")
public class RepairProcess extends FullAuditEntity {

    @JoinColumn(name = "ESTIMATE_ID")
    @OneToOne(fetch = FetchType.LAZY)
    private RepairEstimate estimate;

    @NotNull
    @JoinColumn(name = "RENTAL_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItem rentalItem;

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 32)
    private RepairProcessStatus status = RepairProcessStatus.ACTIVE;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "PROCESS_KIND", nullable = false, length = 32)
    private RepairProcessKind processKind = RepairProcessKind.ESTIMATE_REPAIR;

    @JoinColumn(name = "SOURCE_PROCESS_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RepairProcess sourceProcess;

    @JoinColumn(name = "REQUESTED_WORKER_GROUP_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private WorkerGroup requestedWorkerGroup;

    @JoinColumn(name = "REQUESTED_WORKER_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Worker requestedWorker;

    @Column(name = "MOVE_TO_REPAIR_REQUIRED")
    private Boolean moveToRepairRequired = false;

    @Column(name = "MOVE_TO_REPAIR_DONE")
    private Boolean moveToRepairDone = false;

    @Column(name = "MOVE_TO_REPAIR_CANCELLED")
    private Boolean moveToRepairCancelled = false;

    @Column(name = "MOVE_FROM_REPAIR_REQUIRED")
    private Boolean moveFromRepairRequired = false;

    @Column(name = "MOVE_FROM_REPAIR_DONE")
    private Boolean moveFromRepairDone = false;

    @Column(name = "MOVE_FROM_REPAIR_CANCELLED")
    private Boolean moveFromRepairCancelled = false;

    @Column(name = "ACCEPTED_AT")
    private OffsetDateTime acceptedAt;

    @Column(name = "ACCEPTED_BY", length = 255)
    private String acceptedBy;

    @Column(name = "ACCEPTANCE_COMMENT", length = 2000)
    private String acceptanceComment;

    @Column(name = "COMMENT_", length = 2000)
    private String comment;

    @InstanceName
    public String getDisplayName() {
        String itemNumber = rentalItem == null ? null : rentalItem.getNumber();
        String prefix = processKind == RepairProcessKind.REWORK ? "Rework process" : "Repair process";
        return itemNumber == null || itemNumber.isBlank() ? prefix : prefix + " " + itemNumber;
    }

    public RepairEstimate getEstimate() {
        return estimate;
    }

    public void setEstimate(RepairEstimate estimate) {
        this.estimate = estimate;
    }

    public RentalItem getRentalItem() {
        return rentalItem;
    }

    public void setRentalItem(RentalItem rentalItem) {
        this.rentalItem = rentalItem;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public RepairProcessStatus getStatus() {
        return status;
    }

    public void setStatus(RepairProcessStatus status) {
        this.status = status;
    }

    public RepairProcessKind getProcessKind() {
        return processKind;
    }

    public void setProcessKind(RepairProcessKind processKind) {
        this.processKind = processKind;
    }

    public RepairProcess getSourceProcess() {
        return sourceProcess;
    }

    public void setSourceProcess(RepairProcess sourceProcess) {
        this.sourceProcess = sourceProcess;
    }

    public WorkerGroup getRequestedWorkerGroup() {
        return requestedWorkerGroup;
    }

    public void setRequestedWorkerGroup(WorkerGroup requestedWorkerGroup) {
        this.requestedWorkerGroup = requestedWorkerGroup;
    }

    public Worker getRequestedWorker() {
        return requestedWorker;
    }

    public void setRequestedWorker(Worker requestedWorker) {
        this.requestedWorker = requestedWorker;
    }

    public Boolean getMoveToRepairRequired() {
        return moveToRepairRequired;
    }

    public void setMoveToRepairRequired(Boolean moveToRepairRequired) {
        this.moveToRepairRequired = moveToRepairRequired;
    }

    public Boolean getMoveToRepairDone() {
        return moveToRepairDone;
    }

    public void setMoveToRepairDone(Boolean moveToRepairDone) {
        this.moveToRepairDone = moveToRepairDone;
    }

    public Boolean getMoveToRepairCancelled() {
        return moveToRepairCancelled;
    }

    public void setMoveToRepairCancelled(Boolean moveToRepairCancelled) {
        this.moveToRepairCancelled = moveToRepairCancelled;
    }

    public Boolean getMoveFromRepairRequired() {
        return moveFromRepairRequired;
    }

    public void setMoveFromRepairRequired(Boolean moveFromRepairRequired) {
        this.moveFromRepairRequired = moveFromRepairRequired;
    }

    public Boolean getMoveFromRepairDone() {
        return moveFromRepairDone;
    }

    public void setMoveFromRepairDone(Boolean moveFromRepairDone) {
        this.moveFromRepairDone = moveFromRepairDone;
    }

    public Boolean getMoveFromRepairCancelled() {
        return moveFromRepairCancelled;
    }

    public void setMoveFromRepairCancelled(Boolean moveFromRepairCancelled) {
        this.moveFromRepairCancelled = moveFromRepairCancelled;
    }

    public OffsetDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(OffsetDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public String getAcceptedBy() {
        return acceptedBy;
    }

    public void setAcceptedBy(String acceptedBy) {
        this.acceptedBy = acceptedBy;
    }

    public String getAcceptanceComment() {
        return acceptanceComment;
    }

    public void setAcceptanceComment(String acceptanceComment) {
        this.acceptanceComment = acceptanceComment;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (status == null) {
            status = RepairProcessStatus.ACTIVE;
        }
        if (processKind == null) {
            processKind = RepairProcessKind.ESTIMATE_REPAIR;
        }
        if (moveToRepairRequired == null) {
            moveToRepairRequired = false;
        }
        if (moveToRepairDone == null) {
            moveToRepairDone = false;
        }
        if (moveToRepairCancelled == null) {
            moveToRepairCancelled = false;
        }
        if (moveFromRepairRequired == null) {
            moveFromRepairRequired = false;
        }
        if (moveFromRepairDone == null) {
            moveFromRepairDone = false;
        }
        if (moveFromRepairCancelled == null) {
            moveFromRepairCancelled = false;
        }
    }
}
