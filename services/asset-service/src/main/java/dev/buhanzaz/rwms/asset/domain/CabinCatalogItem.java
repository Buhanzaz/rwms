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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/** A UUID-backed selectable value in the cabin composition catalog. */
@Entity
@Table(name = "cabin_catalog_item")
public class CabinCatalogItem {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private CabinCatalogKind kind;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "name_normalized", nullable = false, length = 255)
  private String nameNormalized;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected CabinCatalogItem() {}

  public static CabinCatalogItem create(CabinCatalogKind kind, String name) {
    CabinCatalogItem item = new CabinCatalogItem();
    item.assign(kind, name, true);
    return item;
  }

  public boolean change(String name, boolean active) {
    String nextName = requiredName(name);
    String nextNormalized = normalizedName(nextName);
    if (Objects.equals(this.name, nextName)
        && Objects.equals(this.nameNormalized, nextNormalized)
        && this.active == active) {
      return false;
    }
    this.name = nextName;
    this.nameNormalized = nextNormalized;
    this.active = active;
    return true;
  }

  /** Bumps the optimistic revision when the type-to-dimension composition changes. */
  public void touchConfiguration() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  private void assign(CabinCatalogKind kind, String name, boolean active) {
    if (kind == null) throw new IllegalArgumentException("Cabin catalog kind is required");
    String normalizedName = requiredName(name);
    this.kind = kind;
    this.name = normalizedName;
    this.nameNormalized = normalizedName(normalizedName);
    this.active = active;
  }

  @PrePersist
  void prePersist() {
    createdAt = updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String requiredName(String value) {
    if (value == null) throw new IllegalArgumentException("Cabin catalog name is required");
    String normalized = value.trim().replaceAll("[\\p{Z}\\s]+", " ");
    if (normalized.isEmpty() || normalized.length() > 255) {
      throw new IllegalArgumentException("Cabin catalog name must contain 1 to 255 characters");
    }
    return normalized;
  }

  private static String normalizedName(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public CabinCatalogKind getKind() {
    return kind;
  }

  public String getName() {
    return name;
  }

  public String getNameNormalized() {
    return nameNormalized;
  }

  public boolean isActive() {
    return active;
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
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((CabinCatalogItem) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
