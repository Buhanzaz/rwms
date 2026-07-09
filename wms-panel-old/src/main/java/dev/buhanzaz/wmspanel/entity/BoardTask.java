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
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

@JmixEntity
@Table(name = "BOARD_TASK")
@Entity
public class BoardTask extends FullAuditEntity {

    @InstanceName
    @NotNull
    @Column(name = "TITLE", nullable = false, length = 200)
    private String title;

    @Column(name = "UNIT_NUMBER", length = 64)
    private String unitNumber;

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @JoinColumn(name = "RENTAL_ITEM_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalItem rentalItem;

    @JoinColumn(name = "REPAIR_PROCESS_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RepairProcess repairProcess;

    @Column(name = "DESCRIPTION", length = 2000)
    private String description;

    @NotNull
    @Column(name = "STATUS", nullable = false, length = 32)
    private String status = BoardTaskStatus.ACTIVE.getId();

    @Enumerated(EnumType.STRING)
    @Column(name = "TASK_KIND", length = 32)
    private RepairProcessTaskKind taskKind = RepairProcessTaskKind.REPAIR_WORK;

    @Column(name = "PLANNED_DURATION_MINUTES")
    private Integer plannedDurationMinutes;

    @Column(name = "DEADLINE_AT")
    private OffsetDateTime deadlineAt;

    @Column(name = "DONE_AT")
    private OffsetDateTime doneAt;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUnitNumber() {
        return unitNumber;
    }

    public void setUnitNumber(String unitNumber) {
        this.unitNumber = unitNumber;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public RentalItem getRentalItem() {
        return rentalItem;
    }

    public void setRentalItem(RentalItem rentalItem) {
        this.rentalItem = rentalItem;
    }

    public RepairProcess getRepairProcess() {
        return repairProcess;
    }

    public void setRepairProcess(RepairProcess repairProcess) {
        this.repairProcess = repairProcess;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BoardTaskStatus getStatus() {
        return BoardTaskStatus.fromId(status);
    }

    public void setStatus(BoardTaskStatus status) {
        this.status = status == null ? null : status.getId();
    }

    public RepairProcessTaskKind getTaskKind() {
        return taskKind;
    }

    public void setTaskKind(RepairProcessTaskKind taskKind) {
        this.taskKind = taskKind;
    }

    public Integer getPlannedDurationMinutes() {
        return plannedDurationMinutes;
    }

    public void setPlannedDurationMinutes(Integer plannedDurationMinutes) {
        this.plannedDurationMinutes = plannedDurationMinutes;
    }

    public OffsetDateTime getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(OffsetDateTime deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public OffsetDateTime getDoneAt() {
        return doneAt;
    }

    public void setDoneAt(OffsetDateTime doneAt) {
        this.doneAt = doneAt;
    }
}
