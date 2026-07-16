package dev.buhanzaz.rwms.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "user_warehouse_access", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_warehouse_access", columnNames = {"user_id", "warehouse_id"}))
public class UserWarehouseAccess {

    @Getter
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Getter
    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Getter
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_warehouse_access_user"))
    private AuthSubject user;

    @Getter
    @Column(name = "warehouse_id", nullable = false, length = 128)
    private String warehouseId;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 16)
    private WarehouseAccessLevel accessLevel;

    @Getter
    @Column(name = "comment_text", length = 1000)
    private String comment;

    @Getter
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
        normalize();
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        normalize();
    }

    private void normalize() {
        warehouseId = warehouseId == null ? null : warehouseId.trim();
        comment = comment == null || comment.isBlank() ? null : comment.trim();
    }

    public void define(
            AuthSubject user,
            String warehouseId,
            WarehouseAccessLevel accessLevel,
            String comment,
            boolean active) {
        if (this.user != null) {
            throw new IllegalStateException("Warehouse access is already initialized");
        }
        this.user = user;
        this.warehouseId = warehouseId;
        this.accessLevel = accessLevel;
        this.comment = comment;
        this.active = active;
    }

}
