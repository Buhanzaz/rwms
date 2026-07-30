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
  public static final int DEFAULT_MAX_REPAIRS_PER_DAY = 6;

  @Id
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Positive
  @Column(name = "max_repairs_per_day", nullable = false)
  private int maxRepairsPerDay;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RepairCapacitySettings() {}

  public static RepairCapacitySettings create(UUID warehouseId, int maxRepairsPerDay) {
    if (warehouseId == null) {
      throw new IllegalArgumentException("warehouseId is required");
    }
    RepairCapacitySettings value = new RepairCapacitySettings();
    value.warehouseId = warehouseId;
    value.maxRepairsPerDay = requirePositive(maxRepairsPerDay);
    return value;
  }

  public void replace(int maxRepairsPerDay) {
    this.maxRepairsPerDay = requirePositive(maxRepairsPerDay);
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

  private static int requirePositive(int maxRepairsPerDay) {
    if (maxRepairsPerDay < 1) {
      throw new IllegalArgumentException("maxRepairsPerDay must be positive");
    }
    return maxRepairsPerDay;
  }

  public UUID getWarehouseId() { return warehouseId; }
  public long getVersion() { return version; }
  public int getMaxRepairsPerDay() { return maxRepairsPerDay; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
