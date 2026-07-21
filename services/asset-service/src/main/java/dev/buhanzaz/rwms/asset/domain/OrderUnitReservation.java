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

@Entity
@Table(name = "order_unit_reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderUnitReservation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private OrderUnitReservationState state;

  @Column(name = "added_by_subject_id", nullable = false)
  private UUID addedBySubjectId;

  @Column(name = "added_by_role", nullable = false, length = 32)
  private String addedByRole;

  @Column(name = "released_by_subject_id")
  private UUID releasedBySubjectId;

  @Column(name = "released_by_role", length = 32)
  private String releasedByRole;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static OrderUnitReservation create(
      UUID orderId,
      UUID rentalItemId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole) {
    OrderUnitReservation reservation = new OrderUnitReservation();
    reservation.orderId = Objects.requireNonNull(orderId, "orderId");
    reservation.rentalItemId = Objects.requireNonNull(rentalItemId, "rentalItemId");
    reservation.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    reservation.state = OrderUnitReservationState.ACTIVE;
    reservation.addedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    reservation.addedByRole = requireRole(actorRole);
    reservation.createdAt = now();
    reservation.updatedAt = reservation.createdAt;
    return reservation;
  }

  public boolean release(UUID actorSubjectId, String actorRole) {
    if (state == OrderUnitReservationState.RELEASED) return false;
    state = OrderUnitReservationState.RELEASED;
    releasedBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    releasedByRole = requireRole(actorRole);
    releasedAt = now();
    updatedAt = releasedAt;
    return true;
  }

  public boolean isActive() {
    return state == OrderUnitReservationState.ACTIVE;
  }

  private static String requireRole(String value) {
    if (value == null
        || !java.util.Set.of(
                "SYSTEM_ADMIN",
                "WMS_ADMIN",
                "WAREHOUSE_MANAGER",
                "RENTAL_MANAGER",
                "VIEWER")
            .contains(value)) {
      throw new IllegalArgumentException("actorRole is invalid");
    }
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
        && Objects.equals(id, ((OrderUnitReservation) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
