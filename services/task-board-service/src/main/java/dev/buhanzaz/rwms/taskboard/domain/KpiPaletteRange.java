package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Ordered inclusive KPI threshold range belonging to one palette revision. */
@Entity
@Table(name = "kpi_palette_range")
public class KpiPaletteRange extends AbstractVersionedEntity {
  @Column(name = "from_percent", nullable = false)
  private int fromPercent;

  @Column(name = "to_percent", nullable = false)
  private int toPercent;

  @Column(name = "color", nullable = false, length = 7)
  private String color;

  protected KpiPaletteRange() {}

  public KpiPaletteRange(int fromPercent, int toPercent, String color) {
    this.fromPercent = fromPercent;
    this.toPercent = toPercent;
    this.color = color;
  }

  public int getFromPercent() {
    return fromPercent;
  }

  public int getToPercent() {
    return toPercent;
  }

  public String getColor() {
    return color;
  }
}
