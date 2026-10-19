package dev.buhanzaz.rwms.logistics.vehicle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned reservation and history for one physical vehicle used by a confirmed transfer.
 * Vehicle and warehouse identities remain opaque owner-issued UUIDs; this aggregate never copies
 * the vehicle catalog or changes its home warehouse.
 */
@Entity
@Table(
    name = "vehicle_operational_assignment",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_vehicle_operational_assignment_transfer_vehicle",
            columnNames = {"transfer_id", "vehicle_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VehicleOperationalAssignment {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @NotNull
  @Column(name = "transfer_id", nullable = false)
  private UUID transferId;

  @NotNull
  @Column(name = "vehicle_id", nullable = false)
  private UUID vehicleId;

  @NotNull
  @Column(name = "source_warehouse_id", nullable = false)
  private UUID sourceWarehouseId;

  @NotNull
  @Column(name = "destination_warehouse_id", nullable = false)
  private UUID destinationWarehouseId;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "mode", nullable = false, length = 16)
  private VehicleOperationalAssignmentMode mode;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private VehicleOperationalAssignmentStatus status;

  @NotNull
  @Column(name = "travel_starts_at", nullable = false)
  private OffsetDateTime travelStartsAt;

  @NotNull
  @Column(name = "effective_from", nullable = false)
  private OffsetDateTime effectiveFrom;

  @Column(name = "effective_until")
  private OffsetDateTime effectiveUntil;

  @NotNull
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @NotNull
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates one planned travel reservation and its mode-specific placement intent. */
  public static VehicleOperationalAssignment planned(
      UUID transferId,
      UUID vehicleId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      VehicleOperationalAssignmentMode mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil,
      OffsetDateTime now) {
    VehicleOperationalAssignment assignment = new VehicleOperationalAssignment();
    assignment.transferId = Objects.requireNonNull(transferId, "transferId");
    assignment.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
    assignment.sourceWarehouseId = Objects.requireNonNull(sourceWarehouseId, "sourceWarehouseId");
    assignment.destinationWarehouseId =
        Objects.requireNonNull(destinationWarehouseId, "destinationWarehouseId");
    if (assignment.sourceWarehouseId.equals(assignment.destinationWarehouseId)) {
      throw new IllegalArgumentException("Vehicle assignment warehouses must differ");
    }
    assignment.mode = Objects.requireNonNull(mode, "mode");
    assignment.travelStartsAt = Objects.requireNonNull(travelStartsAt, "travelStartsAt");
    assignment.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    assignment.effectiveUntil = effectiveUntil;
    validateInterval(mode, travelStartsAt, effectiveFrom, effectiveUntil);
    assignment.status = VehicleOperationalAssignmentStatus.PLANNED;
    assignment.createdAt = Objects.requireNonNull(now, "now");
    assignment.updatedAt = now;
    return assignment;
  }

  /** Marks this reservation as travelling; exact replays keep the current version unchanged. */
  public void beginTransit(OffsetDateTime now) {
    if (status == VehicleOperationalAssignmentStatus.IN_TRANSIT) return;
    if (status != VehicleOperationalAssignmentStatus.PLANNED) {
      throw new IllegalStateException("Only a planned vehicle assignment can enter transit");
    }
    status = VehicleOperationalAssignmentStatus.IN_TRANSIT;
    updatedAt = requiredTransitionTime(now);
  }

  /**
   * Applies arrival semantics: trip-only reservations complete, while repositioned vehicles become
   * active at the destination.
   */
  public void arrive(OffsetDateTime now) {
    VehicleOperationalAssignmentStatus target =
        mode == VehicleOperationalAssignmentMode.TRIP_ONLY
            ? VehicleOperationalAssignmentStatus.COMPLETED
            : VehicleOperationalAssignmentStatus.ACTIVE;
    if (isArrivalApplied()) return;
    if (status != VehicleOperationalAssignmentStatus.IN_TRANSIT) {
      throw new IllegalStateException("Only an in-transit vehicle assignment can arrive");
    }
    status = target;
    updatedAt = requiredTransitionTime(now);
  }

  /** Cancels one pre-start reservation without deleting its planning history. */
  public void cancelBeforeStart(OffsetDateTime now) {
    if (status == VehicleOperationalAssignmentStatus.CANCELLED) return;
    if (status != VehicleOperationalAssignmentStatus.PLANNED) {
      throw new IllegalStateException("Only a planned vehicle assignment can be cancelled");
    }
    status = VehicleOperationalAssignmentStatus.CANCELLED;
    updatedAt = requiredTransitionTime(now);
  }

  /**
   * Closes an active temporary/permanent placement when its reposition successor actually
   * arrives. Admission and the caller's vehicle advisory lock guarantee that the successor left
   * the location resolved by this placement. A temporary end that was already earlier remains
   * unchanged instead of being extended by a later successor arrival.
   */
  public void completePlacement(OffsetDateTime placementEndsAt, OffsetDateTime now) {
    if (mode == VehicleOperationalAssignmentMode.TRIP_ONLY
        || status != VehicleOperationalAssignmentStatus.ACTIVE) {
      throw new IllegalStateException(
          "Only an active temporary or permanent placement can be completed");
    }
    OffsetDateTime completedAt = Objects.requireNonNull(placementEndsAt, "placementEndsAt");
    if (!completedAt.isAfter(effectiveFrom)) {
      throw new IllegalArgumentException(
          "A completed placement end must be after it became effective");
    }
    effectiveUntil = completedAt;
    status = VehicleOperationalAssignmentStatus.COMPLETED;
    updatedAt = requiredTransitionTime(now);
  }

  /** Returns whether this live placement contains the supplied half-open departure instant. */
  public boolean containsPlacementAt(OffsetDateTime departureAt) {
    if (status != VehicleOperationalAssignmentStatus.ACTIVE
        || mode == VehicleOperationalAssignmentMode.TRIP_ONLY) {
      return false;
    }
    OffsetDateTime departure = Objects.requireNonNull(departureAt, "departureAt");
    return !departure.isBefore(effectiveFrom)
        && (effectiveUntil == null || departure.isBefore(effectiveUntil));
  }

  /**
   * Returns whether the row originated from the exact assignment plan. A completed reposition may
   * have replaced its planned placement end with the later authoritative chain transition.
   */
  public boolean matchesPlan(
      UUID expectedVehicleId,
      UUID expectedSourceWarehouseId,
      UUID expectedDestinationWarehouseId,
      VehicleOperationalAssignmentMode expectedMode,
      OffsetDateTime expectedTravelStartsAt,
      OffsetDateTime expectedEffectiveFrom,
      OffsetDateTime expectedEffectiveUntil) {
    return vehicleId.equals(expectedVehicleId)
        && sourceWarehouseId.equals(expectedSourceWarehouseId)
        && destinationWarehouseId.equals(expectedDestinationWarehouseId)
        && mode == expectedMode
        && travelStartsAt.isEqual(expectedTravelStartsAt)
        && effectiveFrom.isEqual(expectedEffectiveFrom)
        && (status == VehicleOperationalAssignmentStatus.COMPLETED
                && mode != VehicleOperationalAssignmentMode.TRIP_ONLY
            || sameInstant(effectiveUntil, expectedEffectiveUntil));
  }

  /** Returns whether arrival has reached the mode-specific terminal operational state. */
  public boolean isArrivalApplied() {
    return mode == VehicleOperationalAssignmentMode.TRIP_ONLY
        ? status == VehicleOperationalAssignmentStatus.COMPLETED
        : status == VehicleOperationalAssignmentStatus.ACTIVE
            || status == VehicleOperationalAssignmentStatus.COMPLETED;
  }

  /** Materializes the immutable raw facts consumed by internal planning projections. */
  public VehicleOperationalAssignmentSnapshot snapshot() {
    return new VehicleOperationalAssignmentSnapshot(
        id,
        version,
        transferId,
        vehicleId,
        sourceWarehouseId,
        destinationWarehouseId,
        mode,
        status,
        travelStartsAt,
        effectiveFrom,
        effectiveUntil,
        createdAt,
        updatedAt);
  }

  private OffsetDateTime requiredTransitionTime(OffsetDateTime value) {
    OffsetDateTime timestamp = Objects.requireNonNull(value, "now");
    if (timestamp.isBefore(updatedAt)) {
      throw new IllegalArgumentException("Vehicle assignment transition time moved backwards");
    }
    return timestamp;
  }

  private static void validateInterval(
      VehicleOperationalAssignmentMode mode,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil) {
    if (!travelStartsAt.isBefore(effectiveFrom)) {
      throw new IllegalArgumentException("Vehicle assignment travel interval is invalid");
    }
    if (mode == VehicleOperationalAssignmentMode.TRIP_ONLY) {
      if (effectiveUntil == null || !effectiveUntil.isEqual(effectiveFrom)) {
        throw new IllegalArgumentException("Trip-only assignment must end at effectiveFrom");
      }
      return;
    }
    if (mode == VehicleOperationalAssignmentMode.TEMPORARY) {
      if (effectiveUntil == null || !effectiveUntil.isAfter(effectiveFrom)) {
        throw new IllegalArgumentException("Temporary assignment end is invalid");
      }
      return;
    }
    if (effectiveUntil != null) {
      throw new IllegalArgumentException("Permanent assignment cannot have an end");
    }
  }

  private static boolean sameInstant(OffsetDateTime first, OffsetDateTime second) {
    return first == null ? second == null : second != null && first.isEqual(second);
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
        && Objects.equals(id, ((VehicleOperationalAssignment) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
