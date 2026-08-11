package dev.buhanzaz.rwms.logistics.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * A desired catalogue position for one order cabin. The physical furniture remains asset-owned;
 * this is the order's immutable-name snapshot and intent.
 */
@Entity
@Table(
    name = "rental_order_equipment_requirement",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_rental_order_equipment_requirement",
            columnNames = {"order_id", "rental_item_id", "equipment_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalOrderEquipmentRequirement {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "order_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_order_equipment_requirement_order"))
  private RentalOrder order;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "equipment_name", nullable = false, length = 512)
  private String equipmentName;

  /** Zero retains the order's audit-safe composition history after removal. */
  @Column(name = "quantity", nullable = false)
  private long quantity;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static RentalOrderEquipmentRequirement create(
      RentalOrder order, UUID rentalItemId, UUID equipmentId, String equipmentName, long quantity) {
    RentalOrderEquipmentRequirement requirement = new RentalOrderEquipmentRequirement();
    requirement.order = Objects.requireNonNull(order, "order");
    requirement.rentalItemId = Objects.requireNonNull(rentalItemId, "rentalItemId");
    requirement.equipmentId = Objects.requireNonNull(equipmentId, "equipmentId");
    requirement.equipmentName = requireText(equipmentName, 512, "equipmentName");
    requirement.quantity = requireQuantity(quantity);
    requirement.createdAt = now();
    requirement.updatedAt = requirement.createdAt;
    return requirement;
  }

  public boolean change(String nextEquipmentName, long nextQuantity) {
    String name = requireText(nextEquipmentName, 512, "equipmentName");
    long quantity = requireQuantity(nextQuantity);
    if (Objects.equals(equipmentName, name) && this.quantity == quantity) {
      return false;
    }
    equipmentName = name;
    this.quantity = quantity;
    updatedAt = now();
    return true;
  }

  /** Transfers the unchanged order furniture intent to a same-order replacement cabin. */
  public void transferToRentalItem(UUID replacementRentalItemId) {
    UUID replacement = Objects.requireNonNull(replacementRentalItemId, "replacementRentalItemId");
    if (!replacement.equals(rentalItemId)) {
      rentalItemId = replacement;
      updatedAt = now();
    }
  }

  private static long requireQuantity(long value) {
    if (value < 0) {
      throw new IllegalArgumentException("quantity must not be negative");
    }
    return value;
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
        && Objects.equals(id, ((RentalOrderEquipmentRequirement) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
