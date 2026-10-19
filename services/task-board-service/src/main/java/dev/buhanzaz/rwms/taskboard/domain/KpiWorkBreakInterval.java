package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalTime;

/** One unpaid or non-working interval in an effective warehouse work schedule revision. */
@Entity
@Table(name = "kpi_work_break")
public class KpiWorkBreakInterval extends AbstractVersionedEntity {
  @Column(name = "break_start", nullable = false)
  private LocalTime start;

  @Column(name = "break_end", nullable = false)
  private LocalTime end;

  protected KpiWorkBreakInterval() {}

  public KpiWorkBreakInterval(LocalTime start, LocalTime end) {
    this.start = start;
    this.end = end;
  }

  public LocalTime getStart() {
    return start;
  }

  public LocalTime getEnd() {
    return end;
  }
}
