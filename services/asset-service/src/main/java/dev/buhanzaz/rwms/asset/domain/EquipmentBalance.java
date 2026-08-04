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
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "equipment_balance")
public class EquipmentBalance {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "rental_item_id")
  private UUID rentalItemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "location_kind", nullable = false, length = 32)
  private BalanceLocationKind locationKind;

  @Column(name = "quantity", nullable = false)
  private long quantity;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected EquipmentBalance() {}

  /** Creates one canonical physical bucket. Callers may only replace its absolute quantity. */
  public static EquipmentBalance create(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {
    if (equipmentId == null || warehouseId == null || locationKind == null) {
      throw new IllegalArgumentException("Equipment balance identity is required");
    }
    if (quantity < 0) {
      throw new IllegalArgumentException("Equipment balance quantity cannot be negative");
    }
    boolean cabin = locationKind == BalanceLocationKind.CABIN_NON_RENTED
        || locationKind == BalanceLocationKind.CABIN_RENTED;
    if (cabin != (rentalItemId != null)) {
      throw new IllegalArgumentException("Equipment balance location identity is invalid");
    }
    EquipmentBalance balance = new EquipmentBalance();
    balance.equipmentId = equipmentId;
    balance.warehouseId = warehouseId;
    balance.rentalItemId = rentalItemId;
    balance.locationKind = locationKind;
    balance.quantity = quantity;
    return balance;
  }

  /** Applies a complete counted quantity after the caller has fenced this balance stream. */
  public boolean replaceQuantity(long nextQuantity) {
    if (nextQuantity < 0) {
      throw new IllegalArgumentException("Equipment balance quantity cannot be negative");
    }
    if (quantity == nextQuantity) {
      return false;
    }
    quantity = nextQuantity;
    updatedAt = now();
    return true;
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime timestamp = now();
    if (createdAt == null) {
      createdAt = timestamp;
    }
    if (updatedAt == null) {
      updatedAt = timestamp;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = now();
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public UUID getEquipmentId() {
    return equipmentId;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getRentalItemId() {
    return rentalItemId;
  }

  public BalanceLocationKind getLocationKind() {
    return locationKind;
  }

  public long getQuantity() {
    return quantity;
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
        && id != null
        && Objects.equals(id, ((EquipmentBalance) other).id);
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
