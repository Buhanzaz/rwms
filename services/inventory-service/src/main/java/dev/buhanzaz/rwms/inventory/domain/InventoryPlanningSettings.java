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

/** Warehouse-local calendar and independent capacities used only to derive final-plan drafts. */
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

  public static InventoryPlanningSettings create(
      UUID warehouseId,
      int movementDailyCapacity,
      int repairDailyCapacity,
      String workingWeekdays,
      String holidays) {
    InventoryPlanningSettings value = new InventoryPlanningSettings();
    value.warehouseId = requireWarehouse(warehouseId);
    value.replace(movementDailyCapacity, repairDailyCapacity, workingWeekdays, holidays);
    return value;
  }

  public void replace(
      int nextMovementDailyCapacity,
      int nextRepairDailyCapacity,
      String nextWorkingWeekdays,
      String nextHolidays) {
    if (nextMovementDailyCapacity < 1 || nextMovementDailyCapacity > 1000) {
      throw new IllegalArgumentException("Movement daily capacity must be between 1 and 1000");
    }
    if (nextRepairDailyCapacity < 1 || nextRepairDailyCapacity > 1000) {
      throw new IllegalArgumentException("Repair daily capacity must be between 1 and 1000");
    }
    movementDailyCapacity = nextMovementDailyCapacity;
    repairDailyCapacity = nextRepairDailyCapacity;
    workingWeekdays = jsonArray(nextWorkingWeekdays, "working weekdays");
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

  public int getMovementDailyCapacity() {
    return movementDailyCapacity;
  }

  public int getRepairDailyCapacity() {
    return repairDailyCapacity;
  }

  public String getWorkingWeekdays() {
    return workingWeekdays;
  }

  public String getHolidays() {
    return holidays;
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
