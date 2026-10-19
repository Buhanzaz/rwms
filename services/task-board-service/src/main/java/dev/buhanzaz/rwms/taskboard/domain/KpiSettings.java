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
import java.time.LocalDate;
import java.util.UUID;

/** Single installation-wide head for KPI presentation and work-schedule revisions. */
@Entity
@Table(name = "kpi_settings")
public class KpiSettings extends AbstractVersionedEntity {
  public static final UUID SINGLETON_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "palette_id",
      foreignKey = @ForeignKey(name = "fk_kpi_settings_palette"))
  private KpiPalette palette;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private KpiSettingsStatus status = KpiSettingsStatus.UNCONFIGURED;

  @Column(name = "data_available_from")
  private LocalDate dataAvailableFrom;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "active_schedule_id",
      foreignKey = @ForeignKey(name = "fk_kpi_settings_active_schedule"))
  private KpiWorkScheduleRevision activeSchedule;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "pending_schedule_id",
      foreignKey = @ForeignKey(name = "fk_kpi_settings_pending_schedule"))
  private KpiWorkScheduleRevision pendingSchedule;

  protected KpiSettings() {}

  public static KpiSettings create() {
    var settings = new KpiSettings();
    settings.assignReviewedId(SINGLETON_ID);
    return settings;
  }

  /** Replaces the shared palette and advances the aggregate concurrency fence. */
  public void setPalette(KpiPalette palette) {
    this.palette = palette;
    touch();
  }

  /** Replaces the single draft or scheduled future revision. */
  public void setPendingSchedule(KpiWorkScheduleRevision schedule) {
    if (activeSchedule == null && pendingSchedule != null) {
      dataAvailableFrom = null;
    }
    pendingSchedule = schedule;
    recalculateStatus();
    touch();
  }

  /** Removes the unactivated schedule revision. */
  public void removePendingSchedule() {
    if (activeSchedule == null && pendingSchedule != null) {
      dataAvailableFrom = null;
    }
    pendingSchedule = null;
    recalculateStatus();
    touch();
  }

  /** Marks the pending revision as scheduled for every warehouse. */
  public void activatePendingSchedule() {
    if (pendingSchedule == null) {
      throw new IllegalStateException("Для активации необходимо настроить рабочий график");
    }
    pendingSchedule.schedule();
    if (dataAvailableFrom == null
        || pendingSchedule.getEffectiveFrom().isBefore(dataAvailableFrom)) {
      dataAvailableFrom = pendingSchedule.getEffectiveFrom();
    }
    recalculateStatus();
    touch();
  }

  /** Promotes a scheduled revision once its common local calendar date is reached. */
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

  public KpiPalette getPalette() {
    return palette;
  }

  public KpiSettingsStatus getStatus() {
    return status;
  }

  public LocalDate getDataAvailableFrom() {
    return dataAvailableFrom;
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
    if (pendingSchedule != null && pendingSchedule.isScheduled()) {
      status = KpiSettingsStatus.SCHEDULED;
    } else if (pendingSchedule != null) {
      status = KpiSettingsStatus.DRAFT;
    } else if (activeSchedule != null) {
      status = KpiSettingsStatus.ACTIVE;
    } else {
      status = KpiSettingsStatus.UNCONFIGURED;
    }
  }

  private void touch() {
    revisionMarker = UUID.randomUUID();
  }
}
