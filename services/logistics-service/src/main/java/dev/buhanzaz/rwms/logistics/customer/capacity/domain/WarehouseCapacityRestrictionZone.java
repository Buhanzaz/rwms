package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Versioned simulator polygon that either forbids delivery or requires a trailer-free route.
 * Geometry remains an opaque, validated GeoJSON MultiPolygon until point classification.
 */
@Entity
@Table(
    name = "customer_warehouse_capacity_restriction_zone",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_warehouse_capacity_restriction_zone_source",
            columnNames = {"snapshot_id", "source_zone_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseCapacityRestrictionZone {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "snapshot_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_customer_warehouse_capacity_restriction_zone_snapshot"))
  private WarehouseCapacitySnapshot snapshot;

  @Column(name = "source_zone_id", nullable = false)
  private UUID sourceZoneId;

  @Column(name = "source_zone_version", nullable = false)
  private long sourceZoneVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "restriction_kind", nullable = false, length = 32)
  private WarehouseCapacityRestrictionKind kind;

  @Column(name = "geometry_json", nullable = false, columnDefinition = "text")
  private String geometryJson;

  /** Copies validated restriction facts into the owning replacement aggregate. */
  static WarehouseCapacityRestrictionZone create(
      WarehouseCapacitySnapshot snapshot, Facts facts) {
    WarehouseCapacityRestrictionZone zone = new WarehouseCapacityRestrictionZone();
    zone.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    zone.replace(facts);
    return zone;
  }

  /** Replaces one stable source-zone version without changing persistence identity. */
  void replace(Facts facts) {
    Objects.requireNonNull(facts, "facts");
    sourceZoneId = facts.sourceZoneId();
    sourceZoneVersion = facts.sourceZoneVersion();
    kind = facts.kind();
    geometryJson = facts.geometryJson();
  }

  /** Boundary-independent immutable facts for one route-restriction MultiPolygon. */
  public record Facts(
      UUID sourceZoneId,
      long sourceZoneVersion,
      WarehouseCapacityRestrictionKind kind,
      String geometryJson) {
    public Facts {
      Objects.requireNonNull(sourceZoneId, "sourceZoneId");
      Objects.requireNonNull(kind, "kind");
      if (sourceZoneVersion < 0) {
        throw new IllegalArgumentException("Planning restriction-zone version is invalid");
      }
      if (geometryJson == null || geometryJson.isBlank() || geometryJson.length() > 2_000_000) {
        throw new IllegalArgumentException("geometryJson is invalid");
      }
      geometryJson = geometryJson.trim();
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
        && Objects.equals(id, ((WarehouseCapacityRestrictionZone) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
