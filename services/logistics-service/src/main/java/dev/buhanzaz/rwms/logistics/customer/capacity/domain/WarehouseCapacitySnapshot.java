package dev.buhanzaz.rwms.logistics.customer.capacity.domain;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob.Facts;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned active simulator-capacity projection for one warehouse.
 *
 * <p>The projection contains no rental order, client, cabin, or driver identity. Replacing it
 * changes only CustomerApp slot feasibility; real slots and orders remain independent durable
 * workload.
 */
@Entity
@Table(
    name = "customer_warehouse_capacity_snapshot",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_customer_warehouse_capacity_snapshot_warehouse",
            columnNames = "warehouse_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseCapacitySnapshot {
  public static final long DEFAULT_ISOCHRONE_PRICE_60_MINUTES = 10_000;
  public static final long DEFAULT_ISOCHRONE_PRICE_120_MINUTES = 15_000;
  public static final long DEFAULT_ISOCHRONE_PRICE_180_MINUTES = 20_000;
  public static final long DEFAULT_ISOCHRONE_PRICE_240_MINUTES = 25_000;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "source_generation", nullable = false)
  private long sourceGeneration;

  @Column(name = "source_revision", nullable = false, length = 64)
  private String sourceRevision;

  @Column(name = "isochrone_price_60_minutes", nullable = false)
  private long isochronePrice60Minutes;

  @Column(name = "isochrone_price_120_minutes", nullable = false)
  private long isochronePrice120Minutes;

  @Column(name = "isochrone_price_180_minutes", nullable = false)
  private long isochronePrice180Minutes;

  @Column(name = "isochrone_price_240_minutes", nullable = false)
  private long isochronePrice240Minutes;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("deliveryDate ASC, windowStart ASC, sourceJobId ASC")
  private List<WarehouseCapacityJob> jobs = new ArrayList<>();

  @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("deliveryDate ASC, shiftStart ASC, sourceShiftId ASC")
  private List<WarehouseCapacityShift> shifts = new ArrayList<>();

  @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("sourceZoneId ASC")
  private List<WarehouseCapacityPriceZone> priceZones = new ArrayList<>();

  @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("sourceZoneId ASC")
  private List<WarehouseCapacityRestrictionZone> restrictionZones = new ArrayList<>();

  /** Creates one warehouse projection from a complete, validated replacement command. */
  public static WarehouseCapacitySnapshot create(
      UUID warehouseId,
      long sourceGeneration,
      String sourceRevision,
      List<Facts> jobs,
      List<WarehouseCapacityShift.Facts> shifts,
      List<WarehouseCapacityPriceZone.Facts> priceZones,
      long isochronePrice60Minutes,
      long isochronePrice120Minutes,
      long isochronePrice180Minutes,
      long isochronePrice240Minutes,
      List<WarehouseCapacityRestrictionZone.Facts> restrictionZones,
      OffsetDateTime now) {
    WarehouseCapacitySnapshot snapshot = new WarehouseCapacitySnapshot();
    snapshot.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    snapshot.createdAt = Objects.requireNonNull(now, "now");
    snapshot.replace(
        sourceGeneration,
        sourceRevision,
        jobs,
        shifts,
        priceZones,
        isochronePrice60Minutes,
        isochronePrice120Minutes,
        isochronePrice180Minutes,
        isochronePrice240Minutes,
        restrictionZones,
        now);
    return snapshot;
  }

  /** Preserves callers that predate warehouse isochrone tariffs and restriction polygons. */
  public static WarehouseCapacitySnapshot create(
      UUID warehouseId,
      long sourceGeneration,
      String sourceRevision,
      List<Facts> jobs,
      List<WarehouseCapacityShift.Facts> shifts,
      List<WarehouseCapacityPriceZone.Facts> priceZones,
      OffsetDateTime now) {
    return create(
        warehouseId,
        sourceGeneration,
        sourceRevision,
        jobs,
        shifts,
        priceZones,
        DEFAULT_ISOCHRONE_PRICE_60_MINUTES,
        DEFAULT_ISOCHRONE_PRICE_120_MINUTES,
        DEFAULT_ISOCHRONE_PRICE_180_MINUTES,
        DEFAULT_ISOCHRONE_PRICE_240_MINUTES,
        List.of(),
        now);
  }

  /** Atomically replaces the active simulator facts without mutating any real booking workload. */
  public void replace(
      long sourceGeneration,
      String sourceRevision,
      List<Facts> jobs,
      List<WarehouseCapacityShift.Facts> shifts,
      List<WarehouseCapacityPriceZone.Facts> priceZones,
      long isochronePrice60Minutes,
      long isochronePrice120Minutes,
      long isochronePrice180Minutes,
      long isochronePrice240Minutes,
      List<WarehouseCapacityRestrictionZone.Facts> restrictionZones,
      OffsetDateTime now) {
    if (sourceGeneration < 1) {
      throw new IllegalArgumentException("sourceGeneration must be positive");
    }
    this.sourceGeneration = sourceGeneration;
    this.sourceRevision = requireSha256(sourceRevision, "sourceRevision");
    requireNonNegativeTariffs(
        isochronePrice60Minutes,
        isochronePrice120Minutes,
        isochronePrice180Minutes,
        isochronePrice240Minutes);
    this.isochronePrice60Minutes = isochronePrice60Minutes;
    this.isochronePrice120Minutes = isochronePrice120Minutes;
    this.isochronePrice180Minutes = isochronePrice180Minutes;
    this.isochronePrice240Minutes = isochronePrice240Minutes;
    Map<UUID, WarehouseCapacityJob> existingBySource = new HashMap<>();
    this.jobs.forEach(job -> existingBySource.put(job.getSourceJobId(), job));
    Set<UUID> requestedSources = new HashSet<>();
    for (Facts facts : Objects.requireNonNull(jobs, "jobs")) {
      requestedSources.add(facts.sourceJobId());
      WarehouseCapacityJob existing = existingBySource.get(facts.sourceJobId());
      if (existing == null) this.jobs.add(WarehouseCapacityJob.create(this, facts));
      else existing.replace(facts);
    }
    this.jobs.removeIf(job -> !requestedSources.contains(job.getSourceJobId()));
    this.jobs.sort(
        Comparator.comparing(WarehouseCapacityJob::getDeliveryDate)
            .thenComparing(WarehouseCapacityJob::getWindowStart)
            .thenComparing(WarehouseCapacityJob::getSourceJobId));
    Map<UUID, WarehouseCapacityShift> existingShiftBySource = new HashMap<>();
    this.shifts.forEach(shift -> existingShiftBySource.put(shift.getSourceShiftId(), shift));
    Set<UUID> requestedShiftSources = new HashSet<>();
    for (WarehouseCapacityShift.Facts facts : Objects.requireNonNull(shifts, "shifts")) {
      requestedShiftSources.add(facts.sourceShiftId());
      WarehouseCapacityShift existing = existingShiftBySource.get(facts.sourceShiftId());
      if (existing == null) this.shifts.add(WarehouseCapacityShift.create(this, facts));
      else existing.replace(facts);
    }
    this.shifts.removeIf(shift -> !requestedShiftSources.contains(shift.getSourceShiftId()));
    this.shifts.sort(
        Comparator.comparing(WarehouseCapacityShift::getDeliveryDate)
            .thenComparing(WarehouseCapacityShift::getShiftStart)
            .thenComparing(WarehouseCapacityShift::getSourceShiftId));
    Map<UUID, WarehouseCapacityPriceZone> existingZoneBySource = new HashMap<>();
    this.priceZones.forEach(zone -> existingZoneBySource.put(zone.getSourceZoneId(), zone));
    Set<UUID> requestedZoneSources = new HashSet<>();
    for (WarehouseCapacityPriceZone.Facts facts :
        Objects.requireNonNull(priceZones, "priceZones")) {
      requestedZoneSources.add(facts.sourceZoneId());
      WarehouseCapacityPriceZone existing = existingZoneBySource.get(facts.sourceZoneId());
      if (existing == null) {
        this.priceZones.add(WarehouseCapacityPriceZone.create(this, facts));
      } else {
        existing.replace(facts);
      }
    }
    this.priceZones.removeIf(zone -> !requestedZoneSources.contains(zone.getSourceZoneId()));
    this.priceZones.sort(Comparator.comparing(WarehouseCapacityPriceZone::getSourceZoneId));
    Map<UUID, WarehouseCapacityRestrictionZone> existingRestrictionBySource = new HashMap<>();
    this.restrictionZones.forEach(
        zone -> existingRestrictionBySource.put(zone.getSourceZoneId(), zone));
    Set<UUID> requestedRestrictionSources = new HashSet<>();
    for (WarehouseCapacityRestrictionZone.Facts facts :
        Objects.requireNonNull(restrictionZones, "restrictionZones")) {
      requestedRestrictionSources.add(facts.sourceZoneId());
      WarehouseCapacityRestrictionZone existing =
          existingRestrictionBySource.get(facts.sourceZoneId());
      if (existing == null) {
        this.restrictionZones.add(WarehouseCapacityRestrictionZone.create(this, facts));
      } else {
        existing.replace(facts);
      }
    }
    this.restrictionZones.removeIf(
        zone -> !requestedRestrictionSources.contains(zone.getSourceZoneId()));
    this.restrictionZones.sort(
        Comparator.comparing(WarehouseCapacityRestrictionZone::getSourceZoneId));
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  /** Preserves replacement code that predates warehouse isochrone and restriction facts. */
  public void replace(
      long sourceGeneration,
      String sourceRevision,
      List<Facts> jobs,
      List<WarehouseCapacityShift.Facts> shifts,
      List<WarehouseCapacityPriceZone.Facts> priceZones,
      OffsetDateTime now) {
    replace(
        sourceGeneration,
        sourceRevision,
        jobs,
        shifts,
        priceZones,
        DEFAULT_ISOCHRONE_PRICE_60_MINUTES,
        DEFAULT_ISOCHRONE_PRICE_120_MINUTES,
        DEFAULT_ISOCHRONE_PRICE_180_MINUTES,
        DEFAULT_ISOCHRONE_PRICE_240_MINUTES,
        List.of(),
        now);
  }

  /** Returns the configured ordinary-delivery price for one canonical isochrone tier. */
  public long deliveryPriceForIsochroneMinutes(int minutes) {
    return switch (minutes) {
      case 60 -> isochronePrice60Minutes;
      case 120 -> isochronePrice120Minutes;
      case 180 -> isochronePrice180Minutes;
      case 240 -> isochronePrice240Minutes;
      default -> throw new IllegalArgumentException("Unsupported isochrone price tier");
    };
  }

  private static void requireNonNegativeTariffs(long price60, long price120, long price180, long price240) {
    if (price60 < 0 || price120 < 0 || price180 < 0 || price240 < 0) {
      throw new IllegalArgumentException("Isochrone prices must be non-negative");
    }
  }

  private static String requireSha256(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
    return value;
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
        && Objects.equals(id, ((WarehouseCapacitySnapshot) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
