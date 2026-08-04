package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/** Durable inventory source identity for one all-or-nothing furniture reconciliation. */
@Entity
@Table(name = "inventory_furniture_reconciliation")
public class InventoryFurnitureReconciliation {
  @Id
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryFurnitureReconciliation() {}

  public static InventoryFurnitureReconciliation register(
      UUID inventoryId, String requestSha256, UUID idempotencyKey) {
    if (inventoryId == null || idempotencyKey == null || requestSha256 == null
        || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inventory furniture reconciliation identity is invalid");
    }
    InventoryFurnitureReconciliation value = new InventoryFurnitureReconciliation();
    value.inventoryId = inventoryId;
    value.requestSha256 = requestSha256;
    value.idempotencyKey = idempotencyKey;
    value.createdAt = now();
    value.updatedAt = value.createdAt;
    return value;
  }

  public boolean complete() {
    if (completedAt != null) {
      return false;
    }
    completedAt = now();
    updatedAt = completedAt;
    return true;
  }

  public boolean isCompleted() {
    return completedAt != null;
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime timestamp = now();
    if (createdAt == null) createdAt = timestamp;
    if (updatedAt == null) updatedAt = timestamp;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = now();
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public long getVersion() {
    return version;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public OffsetDateTime getCompletedAt() {
    return completedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : getClass();
    return thisClass == otherClass
        && inventoryId != null
        && Objects.equals(inventoryId, ((InventoryFurnitureReconciliation) other).inventoryId);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
