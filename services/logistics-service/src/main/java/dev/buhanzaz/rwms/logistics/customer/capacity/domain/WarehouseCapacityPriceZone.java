package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.ForeignKey;
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
 * Versioned simulator tariff polygon copied into logistics for customer price classification.
 * Geometry and prices remain independent from the hard route-time delivery boundary.
 */
@Entity
@Table(
    name = "customer_warehouse_capacity_price_zone",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_warehouse_capacity_price_zone_source",
            columnNames = {"snapshot_id", "source_zone_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseCapacityPriceZone {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "snapshot_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_customer_warehouse_capacity_price_zone_snapshot"))
  private WarehouseCapacitySnapshot snapshot;

  @Column(name = "source_zone_id", nullable = false)
  private UUID sourceZoneId;

  @Column(name = "source_zone_version", nullable = false)
  private long sourceZoneVersion;

  @Column(name = "delivery_price_rubles", nullable = false)
  private long deliveryPriceRubles;

  @Column(name = "pickup_price_rubles", nullable = false)
  private long pickupPriceRubles;

  @Column(name = "geometry_json", nullable = false, columnDefinition = "text")
  private String geometryJson;

  /** Copies validated tariff facts into the owning replacement aggregate. */
  static WarehouseCapacityPriceZone create(
      WarehouseCapacitySnapshot snapshot, Facts facts) {
    WarehouseCapacityPriceZone zone = new WarehouseCapacityPriceZone();
    zone.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    zone.replace(facts);
    return zone;
  }

  /** Replaces one stable simulator-zone version without changing its persistence identity. */
  void replace(Facts facts) {
    Objects.requireNonNull(facts, "facts");
    sourceZoneId = facts.sourceZoneId();
    sourceZoneVersion = facts.sourceZoneVersion();
    deliveryPriceRubles = facts.deliveryPriceRubles();
    pickupPriceRubles = facts.pickupPriceRubles();
    geometryJson = facts.geometryJson();
  }

  /** Boundary-independent immutable facts for one tariff-only GeoJSON MultiPolygon. */
  public record Facts(
      UUID sourceZoneId,
      long sourceZoneVersion,
      long deliveryPriceRubles,
      long pickupPriceRubles,
      String geometryJson) {
    public Facts {
      Objects.requireNonNull(sourceZoneId, "sourceZoneId");
      geometryJson = required(geometryJson, 2_000_000, "geometryJson");
      if (sourceZoneVersion < 0 || deliveryPriceRubles < 0 || pickupPriceRubles < 0) {
        throw new IllegalArgumentException("Planning price-zone facts are invalid");
      }
    }

    private static String required(String value, int maximumLength, String field) {
      if (value == null || value.isBlank() || value.length() > maximumLength) {
        throw new IllegalArgumentException(field + " is invalid");
      }
      return value.trim();
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
        && Objects.equals(id, ((WarehouseCapacityPriceZone) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
