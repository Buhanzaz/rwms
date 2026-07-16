package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import java.util.Locale;

@JmixEntity
@Table(name = "WORKER_CLASS", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_WORKER_CLASS_UNQ_CODE", columnNames = "CODE")
})
@Entity
public class WorkerClass extends FullAuditEntity {

    @NotNull
    @Column(name = "CODE", nullable = false, length = 64)
    private String code;

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false, length = 128)
    private String name;

    @Column(name = "DESCRIPTION", length = 1000)
    private String description;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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
