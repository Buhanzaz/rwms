package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

@Table(name = "USER_WAREHOUSE_ACCESS", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_USER_WAREHOUSE_ACCESS_UNQ", columnNames = {"USER_ID", "WAREHOUSE_ID"})
})
@JmixEntity
@Entity
public class UserWarehouseAccess extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "USER_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "ACCESS_LEVEL", nullable = false, length = 32)
    private WarehouseAccessLevel accessLevel;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @NotNull
    @Column(name = "ACTIVE", nullable = false)
    private Boolean active = true;

    @InstanceName
    @DependsOnProperties({"user", "warehouse"})
    public String getInstanceName() {
        return (user != null ? user.getDisplayName() : "") + " / " + (warehouse != null ? warehouse.getName() : "");
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public WarehouseAccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(WarehouseAccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
