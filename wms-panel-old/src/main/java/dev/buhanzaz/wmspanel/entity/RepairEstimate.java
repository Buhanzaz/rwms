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

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@JmixEntity
@Entity
@Table(name = "REPAIR_ESTIMATE")
public class RepairEstimate extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "RENTAL_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItem rentalItem;

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @InstanceName
    @Column(name = "CABIN_NUMBER", nullable = false, length = 128)
    private String cabinNumber;

    @Column(name = "SOURCE_PARTY", length = 255)
    private String sourceParty;

    @Column(name = "DESTINATION_PARTY", length = 255)
    private String destinationParty;

    @Column(name = "COMMENT_", length = 2000)
    private String comment;

    @Column(name = "DISPATCH_DATE")
    private OffsetDateTime dispatchDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 32)
    private RepairEstimateStatus status = RepairEstimateStatus.DRAFT;

    @Column(name = "TOTAL_AMOUNT", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @JoinColumn(name = "LATEST_EVENT_ID")
    @OneToOne(fetch = FetchType.LAZY)
    private RentalItemEvent latestEvent;

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

    public String getCabinNumber() {
        return cabinNumber;
    }

    public void setCabinNumber(String cabinNumber) {
        this.cabinNumber = cabinNumber;
    }

    public String getSourceParty() {
        return sourceParty;
    }

    public void setSourceParty(String sourceParty) {
        this.sourceParty = sourceParty;
    }

    public String getDestinationParty() {
        return destinationParty;
    }

    public void setDestinationParty(String destinationParty) {
        this.destinationParty = destinationParty;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public OffsetDateTime getDispatchDate() {
        return dispatchDate;
    }

    public void setDispatchDate(OffsetDateTime dispatchDate) {
        this.dispatchDate = dispatchDate;
    }

    public RepairEstimateStatus getStatus() {
        return status;
    }

    public void setStatus(RepairEstimateStatus status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public RentalItemEvent getLatestEvent() {
        return latestEvent;
    }

    public void setLatestEvent(RentalItemEvent latestEvent) {
        this.latestEvent = latestEvent;
    }
}
