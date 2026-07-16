package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Table(name = "RENTAL_ATTRIBUTE_DEFINITION", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RENTAL_ATTRIBUTE_DEFINITION_UNQ_CODE", columnNames = "CODE")
})
@Entity
public class RentalAttributeDefinition extends FullAuditEntity {

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false)
    private String name;

    @NotNull
    @Column(name = "CODE", nullable = false, length = 64)
    private String code;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "DATA_TYPE", nullable = false, length = 32)
    private RentalAttributeDataType dataType;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

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

    public RentalAttributeDataType getDataType() {
        return dataType;
    }

    public void setDataType(RentalAttributeDataType dataType) {
        this.dataType = dataType;
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
