package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Table(name = "RENTAL_CLASSIFIER_ATTRIBUTE_CATEGORY_LINK", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RENTAL_CLASSIFIER_ATTR_CATEGORY_UNQ", columnNames = {"RENTAL_CLASSIFIER_ATTRIBUTE_ID", "CATEGORY_ID"})
})
@Entity
public class RentalClassifierAttributeCategoryLink extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "RENTAL_CLASSIFIER_ATTRIBUTE_ID", nullable = false,
            foreignKey = @ForeignKey(name = "FK_RENTAL_CLASSIFIER_ATTR_CATEGORY_ON_BINDING"))
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalClassifierAttribute rentalClassifierAttribute;

    @NotNull
    @JoinColumn(name = "CATEGORY_ID", nullable = false,
            foreignKey = @ForeignKey(name = "FK_RENTAL_CLASSIFIER_ATTR_CATEGORY_ON_CATEGORY"))
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalCategory category;

    @InstanceName
    public String getDisplayName() {
        String attributeName = rentalClassifierAttribute == null ? null : rentalClassifierAttribute.getDisplayName();
        String categoryName = category == null ? null : category.getName();
        if (attributeName == null && categoryName == null) {
            return null;
        }
        return String.format("%s / %s",
                attributeName == null ? "" : attributeName,
                categoryName == null ? "" : categoryName).trim();
    }

    public RentalClassifierAttribute getRentalClassifierAttribute() {
        return rentalClassifierAttribute;
    }

    public void setRentalClassifierAttribute(RentalClassifierAttribute rentalClassifierAttribute) {
        this.rentalClassifierAttribute = rentalClassifierAttribute;
    }

    public RentalCategory getCategory() {
        return category;
    }

    public void setCategory(RentalCategory category) {
        this.category = category;
    }
}
