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

@JmixEntity
@Table(name = "STOCK_ITEM")
@Entity
public class StockItem extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false, length = 255)
    private String name;

    @JoinColumn(name = "SEGMENT_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private WarehouseSegment segment;

    @JoinColumn(name = "CATEGORY_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalCategory category;

    @NotNull
    @Column(name = "TOTAL_QUANTITY", nullable = false)
    private Integer totalQuantity = 0;

    @NotNull
    @Column(name = "UNIT_", nullable = false, length = 64)
    private String unit = "шт.";

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public WarehouseSegment getSegment() {
        return segment;
    }

    public void setSegment(WarehouseSegment segment) {
        this.segment = segment;
    }

    public RentalCategory getCategory() {
        return category;
    }

    public void setCategory(RentalCategory category) {
        this.category = category;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(Integer totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
