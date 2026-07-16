package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@JmixEntity
@Table(name = "ACCESSORY_STOCK_BALANCE", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_ACCESSORY_STOCK_BALANCE_UNQ_WAREHOUSE_ITEM", columnNames = {"WAREHOUSE_ID", "ACCESSORY_ITEM_ID"})
})
@Entity
public class AccessoryStockBalance extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @NotNull
    @JoinColumn(name = "ACCESSORY_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AccessoryItem accessoryItem;

    @NotNull
    @PositiveOrZero
    @Column(name = "QUANTITY_TOTAL", nullable = false)
    private Integer quantityTotal = 0;

    @NotNull
    @PositiveOrZero
    @Column(name = "QUANTITY_AVAILABLE", nullable = false)
    private Integer quantityAvailable = 0;

    @NotNull
    @PositiveOrZero
    @Column(name = "QUANTITY_RESERVED", nullable = false)
    private Integer quantityReserved = 0;

    @NotNull
    @PositiveOrZero
    @Column(name = "QUANTITY_IN_RENT", nullable = false)
    private Integer quantityInRent = 0;

    @NotNull
    @PositiveOrZero
    @Column(name = "QUANTITY_BROKEN", nullable = false)
    private Integer quantityBroken = 0;

    @NotNull
    @PositiveOrZero
    @Column(name = "QUANTITY_WRITTEN_OFF", nullable = false)
    private Integer quantityWrittenOff = 0;

    // The total balance formula is intentionally deferred to a later step.
    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @InstanceName
    public String getDisplayName() {
        String warehouseName = warehouse == null ? null : warehouse.getName();
        String accessoryName = accessoryItem == null ? null : accessoryItem.getName();
        if (warehouseName == null && accessoryName == null) {
            return "Accessory stock balance";
        }
        if (warehouseName == null) {
            return accessoryName;
        }
        if (accessoryName == null) {
            return warehouseName;
        }
        return warehouseName + " / " + accessoryName;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public AccessoryItem getAccessoryItem() {
        return accessoryItem;
    }

    public void setAccessoryItem(AccessoryItem accessoryItem) {
        this.accessoryItem = accessoryItem;
    }

    public Integer getQuantityTotal() {
        return quantityTotal;
    }

    public void setQuantityTotal(Integer quantityTotal) {
        this.quantityTotal = quantityTotal;
    }

    public Integer getQuantityAvailable() {
        return quantityAvailable;
    }

    public void setQuantityAvailable(Integer quantityAvailable) {
        this.quantityAvailable = quantityAvailable;
    }

    public Integer getQuantityReserved() {
        return quantityReserved;
    }

    public void setQuantityReserved(Integer quantityReserved) {
        this.quantityReserved = quantityReserved;
    }

    public Integer getQuantityInRent() {
        return quantityInRent;
    }

    public void setQuantityInRent(Integer quantityInRent) {
        this.quantityInRent = quantityInRent;
    }

    public Integer getQuantityBroken() {
        return quantityBroken;
    }

    public void setQuantityBroken(Integer quantityBroken) {
        this.quantityBroken = quantityBroken;
    }

    public Integer getQuantityWrittenOff() {
        return quantityWrittenOff;
    }

    public void setQuantityWrittenOff(Integer quantityWrittenOff) {
        this.quantityWrittenOff = quantityWrittenOff;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
