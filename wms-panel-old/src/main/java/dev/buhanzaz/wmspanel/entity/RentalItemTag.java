package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Table(name = "RENTAL_ITEM_TAG", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RENTAL_ITEM_TAG_UNQ_ITEM_TAG", columnNames = {"RENTAL_ITEM_ID", "TAG_ID"})
})
@Entity
public class RentalItemTag extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "RENTAL_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItem rentalItem;

    @NotNull
    @JoinColumn(name = "TAG_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalTag tag;

    @InstanceName
    public String getDisplayName() {
        return tag == null || tag.getName() == null ? "Rental item tag" : tag.getName();
    }

    public RentalItem getRentalItem() {
        return rentalItem;
    }

    public void setRentalItem(RentalItem rentalItem) {
        this.rentalItem = rentalItem;
    }

    public RentalTag getTag() {
        return tag;
    }

    public void setTag(RentalTag tag) {
        this.tag = tag;
    }
}
