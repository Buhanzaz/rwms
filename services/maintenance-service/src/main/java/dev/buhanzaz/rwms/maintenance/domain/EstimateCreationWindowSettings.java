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
import java.time.OffsetDateTime;
import java.util.UUID;

/** Warehouse-scoped limit for opening a new maintenance estimate after physical return. */
@Entity
@Table(name = "estimate_creation_window_settings")
public class EstimateCreationWindowSettings {
  public static final int DEFAULT_DAYS = 7;
  public static final int MIN_DAYS = 1;
  public static final int MAX_DAYS = 3650;

  @Id
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Min(MIN_DAYS)
  @Max(MAX_DAYS)
  @Column(name = "days", nullable = false)
  private int days;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected EstimateCreationWindowSettings() {}

  /** Creates the first persisted setting for one warehouse. */
  public static EstimateCreationWindowSettings create(UUID warehouseId, int days) {
    if (warehouseId == null) {
      throw new IllegalArgumentException("warehouseId is required");
    }
    EstimateCreationWindowSettings value = new EstimateCreationWindowSettings();
    value.warehouseId = warehouseId;
    value.days = requireDays(days);
    return value;
  }

  /** Replaces the creation window while retaining the warehouse identity and version fence. */
  public void replace(int days) {
    this.days = requireDays(days);
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

  private static int requireDays(int days) {
    if (days < MIN_DAYS || days > MAX_DAYS) {
      throw new IllegalArgumentException(
          "days must be between %d and %d".formatted(MIN_DAYS, MAX_DAYS));
    }
    return days;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public long getVersion() {
    return version;
  }

  public int getDays() {
    return days;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
