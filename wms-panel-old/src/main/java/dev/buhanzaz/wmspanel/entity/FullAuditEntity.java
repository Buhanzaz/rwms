package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

@JmixEntity(name = "wmspanel_FullAuditEntity")
@MappedSuperclass
public abstract class FullAuditEntity extends CreateUpdateAuditEntity {
    @Column(name = "VERSION", nullable = false)
    @Version
    private Integer version;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
