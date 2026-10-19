package dev.buhanzaz.rwms.logistics.equipment.domain;

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
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One equipment source reservation and its eventual target in a movement task. */
@Entity
@Table(
    name = "equipment_movement_task_line",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_equipment_movement_task_line_number",
          columnNames = {"task_id", "line_number"}),
      @UniqueConstraint(
          name = "uk_equipment_movement_task_line_reservation",
          columnNames = "reservation_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentMovementTaskLine {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "task_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_equipment_movement_line_task"))
  private EquipmentMovementTask task;

  @Column(name = "line_number", nullable = false)
  private int lineNumber;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "source_warehouse_id", nullable = false)
  private UUID sourceWarehouseId;

  @Column(name = "source_rental_item_id")
  private UUID sourceRentalItemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_location_kind", nullable = false, length = 32)
  private EquipmentMovementLocationKind sourceLocationKind;

  @Column(name = "expected_source_balance_version", nullable = false)
  private long expectedSourceBalanceVersion;

  /** Exact asset balance identity frozen only for an atomic order-unit replacement. */
  @Column(name = "source_balance_id")
  private UUID sourceBalanceId;

  @Column(name = "target_warehouse_id", nullable = false)
  private UUID targetWarehouseId;

  @Column(name = "target_rental_item_id")
  private UUID targetRentalItemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_location_kind", nullable = false, length = 32)
  private EquipmentMovementLocationKind targetLocationKind;

  @Column(name = "quantity", nullable = false)
  private long quantity;

  @Column(name = "reservation_id")
  private UUID reservationId;

  @Column(name = "reservation_version")
  private Long reservationVersion;

  @Column(name = "equipment_name", length = 512)
  private String equipmentName;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private EquipmentMovementLineState state;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static EquipmentMovementTaskLine plan(
      EquipmentMovementTask task,
      int lineNumber,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      EquipmentMovementLocationKind sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      EquipmentMovementLocationKind targetLocationKind,
      long quantity) {
    if (task == null
        || equipmentId == null
        || sourceWarehouseId == null
        || sourceLocationKind == null
        || targetWarehouseId == null
        || targetLocationKind == null) {
      throw new IllegalArgumentException("Equipment movement line locations are required");
    }
    if (lineNumber < 1 || expectedSourceBalanceVersion < 0 || quantity < 1) {
      throw new IllegalArgumentException("Equipment movement line version or quantity is invalid");
    }
    requireLocation(sourceRentalItemId, sourceLocationKind, "source");
    requireLocation(targetRentalItemId, targetLocationKind, "target");
    if (sourceWarehouseId.equals(targetWarehouseId)
        && sameLocation(
            sourceRentalItemId, sourceLocationKind, targetRentalItemId, targetLocationKind)) {
      throw new IllegalArgumentException("Equipment movement source and target must differ");
    }
    EquipmentMovementTaskLine line = new EquipmentMovementTaskLine();
    line.task = task;
    line.lineNumber = lineNumber;
    line.equipmentId = equipmentId;
    line.sourceWarehouseId = sourceWarehouseId;
    line.sourceRentalItemId = sourceRentalItemId;
    line.sourceLocationKind = sourceLocationKind;
    line.expectedSourceBalanceVersion = expectedSourceBalanceVersion;
    line.targetWarehouseId = targetWarehouseId;
    line.targetRentalItemId = targetRentalItemId;
    line.targetLocationKind = targetLocationKind;
    line.quantity = quantity;
    line.state = EquipmentMovementLineState.PENDING_RESERVATION;
    line.createdAt = now();
    line.updatedAt = line.createdAt;
    return line;
  }

  public void reserve(
      UUID nextReservationId,
      long nextReservationVersion,
      UUID nextEquipmentId,
      String nextEquipmentName,
      String reservationState) {
    if (nextReservationId == null
        || nextReservationVersion < 0
        || !equipmentId.equals(nextEquipmentId)
        || !"ACTIVE".equals(reservationState)) {
      throw new IllegalArgumentException("Equipment movement reservation result is invalid");
    }
    if (state != EquipmentMovementLineState.PENDING_RESERVATION
        && state != EquipmentMovementLineState.RESERVED) {
      throw new IllegalStateException("Equipment movement line cannot be reserved");
    }
    if (reservationId != null && !reservationId.equals(nextReservationId)) {
      throw new IllegalStateException("Equipment movement line has a conflicting reservation");
    }
    reservationId = nextReservationId;
    reservationVersion = nextReservationVersion;
    equipmentName = requireText(nextEquipmentName, 512, "equipmentName");
    state = EquipmentMovementLineState.RESERVED;
    touch();
  }

  /** Freezes the exact direct old-cabin source returned by asset movement planning. */
  public void freezeReplacementSourceBalance(UUID nextSourceBalanceId) {
    UUID required = Objects.requireNonNull(nextSourceBalanceId, "sourceBalanceId");
    if (state != EquipmentMovementLineState.PENDING_RESERVATION) {
      throw new IllegalStateException("Replacement source can be frozen only before reservation");
    }
    if (sourceBalanceId != null && !sourceBalanceId.equals(required)) {
      throw new IllegalStateException("Replacement source balance is immutable");
    }
    sourceBalanceId = required;
    touch();
  }

  public void release(long nextReservationVersion, String reservationState) {
    if (reservationId == null
        || reservationVersion == null
        || nextReservationVersion < reservationVersion
        || !"RELEASED".equals(reservationState)) {
      throw new IllegalArgumentException(
          "Equipment movement reservation release result is invalid");
    }
    if (state != EquipmentMovementLineState.RESERVED) return;
    reservationVersion = nextReservationVersion;
    state = EquipmentMovementLineState.RELEASED;
    touch();
  }

  public void execute(long nextReservationVersion) {
    if (reservationId == null
        || reservationVersion == null
        || nextReservationVersion < reservationVersion) {
      throw new IllegalArgumentException(
          "Equipment movement reservation execution result is invalid");
    }
    if (state != EquipmentMovementLineState.RESERVED) {
      throw new IllegalStateException("Only a reserved equipment movement line can execute");
    }
    reservationVersion = nextReservationVersion;
    state = EquipmentMovementLineState.EXECUTED;
    touch();
  }

  private static void requireLocation(
      UUID rentalItemId, EquipmentMovementLocationKind locationKind, String prefix) {
    if (locationKind.requiresRentalItem() != (rentalItemId != null)) {
      throw new IllegalArgumentException(prefix + " location and rental item are inconsistent");
    }
  }

  private static boolean sameLocation(
      UUID leftRentalItem,
      EquipmentMovementLocationKind leftKind,
      UUID rightRentalItem,
      EquipmentMovementLocationKind rightKind) {
    return java.util.Objects.equals(leftRentalItem, rightRentalItem) && leftKind == rightKind;
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private void touch() {
    OffsetDateTime candidate = now();
    updatedAt = candidate.isAfter(updatedAt) ? candidate : updatedAt.plusNanos(1_000);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
