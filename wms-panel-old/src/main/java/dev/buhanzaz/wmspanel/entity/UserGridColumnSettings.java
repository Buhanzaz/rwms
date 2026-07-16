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
@Table(name = "USER_GRID_COLUMN_SETTINGS", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_USER_GRID_COLUMN_SETTINGS_UNQ", columnNames = {"USER_ID", "GRID_CODE", "COLUMN_KEY"})
})
@Entity
public class UserGridColumnSettings extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "USER_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User user;

    @NotNull
    @Column(name = "GRID_CODE", nullable = false, length = 64)
    private String gridCode;

    @NotNull
    @Column(name = "COLUMN_KEY", nullable = false, length = 128)
    private String columnKey;

    @Column(name = "VISIBLE")
    private Boolean visible = true;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    @Column(name = "WIDTH", length = 32)
    private String width;

    @InstanceName
    public String getDisplayName() {
        return (gridCode == null ? "Grid" : gridCode) + " / " + (columnKey == null ? "Column" : columnKey);
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getGridCode() {
        return gridCode;
    }

    public void setGridCode(String gridCode) {
        this.gridCode = gridCode;
    }

    public String getColumnKey() {
        return columnKey;
    }

    public void setColumnKey(String columnKey) {
        this.columnKey = columnKey;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getWidth() {
        return width;
    }

    public void setWidth(String width) {
        this.width = width;
    }
}
