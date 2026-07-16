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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Locale;

@JmixEntity
@Table(name = "RENTAL_ITEM", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RENTAL_ITEM_UNQ_NUMBER", columnNames = "NUMBER")
})
@Entity
public class RentalItem extends FullAuditEntity {

    @InstanceName
    @NotBlank
    @Column(name = "NUMBER", nullable = false, length = 128)
    private String number;

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;
    
    @NotNull
    @JoinColumn(name = "CATEGORY_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalCategory category;

    @JoinColumn(name = "SUBCATEGORY_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalSubcategory subcategory;

    @JoinColumn(name = "TYPE_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalType type;

    @JoinColumn(name = "CONDITION_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalItemCondition condition;

    @NotBlank
    @Column(name = "STATUS", nullable = false, length = 64)
    private String status;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = normalizeNumber(number);
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public RentalCategory getCategory() {
        return category;
    }

    public void setCategory(RentalCategory category) {
        this.category = category;
    }

    public RentalSubcategory getSubcategory() {
        return subcategory;
    }

    public void setSubcategory(RentalSubcategory subcategory) {
        this.subcategory = subcategory;
    }

    public RentalType getType() {
        return type;
    }

    public void setType(RentalType type) {
        this.type = type;
    }

    public RentalItemCondition getCondition() {
        return condition;
    }

    public void setCondition(RentalItemCondition condition) {
        this.condition = condition;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = normalizeStatus(status);
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    private String normalizeNumber(String number) {
        if (number == null) {
            return null;
        }
        String normalized = number.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        String normalized = status.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
