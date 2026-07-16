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
@Table(name = "RENTAL_ITEM_ACCESSORY", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RENTAL_ITEM_ACCESSORY_UNQ_ITEM_ACCESSORY", columnNames = {"RENTAL_ITEM_ID", "ACCESSORY_ITEM_ID"})
})
public class RentalItemAccessory extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "RENTAL_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItem rentalItem;

    @NotNull
    @JoinColumn(name = "ACCESSORY_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AccessoryItem accessoryItem;

    @NotNull
    @Min(0)
    @Column(name = "QUANTITY", nullable = false)
    private Integer quantity = 0;

    public RentalItem getRentalItem() {
        return rentalItem;
    }

    public void setRentalItem(RentalItem rentalItem) {
        this.rentalItem = rentalItem;
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
