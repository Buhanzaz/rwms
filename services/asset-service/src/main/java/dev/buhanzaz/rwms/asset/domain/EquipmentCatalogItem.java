package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "equipment_catalog_item")
public class EquipmentCatalogItem {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 32)
  private EquipmentCategory category;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "comment", length = 2000)
  private String comment;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected EquipmentCatalogItem() {}

  public static EquipmentCatalogItem create(
      String name, EquipmentCategory category, String comment) {
    EquipmentCatalogItem item = new EquipmentCatalogItem();
    item.assign(name, category, true, comment);
    return item;
  }

  public boolean change(String name, EquipmentCategory category, boolean active, String comment) {
    String nextName = required(name, "name", 255);
    String nextComment = optional(comment, 2000);
    if (category == null) throw new IllegalArgumentException("category is required");
    if (Objects.equals(this.name, nextName)
        && this.category == category && this.active == active && Objects.equals(this.comment, nextComment)) return false;
    this.name = nextName;
    this.category = category;
    this.active = active;
    this.comment = nextComment;
    return true;
  }

  private void assign(String name, EquipmentCategory category, boolean active, String comment) {
    this.name = required(name, "name", 255);
    if (category == null) throw new IllegalArgumentException("category is required");
    this.category = category;
    this.active = active;
    this.comment = optional(comment, 2000);
  }

  @PrePersist
  void prePersist() { createdAt = updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }
  @PreUpdate
  void preUpdate() { updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }

  private static String required(String value, String field, int max) {
    String normalized = optional(value, max);
    if (normalized == null) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }
  private static String optional(String value, int max) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > max) throw new IllegalArgumentException("Value is too long");
    return normalized;
  }

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public String getName() { return name; }
  public EquipmentCategory getCategory() { return category; }
  public boolean isActive() { return active; }
  public String getComment() { return comment; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass() : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass() : getClass();
    return thisClass == otherClass && id != null && Objects.equals(id, ((EquipmentCatalogItem) other).id);
  }
  @Override
  public final int hashCode() { return this instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode(); }
}
