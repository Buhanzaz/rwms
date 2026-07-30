package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Ordered, UUID-only compatibility between one cabin type and one dimension. */
@Entity
@Table(name = "cabin_type_dimension")
public class CabinTypeDimension {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "cabin_type_id", nullable = false)
  private UUID cabinTypeId;

  @Column(name = "dimension_id", nullable = false)
  private UUID dimensionId;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected CabinTypeDimension() {}

  public static CabinTypeDimension create(UUID cabinTypeId, UUID dimensionId, int sortOrder) {
    if (cabinTypeId == null || dimensionId == null || sortOrder < 0) {
      throw new IllegalArgumentException("Cabin type-dimension link is incomplete");
    }
    CabinTypeDimension link = new CabinTypeDimension();
    link.cabinTypeId = cabinTypeId;
    link.dimensionId = dimensionId;
    link.sortOrder = sortOrder;
    return link;
  }

  @PrePersist
  void prePersist() {
    createdAt = updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public UUID getCabinTypeId() {
    return cabinTypeId;
  }

  public UUID getDimensionId() {
    return dimensionId;
  }

  public int getSortOrder() {
    return sortOrder;
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
        && Objects.equals(id, ((CabinTypeDimension) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
