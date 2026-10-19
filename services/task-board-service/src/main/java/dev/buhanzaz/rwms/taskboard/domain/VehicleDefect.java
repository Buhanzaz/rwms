package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Shared driver-reported vehicle defect used by both inspection and shift closing. */
@Entity
@Table(
    name = "vehicle_defect",
    indexes = {
      @Index(name = "idx_vehicle_defect_shift", columnList = "shift_id,created_at"),
      @Index(name = "idx_vehicle_defect_vehicle_status", columnList = "vehicle_id,status")
    })
public class VehicleDefect extends AbstractVersionedEntity {
  @Column(name = "shift_id", nullable = false)
  private UUID shiftId;

  @Column(name = "driver_id", nullable = false)
  private UUID driverId;

  @Column(name = "vehicle_id", nullable = false)
  private UUID vehicleId;

  @Column(name = "work_date", nullable = false)
  private LocalDate workDate;

  @Column(name = "inspection_item_id")
  private UUID inspectionItemId;

  @Column(name = "description", nullable = false, length = 2000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16)
  private DefectSeverity severity;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private DefectStatus status;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "resolved_at")
  private OffsetDateTime resolvedAt;

  /** Creates one client-identified open defect with the supplied operational severity. */
  public void initialize(
      UUID shiftId,
      UUID driverId,
      UUID vehicleId,
      LocalDate workDate,
      UUID itemId,
      String description,
      DefectSeverity severity,
      OffsetDateTime now) {
    this.shiftId = shiftId;
    this.driverId = driverId;
    this.vehicleId = vehicleId;
    this.workDate = workDate;
    inspectionItemId = itemId;
    this.description = description.trim();
    this.severity = severity;
    status = DefectStatus.OPEN;
    createdAt = now;
  }

  /** Updates description only while the same driver workflow owns the still-open defect. */
  public void revise(String description) {
    if (status != DefectStatus.OPEN)
      throw new IllegalStateException("Resolved defect cannot be revised");
    this.description = description.trim();
  }

  public UUID getShiftId() {
    return shiftId;
  }

  public UUID getDriverId() {
    return driverId;
  }

  public UUID getVehicleId() {
    return vehicleId;
  }

  public LocalDate getWorkDate() {
    return workDate;
  }

  public UUID getInspectionItemId() {
    return inspectionItemId;
  }

  public String getDescription() {
    return description;
  }

  public DefectSeverity getSeverity() {
    return severity;
  }

  public DefectStatus getStatus() {
    return status;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
