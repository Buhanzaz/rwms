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
@Table(name = "RENTAL_CLASSIFIER_ATTRIBUTE")
@Entity
public class RentalClassifierAttribute extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "ATTRIBUTE_DEFINITION_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalAttributeDefinition attributeDefinition;

    @JoinColumn(name = "CATEGORY_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalCategory category;

    @JoinColumn(name = "SUBCATEGORY_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalSubcategory subcategory;

    @JoinColumn(name = "TYPE_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalType type;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    @InstanceName
    public String getDisplayName() {
        StringBuilder builder = new StringBuilder();
        if (attributeDefinition != null && attributeDefinition.getName() != null) {
            builder.append(attributeDefinition.getName());
        }
        String target = targetName();
        if (target != null && !target.isBlank()) {
            if (builder.length() > 0) {
                builder.append(" - ");
            }
            builder.append(target);
        }
        return builder.length() == 0 ? "Rental classifier attribute" : builder.toString();
    }

    public RentalAttributeDefinition getAttributeDefinition() {
        return attributeDefinition;
    }

    public void setAttributeDefinition(RentalAttributeDefinition attributeDefinition) {
        this.attributeDefinition = attributeDefinition;
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

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    private String targetName() {
        if (type != null) {
            return type.getName();
        }
        if (subcategory != null) {
            return subcategory.getName();
        }
        if (category != null) {
            return category.getName();
        }
        return null;
    }
}
