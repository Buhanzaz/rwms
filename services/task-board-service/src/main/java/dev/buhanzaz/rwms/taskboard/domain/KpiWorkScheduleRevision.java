package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Effective-dated warehouse work schedule revision used for deterministic KPI time accounting.
 */
@Entity
@Table(name = "kpi_work_schedule")
public class KpiWorkScheduleRevision extends AbstractVersionedEntity {
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "shift_start", nullable = false)
  private LocalTime shiftStart;

  @Column(name = "shift_end", nullable = false)
  private LocalTime shiftEnd;

  @Column(name = "days_off_mask", nullable = false)
  private int daysOffMask;

  @Column(name = "scheduled", nullable = false)
  private boolean scheduled;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "schedule_id", nullable = false)
  @OrderBy("start ASC")
  private final List<KpiWorkBreakInterval> breaks = new ArrayList<>();

  protected KpiWorkScheduleRevision() {}

  public KpiWorkScheduleRevision(
      UUID warehouseId,
      LocalDate effectiveFrom,
      LocalTime shiftStart,
      LocalTime shiftEnd,
      List<Integer> daysOff,
      List<KpiWorkBreakInterval> breaks) {
    this.warehouseId = warehouseId;
    this.effectiveFrom = effectiveFrom;
    this.shiftStart = shiftStart;
    this.shiftEnd = shiftEnd;
    this.daysOffMask = encodeDaysOff(daysOff);
    this.breaks.addAll(breaks);
  }

  public void schedule() {
    scheduled = true;
  }

  public void unschedule() {
    scheduled = false;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalTime getShiftStart() {
    return shiftStart;
  }

  public LocalTime getShiftEnd() {
    return shiftEnd;
  }

  public List<Integer> getDaysOff() {
    var result = new ArrayList<Integer>();
    for (int day = 1; day <= 7; day++) {
      if ((daysOffMask & (1 << (day - 1))) != 0) {
        result.add(day);
      }
    }
    return List.copyOf(result);
  }

  public List<KpiWorkBreakInterval> getBreaks() {
    return Collections.unmodifiableList(breaks);
  }

  public boolean isScheduled() {
    return scheduled;
  }

  private static int encodeDaysOff(List<Integer> daysOff) {
    int mask = 0;
    for (Integer day : daysOff) {
      if (day == null || day < 1 || day > 7 || (mask & (1 << (day - 1))) != 0) {
        throw new IllegalArgumentException("Выходные дни настроены некорректно");
      }
      mask |= 1 << (day - 1);
    }
    return mask;
  }
}
