package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Header of one immutable-template-snapshotted pre-trip vehicle inspection. */
@Entity
@Table(
    name = "vehicle_inspection",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_vehicle_inspection_shift", columnNames = "shift_id"))
public class VehicleInspection extends AbstractVersionedEntity {
  @Column(name = "shift_id", nullable = false)
  private UUID shiftId;

  @Column(name = "vehicle_id", nullable = false)
  private UUID vehicleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "configuration_type", nullable = false, length = 32)
  private VehicleConfigurationType configurationType;

  @Column(name = "template_version", nullable = false)
  private long templateVersion;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  /** Creates a header before copying every enabled template item into result rows. */
  public void initialize(
      UUID shiftId,
      UUID vehicleId,
      VehicleConfigurationType type,
      long templateVersion,
      OffsetDateTime now) {
    this.shiftId = shiftId;
    this.vehicleId = vehicleId;
    configurationType = type;
    this.templateVersion = templateVersion;
    createdAt = now;
  }

  /** Marks this inspection complete once the service has validated every required result. */
  public void complete(OffsetDateTime now) {
    if (completedAt == null) completedAt = now;
  }

  public UUID getShiftId() {
    return shiftId;
  }

  public UUID getVehicleId() {
    return vehicleId;
  }

  public VehicleConfigurationType getConfigurationType() {
    return configurationType;
  }

  public long getTemplateVersion() {
    return templateVersion;
  }

  public OffsetDateTime getCompletedAt() {
    return completedAt;
  }
}
