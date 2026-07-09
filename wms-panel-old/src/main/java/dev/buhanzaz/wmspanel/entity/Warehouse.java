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
@Table(name = "WAREHOUSE", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_WAREHOUSE_UNQ_NAME", columnNames = "NAME"),
        @UniqueConstraint(name = "IDX_WAREHOUSE_UNQ_CODE", columnNames = "CODE")
})
@Entity
public class Warehouse extends FullAuditEntity {

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false, length = 128)
    private String name;

    @NotNull
    @Column(name = "CODE", nullable = false, length = 64)
    private String code;

    @Column(name = "CITY", length = 128)
    private String city;

    @Column(name = "ADDRESS", length = 255)
    private String address;

    @NotNull
    @Column(name = "TIME_ZONE", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

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

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
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
