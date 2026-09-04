package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable ordered route operation retained under one replaceable-until-frozen shift plan.
 *
 * <p>Customer task and warehouse-transfer identities are mutually exclusive and are validated
 * before persistence against the operation kind.
 */
@Entity
@Table(
    name = "driver_shift_route_operation",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_driver_shift_route_operation_sequence",
            columnNames = {"driver_shift_plan_id", "operation_sequence"}),
    indexes =
        @Index(
            name = "idx_driver_shift_route_operation_plan",
            columnList = "driver_shift_plan_id,operation_sequence"))
public class DriverShiftRouteOperation extends AbstractVersionedEntity {
  @Column(name = "driver_shift_plan_id", nullable = false)
  private UUID driverShiftPlanId;

  @Column(name = "operation_sequence", nullable = false)
  private int sequence;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation_kind", nullable = false, length = 32)
  private DriverShiftRouteOperationKind kind;

  @Column(name = "warehouse_id")
  private UUID warehouseId;

  @Column(name = "source_task_id")
  private UUID sourceTaskId;

  @Column(name = "source_transfer_id")
  private UUID sourceTransferId;

  @Column(name = "location_label", nullable = false, length = 500)
  private String locationLabel;

  @Column(name = "planned_arrival", nullable = false)
  private OffsetDateTime plannedArrival;

  @Column(name = "planned_departure", nullable = false)
  private OffsetDateTime plannedDeparture;

  @Column(name = "load_before", nullable = false)
  private int loadBefore;

  @Column(name = "load_after", nullable = false)
  private int loadAfter;

  /** Initializes one already-validated immutable operation before first persistence. */
  public void initialize(
      UUID driverShiftPlanId,
      int sequence,
      DriverShiftRouteOperationKind kind,
      UUID warehouseId,
      UUID sourceTaskId,
      UUID sourceTransferId,
      String locationLabel,
      OffsetDateTime plannedArrival,
      OffsetDateTime plannedDeparture,
      int loadBefore,
      int loadAfter) {
    this.driverShiftPlanId = driverShiftPlanId;
    this.sequence = sequence;
    this.kind = kind;
    this.warehouseId = warehouseId;
    this.sourceTaskId = sourceTaskId;
    this.sourceTransferId = sourceTransferId;
    this.locationLabel = locationLabel.trim();
    this.plannedArrival = plannedArrival;
    this.plannedDeparture = plannedDeparture;
    this.loadBefore = loadBefore;
    this.loadAfter = loadAfter;
  }

  public UUID getDriverShiftPlanId() {
    return driverShiftPlanId;
  }

  public int getSequence() {
    return sequence;
  }

  public DriverShiftRouteOperationKind getKind() {
    return kind;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getSourceTaskId() {
    return sourceTaskId;
  }

  public UUID getSourceTransferId() {
    return sourceTransferId;
  }

  public String getLocationLabel() {
    return locationLabel;
  }

  public OffsetDateTime getPlannedArrival() {
    return plannedArrival;
  }

  public OffsetDateTime getPlannedDeparture() {
    return plannedDeparture;
  }

  public int getLoadBefore() {
    return loadBefore;
  }

  public int getLoadAfter() {
    return loadAfter;
  }
}
