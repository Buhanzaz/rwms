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
@Table(name = "RENTAL_ATTRIBUTE_VALUE", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RENTAL_ATTRIBUTE_VALUE_UNQ_ITEM_ATTR", columnNames = {"RENTAL_ITEM_ID", "ATTRIBUTE_DEFINITION_ID"})
})
@Entity
public class RentalAttributeValue extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "RENTAL_ITEM_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItem rentalItem;

    @NotNull
    @JoinColumn(name = "ATTRIBUTE_DEFINITION_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalAttributeDefinition attributeDefinition;

    @Column(name = "VALUE_STRING", length = 1000)
    private String valueString;

    @Column(name = "VALUE_TEXT", length = 4000)
    private String valueText;

    @Column(name = "VALUE_NUMBER")
    private Double valueNumber;

    @Column(name = "VALUE_BOOLEAN")
    private Boolean valueBoolean;

    @JoinColumn(name = "VALUE_OPTION_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalAttributeOption valueOption;

    @InstanceName
    public String getDisplayName() {
        String attributeName = attributeDefinition == null || attributeDefinition.getName() == null
                ? "Attribute"
                : attributeDefinition.getName();
        String value = displayValue();
        return attributeName + (value.isBlank() ? "" : " = " + value);
    }

    public RentalItem getRentalItem() {
        return rentalItem;
    }

    public void setRentalItem(RentalItem rentalItem) {
        this.rentalItem = rentalItem;
    }

    public RentalAttributeDefinition getAttributeDefinition() {
        return attributeDefinition;
    }

    public void setAttributeDefinition(RentalAttributeDefinition attributeDefinition) {
        this.attributeDefinition = attributeDefinition;
    }

    public String getValueString() {
        return valueString;
    }

    public void setValueString(String valueString) {
        this.valueString = valueString;
    }

    public String getValueText() {
        return valueText;
    }

    public void setValueText(String valueText) {
        this.valueText = valueText;
    }

    public Double getValueNumber() {
        return valueNumber;
    }

    public void setValueNumber(Double valueNumber) {
        this.valueNumber = valueNumber;
    }

    public Boolean getValueBoolean() {
        return valueBoolean;
    }

    public void setValueBoolean(Boolean valueBoolean) {
        this.valueBoolean = valueBoolean;
    }

    public RentalAttributeOption getValueOption() {
        return valueOption;
    }

    public void setValueOption(RentalAttributeOption valueOption) {
        this.valueOption = valueOption;
    }

    private String displayValue() {
        if (valueOption != null && valueOption.getName() != null) {
            return valueOption.getName();
        }
        if (valueString != null && !valueString.isBlank()) {
            return valueString;
        }
        if (valueText != null && !valueText.isBlank()) {
            return valueText;
        }
        if (valueNumber != null) {
            return valueNumber.toString();
        }
        if (valueBoolean != null) {
            return Boolean.TRUE.equals(valueBoolean) ? "true" : "false";
        }
        return "";
    }
}
