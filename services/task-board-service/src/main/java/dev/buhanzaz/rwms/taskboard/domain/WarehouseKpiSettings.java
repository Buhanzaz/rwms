package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "warehouse_kpi_settings",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_warehouse_kpi_settings_warehouse",
            columnNames = "warehouse_id"))
public class WarehouseKpiSettings extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @NotBlank
  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private KpiSettingsStatus status = KpiSettingsStatus.UNCONFIGURED;

  @Column(name = "data_available_from")
  private LocalDate dataAvailableFrom;

  @Column(name = "repair_light_boundary_minutes", nullable = false)
  private int repairLightBoundaryMinutes = 60;

  @Column(name = "repair_medium_boundary_minutes", nullable = false)
  private int repairMediumBoundaryMinutes = 180;

  @Column(name = "repair_complex_boundary_minutes", nullable = false)
  private int repairComplexBoundaryMinutes = 360;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "palette_id",
      foreignKey = @ForeignKey(name = "fk_warehouse_kpi_settings_palette"))
  private KpiPalette palette;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "active_schedule_id",
      foreignKey = @ForeignKey(name = "fk_warehouse_kpi_settings_active_schedule"))
  private KpiWorkScheduleRevision activeSchedule;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "pending_schedule_id",
      foreignKey = @ForeignKey(name = "fk_warehouse_kpi_settings_pending_schedule"))
  private KpiWorkScheduleRevision pendingSchedule;

  protected WarehouseKpiSettings() {}

  public static WarehouseKpiSettings create(UUID warehouseId, String timeZone) {
    var settings = new WarehouseKpiSettings();
    settings.warehouseId = warehouseId;
    settings.timeZone = timeZone;
    return settings;
  }

  public void setPalette(KpiPalette palette) {
    this.palette = palette;
    recalculateStatus();
    touch();
  }

  public void setPendingSchedule(KpiWorkScheduleRevision schedule) {
    this.pendingSchedule = schedule;
    recalculateStatus();
    touch();
  }

  public void removePendingSchedule() {
    pendingSchedule = null;
    recalculateStatus();
    touch();
  }

  public void activatePendingSchedule() {
    if (palette == null || pendingSchedule == null) {
      throw new IllegalStateException(
          "Для активации необходимо настроить палитру и рабочий график");
    }
    pendingSchedule.schedule();
    if (dataAvailableFrom == null) {
      dataAvailableFrom = pendingSchedule.getEffectiveFrom();
    }
    recalculateStatus();
    touch();
  }

  public boolean promoteSchedule(LocalDate date) {
    if (pendingSchedule == null
        || !pendingSchedule.isScheduled()
        || pendingSchedule.getEffectiveFrom().isAfter(date)) {
      return false;
    }
    activeSchedule = pendingSchedule;
    pendingSchedule = null;
    status = KpiSettingsStatus.ACTIVE;
    touch();
    return true;
  }

  public void synchronizeTimeZone(String timeZone) {
    if (!this.timeZone.equals(timeZone)) {
      this.timeZone = timeZone;
      touch();
    }
  }

  public void setRepairComplexityBoundaries(
      int lightBoundaryMinutes,
      int mediumBoundaryMinutes,
      int complexBoundaryMinutes) {
    if (lightBoundaryMinutes <= 0
        || lightBoundaryMinutes >= mediumBoundaryMinutes
        || mediumBoundaryMinutes >= complexBoundaryMinutes) {
      throw new IllegalArgumentException(
          "Границы сложности ремонта должны быть положительными и строго возрастать");
    }
    repairLightBoundaryMinutes = lightBoundaryMinutes;
    repairMediumBoundaryMinutes = mediumBoundaryMinutes;
    repairComplexBoundaryMinutes = complexBoundaryMinutes;
    touch();
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public KpiSettingsStatus getStatus() {
    return status;
  }

  public LocalDate getDataAvailableFrom() {
    return dataAvailableFrom;
  }

  public int getRepairLightBoundaryMinutes() {
    return repairLightBoundaryMinutes;
  }

  public int getRepairMediumBoundaryMinutes() {
    return repairMediumBoundaryMinutes;
  }

  public int getRepairComplexBoundaryMinutes() {
    return repairComplexBoundaryMinutes;
  }

  public KpiPalette getPalette() {
    return palette;
  }

  public KpiWorkScheduleRevision getActiveSchedule() {
    return activeSchedule;
  }

  public KpiWorkScheduleRevision getPendingSchedule() {
    return pendingSchedule;
  }

  public UUID getRevisionMarker() {
    return revisionMarker;
  }

  private void recalculateStatus() {
    if (activeSchedule != null) {
      status = KpiSettingsStatus.ACTIVE;
    } else if (pendingSchedule != null && pendingSchedule.isScheduled()) {
      status = KpiSettingsStatus.SCHEDULED;
    } else if (palette != null || pendingSchedule != null) {
      status = KpiSettingsStatus.DRAFT;
    } else {
      status = KpiSettingsStatus.UNCONFIGURED;
    }
  }

  private void touch() {
    revisionMarker = UUID.randomUUID();
  }
}
