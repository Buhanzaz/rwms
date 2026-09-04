package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Inventory-owned holiday exceptions for future warehouse final-plan generations. */
@Entity
@Table(name = "inventory_planning_settings")
public class InventoryPlanningSettings {
  @Id
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Version
  @Column(name = "settings_revision", nullable = false)
  private long revision;

  @Column(name = "movement_daily_capacity", nullable = false)
  private int movementDailyCapacity;

  @Column(name = "repair_daily_capacity", nullable = false)
  private int repairDailyCapacity;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "working_weekdays", nullable = false, columnDefinition = "jsonb")
  private String workingWeekdays;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "holidays", nullable = false, columnDefinition = "jsonb")
  private String holidays;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryPlanningSettings() {}

  /**
   * Creates a holiday-only setting while retaining V14's non-null legacy columns as inert storage.
   *
   * <p>Those fields are neither read nor changed by planning. Their fixed values exist only until a
   * separately approved storage retirement can remove the old schema safely.
   */
  public static InventoryPlanningSettings create(UUID warehouseId, String holidays) {
    InventoryPlanningSettings value = new InventoryPlanningSettings();
    value.warehouseId = requireWarehouse(warehouseId);
    value.movementDailyCapacity = 1;
    value.repairDailyCapacity = 1;
    value.workingWeekdays = "[\"MONDAY\"]";
    value.replaceHolidays(holidays);
    return value;
  }

  /** Replaces only inventory-owned holiday exceptions under the aggregate version fence. */
  public void replaceHolidays(String nextHolidays) {
    holidays = jsonArray(nextHolidays, "holidays");
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = current;
    updatedAt = current;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public long getRevision() {
    return revision;
  }

  public String getHolidays() {
    return holidays;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  private static UUID requireWarehouse(UUID value) {
    if (value == null) throw new IllegalArgumentException("Warehouse is required");
    return value;
  }

  private static String jsonArray(String value, String field) {
    if (value == null || !value.startsWith("[") || !value.endsWith("]")) {
      throw new IllegalArgumentException(field + " must be a JSON array");
    }
    return value;
  }
}
