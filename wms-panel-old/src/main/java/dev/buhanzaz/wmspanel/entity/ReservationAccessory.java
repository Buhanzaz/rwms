package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Objects;

@JmixEntity
@Table(name = "RESERVATION_ACCESSORY")
@Entity
public class ReservationAccessory extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "RESERVATION_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Reservation reservation;

    @NotNull
    @JoinColumn(name = "RESERVATION_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private ReservationLine reservationItem;

    @NotNull
    @JoinColumn(name = "ACCESSORY_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AccessoryItem accessoryItem;

    @NotNull
    @Positive
    @Column(name = "QUANTITY", nullable = false)
    private Integer quantity = 1;

    @Column(name = "STATUS", nullable = false, length = 32)
    private String status = ReservationAccessoryStatus.ACTIVE.getId();

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @InstanceName
    public String getDisplayName() {
        String reservationNumber = reservation == null ? null : reservation.getReservationNumber();
        String itemNumber = reservationItem == null || reservationItem.getRentalItem() == null
                ? null
                : reservationItem.getRentalItem().getNumber();
        String accessoryName = accessoryItem == null ? null : accessoryItem.getName();
        if (reservationNumber == null && itemNumber == null && accessoryName == null) {
            return "Reservation accessory";
        }
        return String.join(" / ",
                java.util.stream.Stream.of(reservationNumber, itemNumber, accessoryName)
                        .filter(Objects::nonNull)
                        .toList());
    }

    public Reservation getReservation() {
        return reservation;
    }

    public void setReservation(Reservation reservation) {
        this.reservation = reservation;
    }

    public ReservationLine getReservationItem() {
        return reservationItem;
    }

    public void setReservationItem(ReservationLine reservationItem) {
        this.reservationItem = reservationItem;
    }

    public AccessoryItem getAccessoryItem() {
        return accessoryItem;
    }

    public void setAccessoryItem(AccessoryItem accessoryItem) {
        this.accessoryItem = accessoryItem;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public ReservationAccessoryStatus getStatus() {
        return ReservationAccessoryStatus.fromId(status);
    }

    public void setStatus(ReservationAccessoryStatus status) {
        this.status = status == null ? null : status.getId();
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
