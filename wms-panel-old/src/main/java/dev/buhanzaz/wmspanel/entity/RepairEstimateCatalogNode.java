package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

@JmixEntity
@Table(name = "REPAIR_ESTIMATE_CATALOG_NODE", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_REPAIR_ESTIMATE_CATALOG_NODE_UNQ_CODE", columnNames = "CODE")
})
@Entity
public class RepairEstimateCatalogNode extends FullAuditEntity {

    @NotNull
    @Column(name = "CODE", nullable = false, length = 128)
    private String code;

    @NotNull
    @Column(name = "NAME", nullable = false, length = 255)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "NODE_TYPE", nullable = false, length = 32)
    private RepairEstimateCatalogNodeType nodeType = RepairEstimateCatalogNodeType.CATEGORY;

    @JoinColumn(name = "PARENT_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RepairEstimateCatalogNode parent;

    @NotNull
    @Column(name = "ACTIVE", nullable = false)
    private Boolean active = true;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    @Column(name = "UNIT_", length = 32)
    private String unit;

    @Column(name = "UNIT_PRICE", precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "DEFAULT_QUANTITY")
    private Integer defaultQuantity = 1;

    @Column(name = "DURATION_MINUTES")
    private Integer durationMinutes;

    @NotNull
    @Column(name = "ADDITIONAL_OPTION", nullable = false)
    private Boolean additionalOption = false;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @NotNull
    @Column(name = "SHOW_IN_MAIN_MENU", nullable = false)
    private Boolean showInMainMenu = false;

    @Column(name = "MAIN_MENU_ORDER")
    private Integer mainMenuOrder;

    @Column(name = "MAIN_MENU_TITLE", length = 255)
    private String mainMenuTitle;

    @JoinColumn(name = "WORK_QUEUE_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private WorkQueue workQueue;

    @Enumerated(EnumType.STRING)
    @Column(name = "ROUTE_QUEUE_KIND", length = 32)
    private WorkQueueKind routeQueueKind;

    @NotNull
    @Column(name = "PHOTO_REQUIRED", nullable = false)
    private Boolean photoRequired = false;

    @NotNull
    @Column(name = "INCLUDE_IN_ESTIMATE", nullable = false)
    private Boolean includeInEstimate = false;

    @NotNull
    @Column(name = "COMMON_ITEM", nullable = false)
    private Boolean commonItem = false;

    @NotNull
    @Column(name = "FURNITURE_CATEGORY", nullable = false)
    private Boolean furnitureCategory = false;

    @Column(name = "CANVAS_X")
    private Integer canvasX;

    @Column(name = "CANVAS_Y")
    private Integer canvasY;

    @InstanceName
    public String getDisplayName() {
        if (code == null || code.isBlank()) {
            return name;
        }
        return code + " - " + name;
    }

    @AssertTrue(message = "{validation.repairEstimateCatalogNodeHierarchy}")
    public boolean isHierarchyValid() {
        if (nodeType == null) {
            return true;
        }
        if (nodeType == RepairEstimateCatalogNodeType.CATEGORY) {
            return parent == null;
        }
        return true;
    }

    @AssertTrue(message = "{validation.repairEstimateCatalogNodeParentSelf}")
    public boolean isParentNotSelf() {
        if (parent == null || getId() == null || parent.getId() == null) {
            return true;
        }
        return !Objects.equals(getId(), parent.getId());
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = normalizeCode(code);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public RepairEstimateCatalogNodeType getNodeType() {
        return nodeType;
    }

    public void setNodeType(RepairEstimateCatalogNodeType nodeType) {
        this.nodeType = nodeType;
    }

    public RepairEstimateCatalogNode getParent() {
        return parent;
    }

    public void setParent(RepairEstimateCatalogNode parent) {
        this.parent = parent;
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

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Integer getDefaultQuantity() {
        return defaultQuantity;
    }

    public void setDefaultQuantity(Integer defaultQuantity) {
        this.defaultQuantity = defaultQuantity;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Boolean getAdditionalOption() {
        return additionalOption;
    }

    public void setAdditionalOption(Boolean additionalOption) {
        this.additionalOption = additionalOption;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Boolean getShowInMainMenu() {
        return showInMainMenu;
    }

    public void setShowInMainMenu(Boolean showInMainMenu) {
        this.showInMainMenu = showInMainMenu;
    }

    public Integer getMainMenuOrder() {
        return mainMenuOrder;
    }

    public void setMainMenuOrder(Integer mainMenuOrder) {
        this.mainMenuOrder = mainMenuOrder;
    }

    public String getMainMenuTitle() {
        return mainMenuTitle;
    }

    public void setMainMenuTitle(String mainMenuTitle) {
        this.mainMenuTitle = mainMenuTitle;
    }

    public WorkQueue getWorkQueue() {
        return workQueue;
    }

    public void setWorkQueue(WorkQueue workQueue) {
        this.workQueue = workQueue;
    }

    public WorkQueueKind getRouteQueueKind() {
        return routeQueueKind;
    }

    public void setRouteQueueKind(WorkQueueKind routeQueueKind) {
        this.routeQueueKind = routeQueueKind;
    }

    public Boolean getPhotoRequired() {
        return photoRequired;
    }

    public void setPhotoRequired(Boolean photoRequired) {
        this.photoRequired = photoRequired;
    }

    public Boolean getIncludeInEstimate() {
        return includeInEstimate;
    }

    public void setIncludeInEstimate(Boolean includeInEstimate) {
        this.includeInEstimate = includeInEstimate;
    }

    public Boolean getCommonItem() {
        return commonItem;
    }

    public void setCommonItem(Boolean commonItem) {
        this.commonItem = commonItem;
    }

    public Boolean getFurnitureCategory() {
        return furnitureCategory;
    }

    public void setFurnitureCategory(Boolean furnitureCategory) {
        this.furnitureCategory = furnitureCategory;
    }

    public Integer getCanvasX() {
        return canvasX;
    }

    public void setCanvasX(Integer canvasX) {
        this.canvasX = canvasX;
    }

    public Integer getCanvasY() {
        return canvasY;
    }

    public void setCanvasY(Integer canvasY) {
        this.canvasY = canvasY;
    }

    @PrePersist
    @PreUpdate
    private void normalizeBeforeSave() {
        this.code = normalizeCode(this.code);
        if (photoRequired == null) {
            photoRequired = false;
        }
        if (includeInEstimate == null) {
            includeInEstimate = nodeType == RepairEstimateCatalogNodeType.WORK
                    || nodeType == RepairEstimateCatalogNodeType.MATERIAL;
        }
        if (commonItem == null) {
            commonItem = false;
        }
        if (furnitureCategory == null) {
            furnitureCategory = false;
        }
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
