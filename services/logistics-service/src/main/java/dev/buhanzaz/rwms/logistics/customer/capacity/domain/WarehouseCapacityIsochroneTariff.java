package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

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
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * One logistics-owned hourly road-time tariff in a warehouse capacity snapshot.
 *
 * <p>The ordered aggregate tier with the greatest travel time is also the warehouse delivery
 * boundary. A customer point beyond it is not offered a delivery slot.
 */
@Entity
@Table(
    name = "customer_warehouse_capacity_isochrone_tariff",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_warehouse_capacity_isochrone_tariff_minutes",
            columnNames = {"snapshot_id", "travel_minutes"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseCapacityIsochroneTariff {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "snapshot_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_customer_warehouse_capacity_isochrone_tariff_snapshot"))
  private WarehouseCapacitySnapshot snapshot;

  @Column(name = "travel_minutes", nullable = false)
  private int travelMinutes;

  @Column(name = "price_rubles", nullable = false)
  private long priceRubles;

  /** Copies one validated tariff into the owning replacement aggregate. */
  static WarehouseCapacityIsochroneTariff create(
      WarehouseCapacitySnapshot snapshot, Facts facts) {
    WarehouseCapacityIsochroneTariff tariff = new WarehouseCapacityIsochroneTariff();
    tariff.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    tariff.replace(facts);
    return tariff;
  }

  /** Replaces the mutable price while retaining the stable tier persistence identity. */
  void replace(Facts facts) {
    Objects.requireNonNull(facts, "facts");
    travelMinutes = facts.travelMinutes();
    priceRubles = facts.priceRubles();
  }

  /** Boundary-independent immutable facts for one hourly road-time tariff. */
  public record Facts(int travelMinutes, long priceRubles) {
    public Facts {
      if (travelMinutes < 60 || travelMinutes > 720 || travelMinutes % 60 != 0) {
        throw new IllegalArgumentException(
            "Isochrone travel minutes must be a whole hour from 60 to 720");
      }
      if (priceRubles < 0) {
        throw new IllegalArgumentException("Isochrone price must be non-negative");
      }
    }
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
        && Objects.equals(id, ((WarehouseCapacityIsochroneTariff) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
