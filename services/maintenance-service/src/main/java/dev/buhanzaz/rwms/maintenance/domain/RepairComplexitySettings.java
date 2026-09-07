package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Global thresholds that map repair complexity to planned duration. */
@Entity
@Table(name = "global_repair_complexity_settings")
public class RepairComplexitySettings {
  public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  public static final int DEFAULT_LIGHT_BOUNDARY_MINUTES = 60;
  public static final int DEFAULT_MEDIUM_BOUNDARY_MINUTES = 180;
  public static final int DEFAULT_COMPLEX_BOUNDARY_MINUTES = 360;

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "light_boundary_minutes", nullable = false)
  private int lightBoundaryMinutes;

  @Column(name = "medium_boundary_minutes", nullable = false)
  private int mediumBoundaryMinutes;

  @Column(name = "complex_boundary_minutes", nullable = false)
  private int complexBoundaryMinutes;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RepairComplexitySettings() {}

  /** Creates the singleton thresholds; production initialization belongs to Flyway. */
  public static RepairComplexitySettings create(
      int lightBoundaryMinutes, int mediumBoundaryMinutes, int complexBoundaryMinutes) {
    validate(lightBoundaryMinutes, mediumBoundaryMinutes, complexBoundaryMinutes);
    RepairComplexitySettings value = new RepairComplexitySettings();
    value.id = SINGLETON_ID;
    value.lightBoundaryMinutes = lightBoundaryMinutes;
    value.mediumBoundaryMinutes = mediumBoundaryMinutes;
    value.complexBoundaryMinutes = complexBoundaryMinutes;
    return value;
  }

  public void replace(
      int lightBoundaryMinutes,
      int mediumBoundaryMinutes,
      int complexBoundaryMinutes) {
    validate(lightBoundaryMinutes, mediumBoundaryMinutes, complexBoundaryMinutes);
    this.lightBoundaryMinutes = lightBoundaryMinutes;
    this.mediumBoundaryMinutes = mediumBoundaryMinutes;
    this.complexBoundaryMinutes = complexBoundaryMinutes;
  }

  public RepairComplexity classify(java.math.BigDecimal plannedMinutes, boolean forcedCapital) {
    if (plannedMinutes == null || plannedMinutes.signum() < 0) {
      throw new IllegalArgumentException("plannedMinutes must not be negative");
    }
    if (forcedCapital) {
      return RepairComplexity.CAPITAL;
    }
    if (plannedMinutes.compareTo(java.math.BigDecimal.valueOf(lightBoundaryMinutes)) <= 0) {
      return RepairComplexity.LIGHT;
    }
    if (plannedMinutes.compareTo(java.math.BigDecimal.valueOf(mediumBoundaryMinutes)) <= 0) {
      return RepairComplexity.MEDIUM;
    }
    if (plannedMinutes.compareTo(java.math.BigDecimal.valueOf(complexBoundaryMinutes)) <= 0) {
      return RepairComplexity.COMPLEX;
    }
    return RepairComplexity.CAPITAL;
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

  private static void validate(int light, int medium, int complex) {
    if (light < 1 || light >= medium || medium >= complex) {
      throw new IllegalArgumentException(
          "Repair complexity boundaries must be positive and strictly increasing");
    }
  }

  public UUID getId() {
    return id; }
  public long getVersion() { return version; }
  public int getLightBoundaryMinutes() { return lightBoundaryMinutes; }
  public int getMediumBoundaryMinutes() { return mediumBoundaryMinutes; }
  public int getComplexBoundaryMinutes() { return complexBoundaryMinutes; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
