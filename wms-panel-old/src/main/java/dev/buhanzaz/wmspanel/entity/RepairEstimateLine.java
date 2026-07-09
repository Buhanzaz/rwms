package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@JmixEntity
@Entity
@Table(name = "REPAIR_ESTIMATE_LINE", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "IDX_REPAIR_ESTIMATE_LINE_UNQ_SOURCE_KEY", columnNames = {
                "ESTIMATE_ID", "SOURCE_LINE_KEY"
        })
})
public class RepairEstimateLine extends UuidEntity {

    @NotNull
    @JoinColumn(name = "ESTIMATE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RepairEstimate estimate;

    @Enumerated(EnumType.STRING)
    @Column(name = "LINE_TYPE", nullable = false, length = 32)
    private RepairEstimateLineType lineType = RepairEstimateLineType.WORK;

    @Column(name = "DESCRIPTION", nullable = false, length = 2000)
    private String description;

    @Column(name = "LINE_COMMENT", length = 2000)
    private String lineComment;

    @Column(name = "UNIT_", nullable = false, length = 32)
    private String unit = "ед";

    @Column(name = "QUANTITY_", nullable = false)
    private Integer quantity = 1;

    @Column(name = "UNIT_PRICE", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "LINE_TOTAL", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Column(name = "ROW_ORDER", nullable = false)
    private Integer rowOrder = 0;

    @Column(name = "CATALOG_CODE", length = 128)
    private String catalogCode;

    @Column(name = "SOURCE_LINE_KEY", length = 128)
    private String sourceLineKey;

    @OneToMany(mappedBy = "estimateLine")
    private List<RepairEstimateTaskPlan> taskPlans = new ArrayList<>();

    public RepairEstimate getEstimate() {
        return estimate;
    }

    public void setEstimate(RepairEstimate estimate) {
        this.estimate = estimate;
    }

    public RepairEstimateLineType getLineType() {
        return lineType;
    }

    public void setLineType(RepairEstimateLineType lineType) {
        this.lineType = lineType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLineComment() {
        return lineComment;
    }

    public void setLineComment(String lineComment) {
        this.lineComment = lineComment;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(BigDecimal lineTotal) {
        this.lineTotal = lineTotal;
    }

    public Integer getRowOrder() {
        return rowOrder;
    }

    public void setRowOrder(Integer rowOrder) {
        this.rowOrder = rowOrder;
    }

    public String getCatalogCode() {
        return catalogCode;
    }

    public void setCatalogCode(String catalogCode) {
        this.catalogCode = catalogCode;
    }

    public String getSourceLineKey() {
        return sourceLineKey;
    }

    public void setSourceLineKey(String sourceLineKey) {
        this.sourceLineKey = sourceLineKey;
    }

    public List<RepairEstimateTaskPlan> getTaskPlans() {
        return taskPlans;
    }

    public void setTaskPlans(List<RepairEstimateTaskPlan> taskPlans) {
        this.taskPlans = taskPlans;
    }

    @PrePersist
    @PreUpdate
    private void normalizeBeforeSave() {
        description = trimToEmpty(description);
        lineComment = trimToNull(lineComment);
        unit = trimToEmpty(unit);
        if (unit.isBlank()) {
            unit = "ед";
        }
        catalogCode = normalizeCode(catalogCode);
        sourceLineKey = normalizeSourceLineKey(sourceLineKey);
        if (sourceLineKey == null) {
            sourceLineKey = UUID.randomUUID().toString();
        }
        if (lineTotal == null) {
            lineTotal = BigDecimal.ZERO;
        }
        if (rowOrder == null) {
            rowOrder = 0;
        }
        if (quantity == null || quantity < 1) {
            quantity = 1;
        }
        if (unitPrice == null) {
            unitPrice = BigDecimal.ZERO;
        }
        if (lineType == null) {
            lineType = RepairEstimateLineType.WORK;
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeCode(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String normalizeSourceLineKey(String value) {
        return trimToNull(value);
    }
}
