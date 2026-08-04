package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "repair_place_allocation")
public class RepairPlaceAllocation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "repair_id", nullable = false)
  private UUID repairId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private RepairPlaceAllocationState state;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RepairPlaceAllocation() {}

  public static RepairPlaceAllocation reserve(UUID warehouseId, UUID repairId) {
    if (warehouseId == null || repairId == null) {
      throw new IllegalArgumentException("Repair-place allocation identity is required");
    }
    RepairPlaceAllocation value = new RepairPlaceAllocation();
    value.warehouseId = warehouseId;
    value.repairId = repairId;
    value.state = RepairPlaceAllocationState.RESERVED;
    return value;
  }

  public void occupy() {
    transition(RepairPlaceAllocationState.RESERVED, RepairPlaceAllocationState.OCCUPIED);
  }

  public void readyToRelease() {
    transition(
        RepairPlaceAllocationState.OCCUPIED,
        RepairPlaceAllocationState.READY_TO_RELEASE);
  }

  public void release() {
    if (state != RepairPlaceAllocationState.RESERVED
        && state != RepairPlaceAllocationState.READY_TO_RELEASE) {
      throw new IllegalStateException(
          "Only a reservation or a ready repair place can be released");
    }
    state = RepairPlaceAllocationState.RELEASED;
  }

  /**
   * Keeps a cabin physically in its occupied repair place while a proved pre-start replacement
   * moves the maintenance lifecycle to a new repair aggregate.
   */
  public void reassignForInventoryReplacement(UUID successorRepairId) {
    if (successorRepairId == null) {
      throw new IllegalArgumentException("Inventory replacement successor is required");
    }
    if (state != RepairPlaceAllocationState.OCCUPIED) {
      throw new IllegalStateException(
          "Only an occupied repair place can move to an inventory replacement");
    }
    if (successorRepairId.equals(repairId)) return;
    repairId = successorRepairId;
  }

  private void transition(
      RepairPlaceAllocationState expected, RepairPlaceAllocationState target) {
    if (state != expected) {
      throw new IllegalStateException(
          "Repair-place allocation must be %s before %s"
              .formatted(expected, target));
    }
    state = target;
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = MaintenanceTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = MaintenanceTime.now();
  }

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public UUID getWarehouseId() { return warehouseId; }
  public UUID getRepairId() { return repairId; }
  public RepairPlaceAllocationState getState() { return state; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
