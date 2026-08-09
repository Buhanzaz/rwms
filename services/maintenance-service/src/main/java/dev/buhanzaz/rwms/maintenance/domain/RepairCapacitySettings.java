package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.UUID;

/** JPA warehouse-level settings for repair-place capacity and automatic refill timing. */
@Entity
@Table(name = "repair_capacity_settings")
public class RepairCapacitySettings {
  public static final int DEFAULT_REPAIR_PLACE_COUNT = 6;
  public static final int DEFAULT_AUTOMATIC_REFILL_DELAY_MINUTES = 5;
  public static final int MIN_AUTOMATIC_REFILL_DELAY_MINUTES = 1;
  public static final int MAX_AUTOMATIC_REFILL_DELAY_MINUTES = 1440;

  @Id
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Positive
  @Column(name = "repair_place_count", nullable = false)
  private int repairPlaceCount;

  @Min(MIN_AUTOMATIC_REFILL_DELAY_MINUTES)
  @Max(MAX_AUTOMATIC_REFILL_DELAY_MINUTES)
  @Column(name = "automatic_refill_delay_minutes", nullable = false)
  private int automaticRefillDelayMinutes;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RepairCapacitySettings() {}

  public static RepairCapacitySettings create(
      UUID warehouseId,
      int repairPlaceCount,
      int automaticRefillDelayMinutes) {
    if (warehouseId == null) {
      throw new IllegalArgumentException("warehouseId is required");
    }
    RepairCapacitySettings value = new RepairCapacitySettings();
    value.warehouseId = warehouseId;
    value.repairPlaceCount = requirePositive(repairPlaceCount);
    value.automaticRefillDelayMinutes = requireAutomaticRefillDelay(automaticRefillDelayMinutes);
    return value;
  }

  public void replace(
      int repairPlaceCount,
      int automaticRefillDelayMinutes) {
    this.repairPlaceCount = requirePositive(repairPlaceCount);
    this.automaticRefillDelayMinutes = requireAutomaticRefillDelay(automaticRefillDelayMinutes);
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

  private static int requireAutomaticRefillDelay(int automaticRefillDelayMinutes) {
    if (automaticRefillDelayMinutes < MIN_AUTOMATIC_REFILL_DELAY_MINUTES
        || automaticRefillDelayMinutes > MAX_AUTOMATIC_REFILL_DELAY_MINUTES) {
      throw new IllegalArgumentException(
          "automaticRefillDelayMinutes must be between %d and %d"
              .formatted(
                  MIN_AUTOMATIC_REFILL_DELAY_MINUTES,
                  MAX_AUTOMATIC_REFILL_DELAY_MINUTES));
    }
    return automaticRefillDelayMinutes;
  }

  public UUID getWarehouseId() { return warehouseId; }
  public long getVersion() { return version; }
  public int getRepairPlaceCount() { return repairPlaceCount; }
  public int getAutomaticRefillDelayMinutes() { return automaticRefillDelayMinutes; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
