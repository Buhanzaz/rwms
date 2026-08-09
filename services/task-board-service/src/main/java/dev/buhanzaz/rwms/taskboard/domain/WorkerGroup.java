package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import java.time.OffsetDateTime;

/**
 * Warehouse-scoped operational group with qualification, availability, and audience identity.
 */
@Entity
@Table(
    name = "worker_group",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_worker_group_name",
            columnNames = {"warehouse_id", "name"}),
    indexes =
        @Index(
            name = "idx_worker_group_warehouse",
            columnList = "warehouse_id,active,name"))
public class WorkerGroup extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "worker_class_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_worker_group_class"))
  private WorkerClass workerClass;

  @NotBlank
  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Enumerated(EnumType.STRING)
  @Column(name = "operational_status", nullable = false, length = 32)
  private GroupOperationalStatus operationalStatus = GroupOperationalStatus.AVAILABLE;

  @Column(name = "unavailable_since")
  private OffsetDateTime unavailableSince;

  @Column(name = "unavailability_reason", length = 1000)
  private String unavailabilityReason;

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public void setWarehouseId(UUID warehouseId) {
    this.warehouseId = warehouseId;
  }

  public WorkerClass getWorkerClass() {
    return workerClass;
  }

  public void setWorkerClass(WorkerClass workerClass) {
    this.workerClass = workerClass;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public GroupOperationalStatus getOperationalStatus() {
    return operationalStatus;
  }

  public OffsetDateTime getUnavailableSince() {
    return unavailableSince;
  }

  public String getUnavailabilityReason() {
    return unavailabilityReason;
  }

  public void disable(OffsetDateTime at, String reason) {
    operationalStatus = GroupOperationalStatus.DISABLED;
    unavailableSince = at;
    unavailabilityReason = reason == null || reason.isBlank() ? null : reason.trim();
    touch();
  }

  public void enable() {
    operationalStatus = GroupOperationalStatus.AVAILABLE;
    unavailableSince = null;
    unavailabilityReason = null;
    touch();
  }

  public void touch() {
    revisionMarker = UUID.randomUUID();
  }

  public UUID getRevisionMarker() {
    return revisionMarker;
  }
}
