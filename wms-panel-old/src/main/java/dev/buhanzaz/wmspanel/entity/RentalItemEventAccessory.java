package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Entity
@Table(name = "RENTAL_ITEM_EVENT_ACCESSORY", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RENTAL_ITEM_EVENT_ACC_UNQ_EVENT_ACCESSORY", columnNames = {"EVENT_ID", "ACCESSORY_ITEM_ID"})
})
public class RentalItemEventAccessory extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "EVENT_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItemEvent event;

    @NotNull
    @JoinColumn(name = "ACCESSORY_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AccessoryItem accessoryItem;

    @NotNull
    @Min(0)
    @Column(name = "QUANTITY", nullable = false)
    private Integer quantity = 0;

    public RentalItemEvent getEvent() {
        return event;
    }

    public void setEvent(RentalItemEvent event) {
        this.event = event;
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
}
