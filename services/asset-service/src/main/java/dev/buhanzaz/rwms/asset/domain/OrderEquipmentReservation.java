package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * A warehouse-partitioned generic allocation for an order. It deliberately has no source cabin:
 * the physical source is selected only when a warehouse worker task is created, while warehouseId
 * keeps mixed-source orders from consuming another warehouse's capacity.
 */
@Entity
@Table(name = "order_equipment_reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderEquipmentReservation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "quantity", nullable = false)
  private long quantity;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private OrderEquipmentReservationState state;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static OrderEquipmentReservation create(
      UUID orderId, UUID equipmentId, UUID warehouseId, long quantity) {
    OrderEquipmentReservation reservation = new OrderEquipmentReservation();
    reservation.orderId = Objects.requireNonNull(orderId, "orderId");
    reservation.equipmentId = Objects.requireNonNull(equipmentId, "equipmentId");
    reservation.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    reservation.quantity = requireQuantity(quantity);
    reservation.state = OrderEquipmentReservationState.ACTIVE;
    reservation.createdAt = now();
    reservation.updatedAt = reservation.createdAt;
    return reservation;
  }

  public boolean changeQuantity(long nextQuantity) {
    requireActive();
    long requiredQuantity = requireQuantity(nextQuantity);
    if (quantity == requiredQuantity) return false;
    quantity = requiredQuantity;
    updatedAt = now();
    return true;
  }

  public boolean release() {
    if (state == OrderEquipmentReservationState.RELEASED) return false;
    state = OrderEquipmentReservationState.RELEASED;
    releasedAt = now();
    updatedAt = releasedAt;
    return true;
  }

  private void requireActive() {
    if (state != OrderEquipmentReservationState.ACTIVE) {
      throw new IllegalStateException("Order equipment reservation is not active");
    }
  }

  private static long requireQuantity(long value) {
    if (value < 1) throw new IllegalArgumentException("quantity must be positive");
    return value;
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
        && Objects.equals(id, ((OrderEquipmentReservation) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
