package dev.buhanzaz.rwms.logistics.order.recovery;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable normalized intent and receipt values stored by one rental-order mutation command.
 * These records deliberately contain no transport objects or mutable persistence models.
 */
public final class RentalOrderMutationData {
  private RentalOrderMutationData() {}

  /** Frozen remote unit set and exact post-mutation furniture composition. */
  public record Intent(
      List<UnitReservationEvidence> activeUnits,
      List<UnitComposition> remainingComposition) {
    public Intent {
      activeUnits = immutableDistinctUnits(activeUnits, "activeUnits");
      remainingComposition = immutableDistinctComposition(remainingComposition);
    }
  }

  /** Minimal owner receipt needed to prove and audit one unit reservation transition. */
  public record UnitReservationEvidence(
      UUID reservationId,
      UUID unitId,
      UUID warehouseId,
      UUID addedBySubjectId,
      String addedByRole,
      String unitNumber,
      boolean replayed) {
    public UnitReservationEvidence {
      Objects.requireNonNull(reservationId, "reservationId");
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(warehouseId, "warehouseId");
      Objects.requireNonNull(addedBySubjectId, "addedBySubjectId");
      addedByRole = requiredText(addedByRole, "addedByRole");
      unitNumber = requiredText(unitNumber, "unitNumber");
    }
  }

  /** Complete desired furniture requirements retained for one active cabin. */
  public record UnitComposition(
      UUID rentalItemId, List<EquipmentRequirement> requirements) {
    public UnitComposition {
      Objects.requireNonNull(rentalItemId, "rentalItemId");
      requirements = immutableDistinctRequirements(requirements);
    }
  }

  /** Positive quantity of one furniture catalogue position in a frozen composition. */
  public record EquipmentRequirement(UUID equipmentId, long quantity) {
    public EquipmentRequirement {
      Objects.requireNonNull(equipmentId, "equipmentId");
      if (quantity < 1) throw new IllegalArgumentException("quantity is invalid");
    }
  }

  /** Persisted normalized receipt for the release-unit or release-all step. */
  public record ReleasedUnits(List<UnitReservationEvidence> units) {
    public ReleasedUnits {
      units = immutableDistinctUnits(units, "units");
    }
  }

  /** Persisted normalized receipt for the order-wide furniture replacement step. */
  public record EquipmentReservations(List<EquipmentReservationEvidence> reservations) {
    public EquipmentReservations {
      reservations = immutableDistinctEquipmentReservations(reservations);
    }
  }

  /** One source-partitioned furniture reservation returned by asset-service. */
  public record EquipmentReservationEvidence(
      UUID warehouseId,
      UUID equipmentId,
      String equipmentName,
      long quantity,
      long availableQuantity,
      Integer maximumPerCabin) {
    public EquipmentReservationEvidence {
      Objects.requireNonNull(warehouseId, "warehouseId");
      Objects.requireNonNull(equipmentId, "equipmentId");
      equipmentName = requiredText(equipmentName, "equipmentName");
      if (quantity < 1
          || availableQuantity < 0
          || maximumPerCabin != null && maximumPerCabin < 1) {
        throw new IllegalArgumentException("equipment reservation is invalid");
      }
    }
  }

  private static List<UnitReservationEvidence> immutableDistinctUnits(
      List<UnitReservationEvidence> values, String field) {
    if (values == null || values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    List<UnitReservationEvidence> copy = List.copyOf(values);
    Set<UUID> unitIds = new HashSet<>();
    Set<UUID> reservationIds = new HashSet<>();
    if (copy.stream()
        .anyMatch(
            value ->
                !unitIds.add(value.unitId()) || !reservationIds.add(value.reservationId()))) {
      throw new IllegalArgumentException(field + " contains duplicates");
    }
    return copy;
  }

  private static List<UnitComposition> immutableDistinctComposition(
      List<UnitComposition> values) {
    if (values == null || values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("remainingComposition is invalid");
    }
    List<UnitComposition> copy = List.copyOf(values);
    Set<UUID> unitIds = new HashSet<>();
    if (copy.stream().anyMatch(value -> !unitIds.add(value.rentalItemId()))) {
      throw new IllegalArgumentException("remainingComposition contains duplicates");
    }
    return copy;
  }

  private static List<EquipmentRequirement> immutableDistinctRequirements(
      List<EquipmentRequirement> values) {
    if (values == null || values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("requirements are invalid");
    }
    List<EquipmentRequirement> copy = List.copyOf(values);
    Set<UUID> equipmentIds = new HashSet<>();
    if (copy.stream().anyMatch(value -> !equipmentIds.add(value.equipmentId()))) {
      throw new IllegalArgumentException("requirements contain duplicates");
    }
    return copy;
  }

  private static List<EquipmentReservationEvidence> immutableDistinctEquipmentReservations(
      List<EquipmentReservationEvidence> values) {
    if (values == null || values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("reservations are invalid");
    }
    List<EquipmentReservationEvidence> copy = List.copyOf(values);
    Set<String> sourceKeys = new HashSet<>();
    if (copy.stream()
        .anyMatch(value -> !sourceKeys.add(value.warehouseId() + ":" + value.equipmentId()))) {
      throw new IllegalArgumentException("reservations contain duplicates");
    }
    return copy;
  }

  private static String requiredText(String value, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is invalid");
    return normalized;
  }
}
