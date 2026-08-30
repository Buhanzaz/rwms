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
  @OrderBy("travelMinutes ASC")
  private List<WarehouseCapacityIsochroneTariff> isochroneTariffs = new ArrayList<>();

  /** Creates one warehouse projection from a complete, validated replacement command. */
  public static WarehouseCapacitySnapshot create(
      UUID warehouseId,
      long sourceGeneration,
      String sourceRevision,
      List<Facts> jobs,
      List<WarehouseCapacityShift.Facts> shifts,
      List<WarehouseCapacityIsochroneTariff.Facts> isochroneTariffs,
      OffsetDateTime now) {
    WarehouseCapacitySnapshot snapshot = new WarehouseCapacitySnapshot();
    snapshot.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    snapshot.createdAt = Objects.requireNonNull(now, "now");
    snapshot.replace(
        sourceGeneration,
        sourceRevision,
        jobs,
        shifts,
        isochroneTariffs,
        now);
    return snapshot;
  }

  /** Atomically replaces the active simulator facts without mutating any real booking workload. */
  public void replace(
      long sourceGeneration,
      String sourceRevision,
      List<Facts> jobs,
      List<WarehouseCapacityShift.Facts> shifts,
      List<WarehouseCapacityIsochroneTariff.Facts> isochroneTariffs,
      OffsetDateTime now) {
    if (sourceGeneration < 1) {
      throw new IllegalArgumentException("sourceGeneration must be positive");
    }
    this.sourceGeneration = sourceGeneration;
    this.sourceRevision = requireSha256(sourceRevision, "sourceRevision");
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
    List<WarehouseCapacityIsochroneTariff.Facts> requestedTariffs =
        requireContiguousTariffs(isochroneTariffs);
    Map<Integer, WarehouseCapacityIsochroneTariff> existingTariffs = new HashMap<>();
    this.isochroneTariffs.forEach(
        tariff -> existingTariffs.put(tariff.getTravelMinutes(), tariff));
    Set<Integer> requestedMinutes = new HashSet<>();
    for (WarehouseCapacityIsochroneTariff.Facts facts : requestedTariffs) {
      requestedMinutes.add(facts.travelMinutes());
      WarehouseCapacityIsochroneTariff existing = existingTariffs.get(facts.travelMinutes());
      if (existing == null) {
        this.isochroneTariffs.add(WarehouseCapacityIsochroneTariff.create(this, facts));
      } else {
        existing.replace(facts);
      }
    }
    this.isochroneTariffs.removeIf(
        tariff -> !requestedMinutes.contains(tariff.getTravelMinutes()));
    this.isochroneTariffs.sort(
        Comparator.comparingInt(WarehouseCapacityIsochroneTariff::getTravelMinutes));
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  private static List<WarehouseCapacityIsochroneTariff.Facts> requireContiguousTariffs(
      List<WarehouseCapacityIsochroneTariff.Facts> tariffs) {
    List<WarehouseCapacityIsochroneTariff.Facts> values =
        List.copyOf(Objects.requireNonNull(tariffs, "isochroneTariffs"));
    if (values.isEmpty() || values.size() > 12) {
      throw new IllegalArgumentException("Between one and twelve isochrone tariffs are required");
    }
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).travelMinutes() != (index + 1) * 60) {
        throw new IllegalArgumentException(
            "Isochrone tariffs must be contiguous hourly tiers starting at 60 minutes");
      }
    }
    return values;
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
