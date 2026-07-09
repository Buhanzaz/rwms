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
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

@JmixEntity
@Entity
@Table(name = "RENTAL_ITEM_EVENT")
public class RentalItemEvent extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "RENTAL_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItem rentalItem;

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(name = "EVENT_TYPE", nullable = false, length = 32)
    private RentalItemEventType eventType = RentalItemEventType.MANUAL;

    @InstanceName
    @Column(name = "TITLE", nullable = false, length = 255)
    private String title;

    @Column(name = "COMMENT_", length = 2000)
    private String comment;

    @Column(name = "EVENT_DATE", nullable = false)
    private OffsetDateTime eventDate;

    @Column(name = "STATUS_BEFORE", length = 32)
    private String statusBefore;

    @Column(name = "STATUS_AFTER", length = 32)
    private String statusAfter;

    @Column(name = "ACTOR_USERNAME", length = 255)
    private String actorUsername;

    @Column(name = "ACTOR_DISPLAY_NAME", length = 255)
    private String actorDisplayName;

    @Column(name = "MOBILE_TASK_KEY", length = 255)
    private String mobileTaskKey;

    @Column(name = "MOBILE_CREATION_MODE", length = 64)
    private String mobileCreationMode;

    @Column(name = "SOURCE_", length = 64)
    private String source;

    @Column(name = "SOURCE_COMMENT", length = 2000)
    private String sourceComment;

    @Column(name = "EVENT_GROUP_KEY", length = 255)
    private String eventGroupKey;

    @Column(name = "EVENT_ORDER")
    private Integer eventOrder;

    @JoinColumn(name = "PARENT_EVENT_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalItemEvent parentEvent;

    @JoinColumn(name = "ESTIMATE_ID")
    @OneToOne(fetch = FetchType.LAZY)
    private RepairEstimate estimate;

    @JoinColumn(name = "REPAIR_PROCESS_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RepairProcess repairProcess;

    @JoinColumn(name = "BOARD_TASK_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private BoardTask boardTask;

    @JoinColumn(name = "QUEUE_ENTRY_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private QueueEntry queueEntry;

    @JoinColumn(name = "WORKER_GROUP_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private WorkerGroup workerGroup;

    @JoinColumn(name = "WORKER_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Worker worker;

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

    public RentalItemEventType getEventType() {
        return eventType;
    }

    public void setEventType(RentalItemEventType eventType) {
        this.eventType = eventType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public OffsetDateTime getEventDate() {
        return eventDate;
    }

    public void setEventDate(OffsetDateTime eventDate) {
        this.eventDate = eventDate;
    }

    public String getStatusBefore() {
        return statusBefore;
    }

    public void setStatusBefore(String statusBefore) {
        this.statusBefore = statusBefore;
    }

    public String getStatusAfter() {
        return statusAfter;
    }

    public void setStatusAfter(String statusAfter) {
        this.statusAfter = statusAfter;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public String getActorDisplayName() {
        return actorDisplayName;
    }

    public void setActorDisplayName(String actorDisplayName) {
        this.actorDisplayName = actorDisplayName;
    }

    public String getMobileTaskKey() {
        return mobileTaskKey;
    }

    public void setMobileTaskKey(String mobileTaskKey) {
        this.mobileTaskKey = mobileTaskKey;
    }

    public String getMobileCreationMode() {
        return mobileCreationMode;
    }

    public void setMobileCreationMode(String mobileCreationMode) {
        this.mobileCreationMode = mobileCreationMode;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceComment() {
        return sourceComment;
    }

    public void setSourceComment(String sourceComment) {
        this.sourceComment = sourceComment;
    }

    public String getEventGroupKey() {
        return eventGroupKey;
    }

    public void setEventGroupKey(String eventGroupKey) {
        this.eventGroupKey = eventGroupKey;
    }

    public Integer getEventOrder() {
        return eventOrder;
    }

    public void setEventOrder(Integer eventOrder) {
        this.eventOrder = eventOrder;
    }

    public RentalItemEvent getParentEvent() {
        return parentEvent;
    }

    public void setParentEvent(RentalItemEvent parentEvent) {
        this.parentEvent = parentEvent;
    }

    public RepairEstimate getEstimate() {
        return estimate;
    }

    public void setEstimate(RepairEstimate estimate) {
        this.estimate = estimate;
    }

    public RepairProcess getRepairProcess() {
        return repairProcess;
    }

    public void setRepairProcess(RepairProcess repairProcess) {
        this.repairProcess = repairProcess;
    }

    public BoardTask getBoardTask() {
        return boardTask;
    }

    public void setBoardTask(BoardTask boardTask) {
        this.boardTask = boardTask;
    }

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

}
