package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import java.util.Locale;

@JmixEntity
@Table(name = "ACCESSORY_SUBCATEGORY", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_ACCESSORY_SUBCATEGORY_UNQ_CODE", columnNames = {"CATEGORY_ID", "CODE"})
})
@Entity
public class AccessorySubcategory extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "CATEGORY_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AccessoryCategory category;

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false)
    private String name;

    @NotNull
    @Column(name = "CODE", nullable = false, length = 64)
    private String code;

    @NotNull
    @Column(name = "ACTIVE", nullable = false)
    private Boolean active = true;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    public AccessoryCategory getCategory() {
        return category;
    }

    public void setCategory(AccessoryCategory category) {
        this.category = category;
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
        this.code = normalizeCode(code);
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

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    @PrePersist
    @PreUpdate
    private void normalizeBeforeSave() {
        this.code = normalizeCode(this.code);
    }

    private String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
