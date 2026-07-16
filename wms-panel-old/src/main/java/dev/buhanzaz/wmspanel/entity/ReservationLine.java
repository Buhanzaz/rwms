package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.ArrayList;
import java.util.List;

@JmixEntity
@Table(name = "RESERVATION_LINE", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RESERVATION_LINE_UNQ_RESERVATION_RENTAL_ITEM", columnNames = {"RESERVATION_ID", "WAREHOUSE_ITEM_ID"})
})
@Entity
public class ReservationLine extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "RESERVATION_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Reservation reservation;

    @JoinColumn(name = "WAREHOUSE_ITEM_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalItem rentalItem;

    @JoinColumn(name = "STOCK_ITEM_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private StockItem stockItem;

    @NotNull
    @Positive
    @Column(name = "QUANTITY", nullable = false)
    private Integer quantity = 1;

    @Column(name = "STATUS", nullable = false, length = 32)
    private String status = ReservationItemStatus.ACTIVE.getId();

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @OneToMany(mappedBy = "reservationItem")
    private List<ReservationAccessory> accessories = new ArrayList<>();

    public Reservation getReservation() {
        return reservation;
    }

    public void setReservation(Reservation reservation) {
        this.reservation = reservation;
    }

    public RentalItem getRentalItem() {
        return rentalItem;
    }

    public void setRentalItem(RentalItem rentalItem) {
        this.rentalItem = rentalItem;
    }

    public RentalItem getWarehouseItem() {
        return getRentalItem();
    }

    public void setWarehouseItem(RentalItem warehouseItem) {
        setRentalItem(warehouseItem);
    }

    public StockItem getStockItem() {
        return stockItem;
    }

    public void setStockItem(StockItem stockItem) {
        this.stockItem = stockItem;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public ReservationItemStatus getStatus() {
        return ReservationItemStatus.fromId(status);
    }

    public void setStatus(ReservationItemStatus status) {
        this.status = status == null ? null : status.getId();
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<ReservationAccessory> getAccessories() {
        return accessories;
    }

    public void setAccessories(List<ReservationAccessory> accessories) {
        this.accessories = accessories;
    }
}
