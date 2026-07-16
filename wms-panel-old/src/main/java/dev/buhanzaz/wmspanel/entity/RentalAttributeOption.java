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

@JmixEntity
@Table(name = "RENTAL_ATTRIBUTE_OPTION", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RENTAL_ATTRIBUTE_OPTION_UNQ_CODE", columnNames = {"ATTRIBUTE_DEFINITION_ID", "CODE"})
})
@Entity
public class RentalAttributeOption extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "ATTRIBUTE_DEFINITION_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalAttributeDefinition attributeDefinition;

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false)
    private String name;

    @NotNull
    @Column(name = "CODE", nullable = false, length = 64)
    private String code;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    public RentalAttributeDefinition getAttributeDefinition() {
        return attributeDefinition;
    }

    public void setAttributeDefinition(RentalAttributeDefinition attributeDefinition) {
        this.attributeDefinition = attributeDefinition;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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
}
