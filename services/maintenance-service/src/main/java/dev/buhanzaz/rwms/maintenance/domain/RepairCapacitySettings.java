package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "repair_capacity_settings")
public class RepairCapacitySettings {
  public static final int DEFAULT_REPAIR_PLACE_COUNT = 6;

  @Id
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Positive
  @Column(name = "repair_place_count", nullable = false)
  private int repairPlaceCount;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RepairCapacitySettings() {}

  public static RepairCapacitySettings create(UUID warehouseId, int repairPlaceCount) {
    if (warehouseId == null) {
      throw new IllegalArgumentException("warehouseId is required");
    }
    RepairCapacitySettings value = new RepairCapacitySettings();
    value.warehouseId = warehouseId;
    value.repairPlaceCount = requirePositive(repairPlaceCount);
    return value;
  }

  public void replace(int repairPlaceCount) {
    this.repairPlaceCount = requirePositive(repairPlaceCount);
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

  private static int requirePositive(int repairPlaceCount) {
    if (repairPlaceCount < 1) {
      throw new IllegalArgumentException("repairPlaceCount must be positive");
    }
    return repairPlaceCount;
  }

  public UUID getWarehouseId() { return warehouseId; }
  public long getVersion() { return version; }
  public int getRepairPlaceCount() { return repairPlaceCount; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
